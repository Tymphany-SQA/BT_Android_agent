package com.sam.btagent

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

    // Multi-test logic
    private val maxTests = 5
    private var currentTestCount = 0
    private val latencyResults = mutableListOf<Long>()

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
        latencyResults.clear()
        
        binding.btnStartTest.visibility = View.GONE
        binding.btnStopTest.visibility = View.VISIBLE
        binding.tvLatencyResult.text = getString(R.string.measured_latency_none)
        binding.tvLatencyStats.text = "Jitter: -- ms | P95: -- ms"
        
        addLog("Starting Audio Latency Test (5 rounds)...")
        
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

        // Delay before switching to 2kHz
        binding.root.postDelayed({
            if (isTestRunning) {
                triggerFrequencySwitch()
            }
        }, 1500)
    }

    private fun triggerFrequencySwitch() {
        if (!isTestRunning) return
        addLog("Switching to 2kHz...")
        isSwitching = true
    }

    private fun finishMultiTest() {
        if (latencyResults.isNotEmpty()) {
            val average = latencyResults.average().toLong()
            val jitter = calculateStdDev(latencyResults)
            val p95 = percentile(latencyResults, 0.95)
            binding.tvLatencyResult.text = getString(R.string.average_latency_format, average)
            binding.tvLatencyStats.text = String.format(Locale.US, "Jitter: %.1f ms | P95: %d ms", jitter, p95)
            addLog("==========================")
            addLog("Test Finished.")
            addLog("Results: ${latencyResults.joinToString(", ")} ms")
            addLog("Average Latency: $average ms")
            addLog(String.format(Locale.US, "Latency Jitter (StdDev): %.1f ms", jitter))
            addLog("P95 Latency: $p95 ms")
            LogPersistenceManager.persistTestSummary(
                requireContext(),
                "AudioLatency",
                "LatencyStats",
                "${average}ms",
                String.format(Locale.US, "jitter=%.1fms; p95=%dms; samples=%s", jitter, p95, latencyResults.joinToString("|"))
            )
            addLog("==========================")
        }
        
        stopLatencyTest()
    }

    private fun stopLatencyTest() {
        isTestRunning = false
        isSwitching = false
        
        binding.btnStartTest.visibility = View.VISIBLE
        binding.btnStopTest.visibility = View.GONE
        
        stopPlayback()
        stopRecording()
        addLog("Test stopped.")
    }

    private fun startPlayback() {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        
        audioTrack = AudioTrack(
            android.media.AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
            AudioTrack.MODE_STREAM
        )

        val samples = ShortArray(bufferSize)
        var phase = 0.0
        
        audioTrack?.play()

        playbackThread = Thread {
            try {
                while (isTestRunning && audioTrack != null) {
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
                    audioTrack?.write(samples, 0, samples.size)
                }
            } catch (e: Exception) {
                activity?.runOnUiThread { addLog("Playback Error: ${e.message}") }
            }
        }
        playbackThread?.start()
    }

    private fun stopPlayback() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        playbackThread = null
    }

    private fun startRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ShortArray(441) // ~10ms window
            val threshold = 50000.0 // Detection threshold
            
            try {
                while (isTestRunning && audioRecord != null) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // Only detect 2kHz if we are in switching mode and playback thread has updated frequency
                        if (isSwitching && currentFreq == freq2) {
                            val mag2k = goertzel(buffer, freq2, sampleRate)
                            if (mag2k > threshold) {
                                val detectTime = System.currentTimeMillis()
                                val latency = detectTime - switchTimestamp
                                
                                // Critical: Mark switching as done so we don't trigger multiple times in one round
                                isSwitching = false
                                
                                latencyResults.add(latency)
                                activity?.runOnUiThread {
                                    onLatencyMeasured(latency)
                                    // Small delay before next round to allow 1kHz stabilization
                                    binding.root.postDelayed({
                                        runNextTest()
                                    }, 1000)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread { addLog("Recording Error: ${e.message}") }
            }
        }
        recordingThread?.start()
    }

    private fun stopRecording() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordingThread = null
    }

    private fun onLatencyMeasured(latency: Long) {
        if (!isTestRunning) return
        binding.tvLatencyResult.text = getString(R.string.measured_latency_format, latency)
        if (latencyResults.isNotEmpty()) {
            val jitter = calculateStdDev(latencyResults)
            val p95 = percentile(latencyResults, 0.95)
            binding.tvLatencyStats.text = String.format(Locale.US, "Jitter: %.1f ms | P95: %d ms", jitter, p95)
        }
        addLog("Latency detected: $latency ms")
    }

    private fun calculateStdDev(values: List<Long>): Double {
        if (values.size < 2) return 0.0
        val avg = values.average()
        val variance = values.map { (it - avg) * (it - avg) }.average()
        return sqrt(variance)
    }

    private fun percentile(values: List<Long>, p: Double): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val index = kotlin.math.ceil(sorted.size * p).toInt().minus(1).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun goertzel(samples: ShortArray, targetFreq: Double, sampleRate: Int): Double {
        val n = samples.size
        val k = (0.5 + (n * targetFreq / sampleRate)).toInt()
        val omega = 2.0 * PI * k / n
        val cosine = cos(omega)
        val coeff = 2.0 * cosine

        var q0: Double
        var q1 = 0.0
        var q2 = 0.0

        for (sample in samples) {
            q0 = coeff * q1 - q2 + sample
            q2 = q1
            q1 = q0
        }

        return sqrt(q1 * q1 + q2 * q2 - coeff * q1 * q2)
    }

    private fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $message\n"
        binding.tvLog.append(entry)
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
