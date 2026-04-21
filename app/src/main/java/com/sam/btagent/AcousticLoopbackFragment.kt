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
    private val freqLeft = 1000.0
    private val freqRight = 2000.0
    private var isRunning = false
    
    private var lastLeftDetected = false
    private var lastRightDetected = false

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
        binding.btnToggleTone.text = "Stop Stereo Test"
        
        addLog("Starting Stereo Loopback (L:1k, R:2k)...")
        
        startTone()
        startAnalysis()
    }

    private fun stopLoopback() {
        isRunning = false
        isPlayingTone = false
        binding.btnToggleTone.text = "Start Stereo Test"
        
        binding.statusLeft.text = "IDLE"
        binding.statusLeft.setBackgroundColor(0xFFDDDDDD.toInt())
        binding.statusRight.text = "IDLE"
        binding.statusRight.setBackgroundColor(0xFFDDDDDD.toInt())

        stopTone()
        stopAnalysis()
        addLog("Test stopped.")
    }

    private fun startTone() {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) return

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val samples = ShortArray(bufferSize) // This buffer size is in bytes, so ShortArray(bufferSize) is actually twice the needed size, but it's safe. 
            // Better to use actual frame count
            val frameCount = bufferSize / 4 // 2 bytes per sample, 2 channels
            val stereoSamples = ShortArray(frameCount * 2)

            var phaseL = 0.0
            var phaseR = 0.0
            val phaseIncL = 2 * PI * freqLeft / sampleRate
            val phaseIncR = 2 * PI * freqRight / sampleRate

            audioTrack?.play()

            Thread {
                try {
                    while (isPlayingTone && audioTrack != null) {
                        for (i in 0 until frameCount) {
                            stereoSamples[i * 2] = (sin(phaseL) * 16384).toInt().toShort()
                            stereoSamples[i * 2 + 1] = (sin(phaseR) * 16384).toInt().toShort()
                            
                            phaseL += phaseIncL
                            if (phaseL > 2 * PI) phaseL -= 2 * PI
                            phaseR += phaseIncR
                            if (phaseR > 2 * PI) phaseR -= 2 * PI
                        }
                        audioTrack?.write(stereoSamples, 0, stereoSamples.size)
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
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
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

            audioRecord?.startRecording()

            analysisThread = Thread {
                val buffer = ShortArray(1024)
                while (isRunning && audioRecord != null) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val magL = goertzel(buffer, freqLeft, sampleRate)
                        val magR = goertzel(buffer, freqRight, sampleRate)
                        val rms = calculateRMS(buffer)
                        
                        activity?.runOnUiThread {
                            updateUI(magL, magR, rms)
                        }
                    }
                }
            }
            analysisThread?.start()
        } catch (e: Exception) {
            addLog("Setup Error: ${e.message}")
        }
    }

    private fun stopAnalysis() {
        isRunning = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
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
        for (sample in samples) sum += sample * sample
        return sqrt(sum / samples.size)
    }

    private fun updateUI(magL: Double, magR: Double, rms: Double) {
        if (!isRunning) return

        val level = (rms / 32768.0 * 100 * 5).coerceIn(0.0, 100.0).toInt()
        binding.levelProgressBar.progress = level

        binding.magLeftText.text = String.format(Locale.US, "Mag: %.1f", magL)
        binding.magRightText.text = String.format(Locale.US, "Mag: %.1f", magR)

        val threshold = 60000.0 
        val leftDetected = magL > threshold
        val rightDetected = magR > threshold

        if (leftDetected != lastLeftDetected) {
            lastLeftDetected = leftDetected
            addLog(if (leftDetected) "Left (1kHz) RECOVERED" else "Left (1kHz) DROPPED")
        }
        if (rightDetected != lastRightDetected) {
            lastRightDetected = rightDetected
            addLog(if (rightDetected) "Right (2kHz) RECOVERED" else "Right (2kHz) DROPPED")
        }

        updateStatusIndicator(binding.statusLeft, leftDetected)
        updateStatusIndicator(binding.statusRight, rightDetected)
    }

    private fun updateStatusIndicator(view: android.widget.TextView, detected: Boolean) {
        if (detected) {
            view.text = "OK"
            view.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
        } else {
            view.text = "LOST"
            view.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
        }
    }

    private fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $message\n"
        binding.acousticLogText.append(entry)
        
        // Auto-scroll
        val scrollAmount = binding.acousticLogText.layout?.let { 
            it.lineCount * binding.acousticLogText.lineHeight - binding.acousticLogText.height 
        } ?: 0
        if (scrollAmount > 0) binding.acousticLogText.scrollTo(0, scrollAmount)
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
