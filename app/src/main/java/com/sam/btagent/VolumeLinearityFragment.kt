package com.sam.btagent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
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
import com.sam.btagent.databinding.FragmentVolumeLinearityBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class VolumeLinearityFragment : Fragment(), MainActivity.TestStatusProvider {

    private var _binding: FragmentVolumeLinearityBinding? = null
    private val binding get() = _binding!!

    private lateinit var audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Audio Settings
    private val sampleRate = 44100
    private val targetFreq = 1000.0
    @Volatile private var isTestRunning = false

    // Tone Generation
    private var audioTrack: AudioTrack? = null
    @Volatile private var isPlayingTone = false
    private var toneThread: Thread? = null

    // Recording and Analysis
    private var audioRecord: AudioRecord? = null
    private var analysisThread: Thread? = null

    // Test State
    /** Read from the device: STREAM_MUSIC is not 15 steps on every phone (e.g. 25 on Pixel). */
    private var maxSteps = 15
    @Volatile private var currentStep = 0
    private val stepDurationMs = 2000L
    /** Ignore the first part of each step while the volume change settles. */
    private val settleMs = 800L
    /** Step currently being sampled, or -1 while settling. Written on main, read on analysis thread. */
    @Volatile private var measuringStep = -1
    /** step -> magnitudes collected after settling. Guarded by itself. */
    private val stepSamples = HashMap<Int, MutableList<Double>>()

    /** User's volume before the test, restored on stop. null = nothing to restore. */
    private var originalVolume: Int? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVolumeLinearityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxSteps = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        binding.testProgressBar.max = maxSteps
        binding.tvCurrentStep.text = "Current Step: - / $maxSteps"

        binding.btnStartTest.setOnClickListener {
            startLinearityTest()
        }

        binding.btnStopTest.setOnClickListener {
            stopTest()
        }
    }

    private fun startLinearityTest() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 102)
            return
        }

        maxSteps = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        binding.testProgressBar.max = maxSteps
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        isTestRunning = true
        binding.btnStartTest.isEnabled = false
        binding.btnStopTest.isEnabled = true
        binding.tvLog.text = ""
        synchronized(stepSamples) { stepSamples.clear() }
        currentStep = 0
        measuringStep = -1

        addLog("Starting Volume Linearity Test (0-$maxSteps), original volume=$originalVolume...")
        startTone()
        startAnalysis()

        runNextStep()
    }

    private fun runNextStep() {
        if (!isTestRunning) return
        val b = _binding ?: return

        if (currentStep <= maxSteps) {
            val step = currentStep
            b.tvCurrentStep.text = "Current Step: $step / $maxSteps"
            b.testProgressBar.progress = step

            measuringStep = -1
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
            addLog("Step $step: Setting volume to $step...")

            // Sample only after the volume change has settled.
            mainHandler.postDelayed({
                if (isTestRunning && currentStep == step) measuringStep = step
            }, settleMs)

            mainHandler.postDelayed({
                if (isTestRunning) {
                    measuringStep = -1
                    currentStep++
                    runNextStep()
                }
            }, stepDurationMs)
        } else {
            finishTest()
        }
    }

    private fun finishTest() {
        measuringStep = -1
        val means: List<Pair<Int, Double>> = synchronized(stepSamples) {
            (0..maxSteps).mapNotNull { step ->
                stepSamples[step]?.takeIf { it.isNotEmpty() }?.let { step to it.average() }
            }
        }
        val peak = means.maxOfOrNull { it.second } ?: 0.0

        addLog("Test Complete.")
        addLog("--- Summary (mean magnitude, dB re. loudest step) ---")
        val details = means.map { (step, mag) ->
            val db = if (peak > 0 && mag > 0) 20.0 * log10(mag / peak) else Double.NEGATIVE_INFINITY
            addLog(String.format(Locale.US, "Step %d: Magnitude = %.1f (%.1f dB)", step, mag, db))
            String.format(Locale.US, "%d:%.1f:%.1fdB", step, mag, db)
        }
        val missing = (maxSteps + 1) - means.size
        if (missing > 0) addLog("Warning: $missing step(s) had no settled samples")

        context?.let { ctx ->
            LogPersistenceManager.persistTestSummary(
                ctx,
                "VolumeLinearity",
                "StepMagnitudes",
                "${means.size}/${maxSteps + 1} steps",
                "maxSteps=$maxSteps; step:mag:dB=" + details.joinToString("|")
            )
            addLog("Saved to TestSummary CSV.")
        }
        stopTest()
    }

    override fun stopTest() {
        val wasRunning = isTestRunning
        isTestRunning = false
        isPlayingTone = false
        measuringStep = -1
        mainHandler.removeCallbacksAndMessages(null)

        // Flags are cleared first; AudioIo unblocks write()/read(), joins, then releases.
        AudioIo.shutdown(listOf(toneThread, analysisThread), audioTrack, audioRecord)
        toneThread = null
        analysisThread = null
        audioTrack = null
        audioRecord = null

        // Don't leave the user's phone at max volume.
        originalVolume?.let { vol ->
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0) }
            if (wasRunning) addLog("Volume restored to $vol")
        }
        originalVolume = null

        _binding?.let {
            it.btnStartTest.isEnabled = true
            it.btnStopTest.isEnabled = false
            it.tvCurrentStep.text = "Current Step: - / $maxSteps"
        }
    }

    private fun startTone() {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) return

        try {
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
            audioTrack = track

            isPlayingTone = true
            val samples = ShortArray(bufferSize)
            var phase = 0.0
            val phaseIncrement = 2 * PI * targetFreq / sampleRate

            track.play()

            toneThread = Thread({
                try {
                    while (isPlayingTone) {
                        for (i in samples.indices) {
                            samples[i] = (sin(phase) * Short.MAX_VALUE).toInt().toShort()
                            phase += phaseIncrement
                            if (phase > 2 * PI) phase -= 2 * PI
                        }
                        track.write(samples, 0, samples.size)
                    }
                } catch (e: Exception) {
                    activity?.runOnUiThread { addLog("Tone Error: ${e.message}") }
                }
            }, "VolumeTone").apply { start() }
        } catch (e: Exception) {
            addLog("AudioTrack setup failed: ${e.message}")
        }
    }

    private fun startAnalysis() {
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) return

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            audioRecord = record

            record.startRecording()

            analysisThread = Thread({
                val buffer = ShortArray(1024)
                var lastUiTime = 0L
                try {
                    while (isTestRunning) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read < 0) break      // error code (e.g. after stop) — don't busy-spin
                        if (read == 0) continue
                        val magnitude = SignalUtils.goertzelMagnitude(buffer, targetFreq, sampleRate, read)

                        val step = measuringStep
                        if (step >= 0) {
                            synchronized(stepSamples) {
                                stepSamples.getOrPut(step) { mutableListOf() }.add(magnitude)
                            }
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastUiTime > 500) { // Update UI magnitude every 500ms
                            lastUiTime = now
                            activity?.runOnUiThread {
                                _binding?.tvCurrentMagnitude?.text =
                                    String.format(Locale.US, "Last Magnitude: %.1f", magnitude)
                            }
                        }
                    }
                } catch (e: Exception) {
                    activity?.runOnUiThread { addLog("Analysis Error: ${e.message}") }
                }
            }, "VolumeAnalysis").apply { start() }
        } catch (e: Exception) {
            addLog("Analysis Setup Error: ${e.message}")
        }
    }

    private fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $message\n"
        activity?.runOnUiThread {
            _binding?.tvLog?.append(entry)
        }
    }

    override fun isTestRunning(): Boolean = isTestRunning

    override fun onDestroyView() {
        stopTest()
        super.onDestroyView()
        _binding = null
    }
}
