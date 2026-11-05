package com.sam.btagent

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.sam.btagent.databinding.FragmentAudioLatencyBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

class AudioLatencyFragment : Fragment(), MainActivity.TestStatusProvider {

    private var _binding: FragmentAudioLatencyBinding? = null
    private val binding get() = _binding!!

    // Audio Settings
    private val sampleRate = 44100
    private val freq1 = 1000.0
    private val freq2 = 2000.0

    @Volatile private var currentFreq = freq1
    @Volatile private var isTestRunning = false
    @Volatile private var isSwitching = false
    @Volatile private var switchTimestamp: Long = 0
    private val switchLock = Any()

    /**
     * Atomically end the current switch. Exactly one of {detection, timeout} wins a round,
     * so a detection racing with the timeout can't start two next rounds.
     */
    private fun claimSwitch(): Boolean = synchronized(switchLock) {
        if (isSwitching) { isSwitching = false; true } else false
    }

    // Multi-test logic
    private val maxTests = 5
    /** Give up on a round if 2 kHz is not heard within this time after the switch. */
    private val roundTimeoutMs = 3000L
    private var currentTestCount = 0
    private var timedOutRounds = 0
    // Only touched on the main thread.
    private val latencyResults = mutableListOf<Long>()

    // All delayed work goes through this handler so stop can cancel it in one call
    // (binding.root.postDelayed would outlive the view and dereference a null binding).
    private val mainHandler = Handler(Looper.getMainLooper())

    // Audio Components
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var playbackThread: Thread? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAudioLatencyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnStartTest.setOnClickListener {
            startLatencyTest()
        }

