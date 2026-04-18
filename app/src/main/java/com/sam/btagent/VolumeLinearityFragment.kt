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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class VolumeLinearityFragment : Fragment(), MainActivity.TestStatusProvider {

    private var _binding: FragmentVolumeLinearityBinding? = null
    private val binding get() = _binding!!

    private lateinit var audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Audio Settings
    private val sampleRate = 44100
    private val targetFreq = 1000.0
    private var isTestRunning = false

    // Tone Generation
    private var audioTrack: AudioTrack? = null
    private var isPlayingTone = false

    // Recording and Analysis
    private var audioRecord: AudioRecord? = null
    private var analysisThread: Thread? = null
    
    // Test State
    private var currentStep = 0
    private val maxSteps = 15
    private val stepDurationMs = 2000L // 2 seconds per step
    private val results = mutableListOf<Pair<Int, Double>>()

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

        isTestRunning = true
        binding.btnStartTest.isEnabled = false
        binding.btnStopTest.isEnabled = true
        binding.tvLog.text = ""
        results.clear()
        currentStep = 0

        addLog("Starting Volume Linearity Test (0-15)...")
        startTone()
        startAnalysis()
        
        runNextStep()
    }

    private fun runNextStep() {
        if (!isTestRunning) return

        if (currentStep <= maxSteps) {
            binding.tvCurrentStep.text = "Current Step: $currentStep / $maxSteps"
            binding.testProgressBar.progress = currentStep
            
            // Set system volume
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentStep, 0)
            addLog("Step $currentStep: Setting volume to $currentStep...")

            // Wait for volume to settle and measure
            mainHandler.postDelayed({
                if (isTestRunning) {
                    // Capture current magnitude from analysis (simplified: we'll use a callback or shared var)
                    // For this implementation, we'll just log what the analysis thread is currently seeing
                    currentStep++
                    runNextStep()
                }
            }, stepDurationMs)
        } else {
            finishTest()
        }
    }

    private fun finishTest() {
        addLog("Test Complete.")
        addLog("--- Summary ---")
        results.forEach { (step, mag) ->
            addLog("Step $step: Magnitude = ${String.format("%.1f", mag)}")
        }
        stopTest()
    }

    override fun stopTest() {
        isTestRunning = false
        isPlayingTone = false
        mainHandler.removeCallbacksAndMessages(null)
        
        stopTone()
        stopAnalysis()

        activity?.runOnUiThread {
            binding.btnStartTest.isEnabled = true
            binding.btnStopTest.isEnabled = false
            binding.tvCurrentStep.text = "Current Step: - / 15"
        }
    }

    private fun startTone() {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) return

        try {
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )

            isPlayingTone = true
            val samples = ShortArray(bufferSize)
            var phase = 0.0
            val phaseIncrement = 2 * PI * targetFreq / sampleRate

            audioTrack?.play()

            Thread {
                try {
                    while (isPlayingTone && audioTrack != null) {
                        for (i in samples.indices) {
                            samples[i] = (sin(phase) * Short.MAX_VALUE).toInt().toShort()
                            phase += phaseIncrement
                            if (phase > 2 * PI) phase -= 2 * PI
                        }
                        audioTrack?.write(samples, 0, samples.size)
                    }
                } catch (e: Exception) {
                    activity?.runOnUiThread { addLog("Tone Error: ${e.message}") }
                }
            }.start()
        } catch (e: Exception) {
            addLog("AudioTrack setup failed: ${e.message}")
        }
    }

    private fun stopTone() {
        isPlayingTone = false
        audioTrack?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {}
        }
        audioTrack = null
    }

    private fun startAnalysis() {
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            audioRecord?.startRecording()

            analysisThread = Thread {
                val buffer = ShortArray(1024)
                var lastLogTime = 0L
                try {
                    while (isTestRunning && audioRecord != null) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            val magnitude = goertzel(buffer, targetFreq, sampleRate)
                            
                            val now = System.currentTimeMillis()
                            if (now - lastLogTime > 500) { // Update UI magnitude every 500ms
                                lastLogTime = now
                                activity?.runOnUiThread {
                                    if (_binding != null) {
                                        binding.tvCurrentMagnitude.text = String.format(Locale.US, "Last Magnitude: %.1f", magnitude)
                                    }
                                }
                                // Store result for current step if we are mid-step
                                if (currentStep in 0..maxSteps) {
                                    val existing = results.find { it.first == currentStep }
                                    if (existing == null) {
                                        results.add(currentStep to magnitude)
                                    } else {
                                        // Update with latest (maybe average later, but for now just update)
                                        results[results.indexOf(existing)] = currentStep to magnitude
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
            analysisThread?.start()
        } catch (e: Exception) {
            addLog("Analysis Setup Error: ${e.message}")
        }
    }

    private fun stopAnalysis() {
        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {}
        }
        audioRecord = null
        analysisThread = null
    }

    private fun goertzel(samples: ShortArray, targetFreq: Double, sampleRate: Int): Double {
        val n = samples.size
        val k = (0.5 + (n * targetFreq / sampleRate)).toInt()
        val omega = 2.0 * PI * k / n
        val cosine = cos(omega)
        val coeff = 2.0 * cosine

        var q0 = 0.0
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
        activity?.runOnUiThread {
            binding.tvLog.append(entry)
        }
    }

    override fun isTestRunning(): Boolean = isTestRunning

    override fun onDestroyView() {
        stopTest()
        super.onDestroyView()
        _binding = null
    }
}
