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
import com.sam.btagent.databinding.FragmentAcousticLoopbackBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AcousticLoopbackFragment : Fragment(), MainActivity.TestStatusProvider {

    private var _binding: FragmentAcousticLoopbackBinding? = null
    private val binding get() = _binding!!

    // Audio Settings
    private val sampleRate = 44100
    private val targetFreq = 1000.0
    private var isRunning = false
    private var lastDetectionState = false

    // Tone Generation
    private var audioTrack: AudioTrack? = null
    private var isPlayingTone = false

    // Recording and Analysis
    private var audioRecord: AudioRecord? = null
    private var analysisThread: Thread? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAcousticLoopbackBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnToggleTone.setOnClickListener {
            if (isPlayingTone) {
                stopLoopback()
            } else {
                startLoopback()
            }
        }
    }

    private fun startLoopback() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }

        isRunning = true
        isPlayingTone = true
        binding.btnToggleTone.text = "Stop 1kHz Tone"
        binding.statusIndicator.text = "MONITORING"
        binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark))
        
        addLog("Starting 1kHz loopback test...")
        
        startTone()
        startAnalysis()
    }

    private fun stopLoopback() {
        isRunning = false
        isPlayingTone = false
        binding.btnToggleTone.text = "Start 1kHz Tone"
        binding.statusIndicator.text = "IDLE"
        binding.statusIndicator.setBackgroundColor(0xFFDDDDDD.toInt())
        
        stopTone()
        stopAnalysis()
        addLog("Test stopped.")
    }

    private fun startTone() {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) return

        try {
            audioTrack = AudioTrack(
                android.media.AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                addLog("Error: AudioTrack not initialized")
                return
            }

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
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    private fun startAnalysis() {
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) return
        
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            addLog("Error: No Record Permission")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                addLog("Error: AudioRecord not initialized")
                return
            }

            audioRecord?.startRecording()

            analysisThread = Thread {
                val buffer = ShortArray(1024)
                try {
                    while (isRunning && audioRecord != null) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            val magnitude = goertzel(buffer, targetFreq, sampleRate)
                            val rms = calculateRMS(buffer)
                            
                            activity?.runOnUiThread {
                                updateUI(magnitude, rms)
                            }
                        }
                    }
                } catch (e: Exception) {
                    activity?.runOnUiThread { addLog("Analysis Error: ${e.message}") }
                }
            }
            analysisThread?.start()
        } catch (e: SecurityException) {
            addLog("Security Error: ${e.message}")
        } catch (e: Exception) {
            addLog("Setup Error: ${e.message}")
        }
    }

    private fun stopAnalysis() {
        isRunning = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        analysisThread = null
    }

    private fun goertzel(samples: ShortArray, targetFreq: Double, sampleRate: Int): Double {
        val n = samples.size
        val k = (0.5 + (n * targetFreq / sampleRate)).toInt()
        val omega = 2.0 * PI * k / n
        val sine = sin(omega)
        val cosine = kotlin.math.cos(omega)
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

    private fun calculateRMS(samples: ShortArray): Double {
        var sum = 0.0
        for (sample in samples) {
            sum += sample * sample
        }
        return sqrt(sum / samples.size)
    }

    private fun updateUI(magnitude: Double, rms: Double) {
        if (!isRunning) return

        // Normalize RMS to 0-100 for progress bar
        val level = (rms / 32768.0 * 100 * 5).coerceIn(0.0, 100.0).toInt()
        binding.levelProgressBar.progress = level

        binding.magnitudeText.text = String.format(Locale.US, "Magnitude (1kHz): %.1f", magnitude)

        // Threshold check for 1kHz detection
        val detectionThreshold = 75000.0 // Adjusted for better sensitivity
        val isDetected = magnitude > detectionThreshold

        if (isDetected != lastDetectionState) {
            lastDetectionState = isDetected
            if (isDetected) {
                addLog("1kHz Tone DETECTED")
            } else {
                addLog("1kHz Tone LOST")
            }
        }

        if (isDetected) {
            binding.statusIndicator.text = "DETECTED"
            binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
        } else {
            binding.statusIndicator.text = "NO TONE"
            binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
        }
    }

    private fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $message\n"
        binding.acousticLogText.append(entry)
    }

    override fun isTestRunning(): Boolean = isPlayingTone

    override fun stopTest() {
        stopLoopback()
    }

    override fun onDestroyView() {
        stopLoopback()
        super.onDestroyView()
        _binding = null
    }
}