        binding.btnStopTest.setOnClickListener {
            stopLatencyTest()
        }
    }

    private fun startLatencyTest() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 102)
            return
        }

        isTestRunning = true
        isSwitching = false
        currentFreq = freq1
        currentTestCount = 0
        timedOutRounds = 0
        latencyResults.clear()

        binding.btnStartTest.visibility = View.GONE
        binding.btnStopTest.visibility = View.VISIBLE
        binding.tvLatencyResult.text = getString(R.string.measured_latency_none)
        binding.tvLatencyStats.text = "Jitter: -- ms | P95: -- ms"

        addLog("Starting Audio Latency Test ($maxTests rounds)...")

        startPlayback()
        startRecording()

        runNextTest()
    }

    private fun runNextTest() {
        if (!isTestRunning) return

        if (currentTestCount >= maxTests) {
            finishMultiTest()
            return
        }

        currentTestCount++
        isSwitching = false
        currentFreq = freq1
        addLog("--- Round $currentTestCount ---")
        addLog("Stabilizing with 1kHz...")

        val round = currentTestCount
        // Delay before switching to 2kHz
        mainHandler.postDelayed({
            if (isTestRunning && currentTestCount == round) {
                triggerFrequencySwitch(round)
            }
        }, 1500)
    }

    private fun triggerFrequencySwitch(round: Int) {
        if (!isTestRunning) return
        addLog("Switching to 2kHz...")
        isSwitching = true

        // Without a timeout a quiet speaker / dropped BT link would hang the test forever.
        mainHandler.postDelayed({
            if (isTestRunning && currentTestCount == round && claimSwitch()) {
                timedOutRounds++
                addLog("Round $round: 2kHz not detected within ${roundTimeoutMs}ms (timeout) — skipped")
                mainHandler.postDelayed({ runNextTest() }, 1000)
            }
        }, roundTimeoutMs)
    }

    private fun finishMultiTest() {
        val ctx = context ?: return stopLatencyTest()
        if (latencyResults.isNotEmpty()) {
            val average = latencyResults.average().toLong()
            val jitter = SignalUtils.stdDev(latencyResults)
            val p95 = SignalUtils.percentile(latencyResults, 0.95)
            binding.tvLatencyResult.text = getString(R.string.average_latency_format, average)
            binding.tvLatencyStats.text = String.format(Locale.US, "Jitter: %.1f ms | P95: %d ms", jitter, p95)
            addLog("==========================")
            addLog("Test Finished.")
            addLog("Results: ${latencyResults.joinToString(", ")} ms")
            if (timedOutRounds > 0) addLog("Timed-out rounds: $timedOutRounds / $maxTests")
            addLog("Average Latency: $average ms")
            addLog(String.format(Locale.US, "Latency Jitter (StdDev): %.1f ms", jitter))
            addLog("P95 Latency: $p95 ms")
            LogPersistenceManager.persistTestSummary(
                ctx,
                "AudioLatency",
                "LatencyStats",
                "${average}ms",
                String.format(Locale.US, "jitter=%.1fms; p95=%dms; samples=%s; timeouts=%d",
                    jitter, p95, latencyResults.joinToString("|"), timedOutRounds)
            )
            addLog("==========================")
        } else {
            addLog("==========================")
            addLog("Test Finished: no valid measurement ($timedOutRounds / $maxTests rounds timed out).")
            addLog("Check BT connection, speaker volume and mic distance.")
            LogPersistenceManager.persistTestSummary(
                ctx, "AudioLatency", "LatencyStats", "FAIL",
                "no 2kHz detected; timeouts=$timedOutRounds"
            )
            addLog("==========================")
        }

        stopLatencyTest()
    }

    private fun stopLatencyTest() {
        val wasRunning = isTestRunning || audioTrack != null || audioRecord != null
        isTestRunning = false
        isSwitching = false
        mainHandler.removeCallbacksAndMessages(null)

        _binding?.let {
            it.btnStartTest.visibility = View.VISIBLE
            it.btnStopTest.visibility = View.GONE
        }

        // Flags are cleared above so the worker loops exit; AudioIo unblocks the
        // pending read()/write(), joins the threads, and only then releases.
        AudioIo.shutdown(listOf(playbackThread, recordingThread), audioTrack, audioRecord)
        playbackThread = null
        recordingThread = null
        audioTrack = null
        audioRecord = null
        if (wasRunning) addLog("Test stopped.")
    }

    private fun startPlayback() {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        val track = AudioTrack(
            android.media.AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
            AudioTrack.MODE_STREAM
        )
        audioTrack = track

        val samples = ShortArray(bufferSize)
        var phase = 0.0

        track.play()

        playbackThread = Thread({
            try {
                while (isTestRunning) {
                    val targetFreq = if (isSwitching) freq2 else freq1

                    // If we just switched, we need to mark the exact time
                    if (isSwitching && currentFreq == freq1) {
                        switchTimestamp = System.currentTimeMillis()
                        currentFreq = freq2
                    } else if (!isSwitching && currentFreq == freq2) {
                        currentFreq = freq1
                    }

                    val phaseIncrement = 2 * PI * targetFreq / sampleRate
                    for (i in samples.indices) {
                        samples[i] = (sin(phase) * Short.MAX_VALUE * 0.8).toInt().toShort()
                        phase += phaseIncrement
                        if (phase > 2 * PI) phase -= 2 * PI
                    }
                    track.write(samples, 0, samples.size)
                }
            } catch (e: Exception) {
                activity?.runOnUiThread { addLog("Playback Error: ${e.message}") }
            }
        }, "LatencyPlayback").apply { start() }
    }

    private fun startRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        audioRecord = record

        record.startRecording()

        recordingThread = Thread({
            val buffer = ShortArray(441) // ~10ms window
            val threshold = 50000.0 // Detection threshold

            try {
                while (isTestRunning) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read < 0) break      // error code (e.g. after stop) — don't busy-spin
                    if (read > 0) {
                        // Only detect 2kHz if we are in switching mode and playback thread has updated frequency
                        if (isSwitching && currentFreq == freq2) {
                            val mag2k = SignalUtils.goertzelMagnitude(buffer, freq2, sampleRate, read)
                            if (mag2k > threshold) {
                                val detectTime = System.currentTimeMillis()
                                val latency = detectTime - switchTimestamp

                                // Critical: claim the round so neither a second detection nor the
                                // timeout can also advance it.
                                if (!claimSwitch()) continue

                                mainHandler.post {
                                    if (!isTestRunning) return@post
                                    latencyResults.add(latency)
                                    onLatencyMeasured(latency)
                                    // Small delay before next round to allow 1kHz stabilization
                                    mainHandler.postDelayed({ runNextTest() }, 1000)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread { addLog("Recording Error: ${e.message}") }
            }
        }, "LatencyRecord").apply { start() }
    }

    private fun onLatencyMeasured(latency: Long) {
        val b = _binding ?: return
        b.tvLatencyResult.text = getString(R.string.measured_latency_format, latency)
        if (latencyResults.isNotEmpty()) {
            val jitter = SignalUtils.stdDev(latencyResults)
            val p95 = SignalUtils.percentile(latencyResults, 0.95)
            b.tvLatencyStats.text = String.format(Locale.US, "Jitter: %.1f ms | P95: %d ms", jitter, p95)
        }
        addLog("Latency detected: $latency ms")
    }

    private fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $message\n"
        _binding?.tvLog?.append(entry)
    }

    override fun isTestRunning(): Boolean = isTestRunning

    override fun stopTest() {
        stopLatencyTest()
    }

    override fun onDestroyView() {
        stopLatencyTest()
        super.onDestroyView()
        _binding = null
    }
}
