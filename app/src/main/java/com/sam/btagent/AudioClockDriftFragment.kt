package com.sam.btagent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.sam.btagent.databinding.FragmentAudioClockDriftBinding
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioClockDriftFragment : Fragment(), MainActivity.TestStatusProvider {

    private var _binding: FragmentAudioClockDriftBinding? = null
    private val binding get() = _binding!!

    private var isTesting = false
    private var playThread: Thread? = null
    private var recordThread: Thread? = null

    private val SAMPLE_RATE_48K = 48000
    private val NOMINAL_FREQ = 1000.0

    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    
    private val driftHistory = mutableListOf<Double>()
    private val MAX_HISTORY = 10

    private var accumulatedOffsetMs = 0.0
    private var lastAcousticTime = 0L
    private var startTimeMillis = 0L

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startTest()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAudioClockDriftBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nativeRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "48000"
        log("Native Rate: $nativeRate Hz. Using Acoustic loopback for stability.")

        binding.btnStartDriftTest.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                startTest()
            }
        }
        binding.btnStopDriftTest.setOnClickListener { stopTest() }
    }

    private fun startTest() {
        isTesting = true
        startTimeMillis = System.currentTimeMillis()
        lastAcousticTime = startTimeMillis
        accumulatedOffsetMs = 0.0
        driftHistory.clear()
        
        binding.btnStartDriftTest.visibility = View.GONE
        binding.btnStopDriftTest.visibility = View.VISIBLE
        binding.toggleBufferLength.isEnabled = false
        
        startAcousticMethod()
    }

    private fun startAcousticMethod() {
        // 根據 Toggle Group 選擇決定 Buffer 大小 (48kHz)
        val analysisBufferSize = when(binding.toggleBufferLength.checkedButtonId) {
            R.id.btnBuf01 -> 4800   // 0.1s
            R.id.btnBuf05 -> 24000  // 0.5s
            R.id.btnBuf10 -> 48000  // 1.0s
            R.id.btnBuf20 -> 96000  // 2.0s
            else -> 48000           // Default 1.0s
        }
        
        log("Acoustic Monitor (Window: ${analysisBufferSize/480.0}ms + Hanning)...")
        val testRate = SAMPLE_RATE_48K
        val recordBufSize = AudioRecord.getMinBufferSize(testRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(analysisBufferSize * 2)
        
        try {
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, testRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recordBufSize)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(testRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(testRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(9600))
                .build()
            
            audioRecord?.startRecording()
            audioTrack?.play()

            // 執行緒 1: 專責連續播放 1kHz 訊號，確保不中斷
            playThread = Thread {
                val playBuf = ShortArray(2400) // 50ms chunks
                for (i in playBuf.indices) playBuf[i] = (sin(2.0 * PI * NOMINAL_FREQ * i / testRate) * 20000).toInt().toShort()
                try {
                    while (isTesting && !Thread.interrupted()) {
                        audioTrack?.write(playBuf, 0, playBuf.size)
                    }
                } catch (e: Exception) {}
            }.apply { start() }

            // 執行緒 2: 專責錄音與 PPM 分析
            recordThread = Thread {
                val recordBuf = ShortArray(analysisBufferSize)
                val hanning = DoubleArray(analysisBufferSize) { i ->
                    0.5 * (1.0 - Math.cos(2.0 * PI * i / (analysisBufferSize - 1)))
                }

                while (isTesting && !Thread.interrupted()) {
                    val read = audioRecord?.read(recordBuf, 0, recordBuf.size) ?: 0
                    if (read >= analysisBufferSize) {
                        val now = System.currentTimeMillis()
                        
                        if (now - startTimeMillis < 3000) {
                            lastAcousticTime = now
                            activity?.runOnUiThread { if (_binding != null) binding.tvSecondaryInfo.text = "Warming up..." }
                            continue
                        }

                        // Calculate SNR (1kHz vs others)
                        val snr = calculateSNR(recordBuf, analysisBufferSize)
                        if (snr < 10.0) {
                            activity?.runOnUiThread {
                                if (_binding != null) {
                                    binding.tvSecondaryInfo.text = "WARNING: Low SNR (%.1f dB)\nMove closer or reduce noise.".format(snr)
                                }
                            }
                            // We still continue but UI warns the user
                        }

                        val deltaTimeS = (now - lastAcousticTime) / 1000.0
                        lastAcousticTime = now

                        val windowedData = DoubleArray(read) { i -> recordBuf[i] * hanning[i] }
                        val detectedFreq = findPeakFrequencyDouble(windowedData, read)
                        val driftPpm = ((detectedFreq - NOMINAL_FREQ) / NOMINAL_FREQ) * 1_000_000.0
                        
                        val freqDiff = detectedFreq - NOMINAL_FREQ
                        val offsetGainMs = (freqDiff / NOMINAL_FREQ) * deltaTimeS * 1000.0
                        accumulatedOffsetMs += offsetGainMs

                        activity?.runOnUiThread { updateUI(driftPpm, detectedFreq, "Hz (Acoustic)") }
                        LogPersistenceManager.persistDriftLog(requireContext(), "Acoustic", driftPpm, detectedFreq)
                    }
                }
            }.apply { start() }

        } catch (e: Exception) { 
            log("Acoustic Error: ${e.message}")
            stopTest() 
        }
    }

    private fun findPeakFrequencyDouble(data: DoubleArray, length: Int): Double {
        var maxMag = -1.0; var bestFreq = NOMINAL_FREQ
        // 在 1kHz 附近以 0.01Hz 為步進搜尋
        for (f in 99500..100500) {
            val freq = f / 100.0
            val mag = goertzelDouble(data, length, freq)
            if (mag > maxMag) { maxMag = mag; bestFreq = freq }
        }
        return bestFreq
    }

    private fun goertzelDouble(data: DoubleArray, length: Int, targetFreq: Double): Double {
        val k = targetFreq * length / SAMPLE_RATE_48K
        val omega = 2.0 * PI * k / length
        val cosine = Math.cos(omega); val coeff = 2.0 * cosine
        var q0: Double; var q1 = 0.0; var q2 = 0.0
        for (i in 0 until length) {
            q0 = coeff * q1 - q2 + data[i]
            q2 = q1; q1 = q0
        }
        return q1 * q1 + q2 * q2 - coeff * q1 * q2
    }

    private fun calculateSNR(buffer: ShortArray, length: Int): Double {
        val signalMag = goertzelDouble(DoubleArray(length) { buffer[it].toDouble() }, length, NOMINAL_FREQ)
        // Estimate noise from 800Hz and 1200Hz
        val noise1 = goertzelDouble(DoubleArray(length) { buffer[it].toDouble() }, length, 800.0)
        val noise2 = goertzelDouble(DoubleArray(length) { buffer[it].toDouble() }, length, 1200.0)
        val avgNoise = (noise1 + noise2) / 2.0
        if (avgNoise <= 0) return 100.0
        return 10.0 * Math.log10(signalMag / avgNoise)
    }

    private fun updateUI(ppm: Double, rate: Double, unitLabel: String) {
        if (_binding == null) return
        val absPpm = abs(ppm)
        
        binding.tvMeasuredValue.text = String.format(Locale.US, "%.0f", ppm)
        val color = when {
            absPpm < 100 -> "#2E7D32" 
            absPpm < 500 -> "#F57C00" 
            else -> "#C62828" 
        }
        binding.tvMeasuredValue.setTextColor(android.graphics.Color.parseColor(color))
        
        // 顯示累積位移、百分比與即時資訊
        val totalElapsedMs = System.currentTimeMillis() - startTimeMillis
        val durationStr = formatDuration(totalElapsedMs)
        val avgDriftPercent = if (totalElapsedMs > 0) (accumulatedOffsetMs / totalElapsedMs) * 100.0 else 0.0
        val avgPpm = avgDriftPercent * 10000.0

        binding.tvSecondaryInfo.text = String.format(Locale.US, 
            "Duration: %s\nHW: %.2f %s\nAcc: %.1f ms (%.4f%% / %.0f PPM)", 
            durationStr, rate, unitLabel, accumulatedOffsetMs, avgDriftPercent, avgPpm)

        driftHistory.add(ppm)
        if (driftHistory.size > MAX_HISTORY) driftHistory.removeAt(0)
        binding.tvDriftTrend.text = getString(R.string.drift_trend_format, driftHistory.size, driftHistory.joinToString(", ") { String.format(Locale.US, "%.0f", it) })
    }

    override fun isTestRunning() = isTesting
    override fun stopTest() {
        isTesting = false
        playThread?.interrupt(); playThread = null
        recordThread?.interrupt(); recordThread = null

        try { audioTrack?.release(); audioRecord?.release() } catch (e: Exception) {}
        audioTrack = null; audioRecord = null
        activity?.runOnUiThread {
            if (_binding != null) {
                binding.btnStartDriftTest.visibility = View.VISIBLE
                binding.btnStopDriftTest.visibility = View.GONE
                binding.toggleBufferLength.isEnabled = true
            }
        }
    }

    override fun onDestroyView() { stopTest(); super.onDestroyView(); _binding = null }
    
    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = (ms / (1000 * 60 * 60))
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        activity?.runOnUiThread { if (_binding != null) binding.driftLogText.append("[$ts] $msg\n") }
    }
}
