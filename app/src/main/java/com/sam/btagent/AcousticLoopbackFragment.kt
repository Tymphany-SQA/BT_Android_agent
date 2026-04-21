package com.sam.btagent

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.sam.btagent.databinding.FragmentAcousticLoopbackBinding
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.cos

class AcousticLoopbackFragment : Fragment(), MainActivity.TestStatusProvider {

    private var _binding: FragmentAcousticLoopbackBinding? = null
    private val binding get() = _binding!!

    private val sampleRate = 44100
    private enum class TestMode { NORMAL, SWAP, LEFT_ONLY, RIGHT_ONLY, SWAP_LEFT_ONLY, SWAP_RIGHT_ONLY }
    @Volatile private var isRunning = false
    @Volatile private var currentMode = TestMode.NORMAL
    @Volatile private var isAutoDiagnosticRunning = false
    private var isAdvancedMetricsVisible = false

    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var analysisThread: Thread? = null
    private var lastLeftDetected = false
    private var lastRightDetected = false
    private val diagStats = EnumMap<TestMode, ModeStats>(TestMode::class.java)
    private var activeAutoMode: TestMode? = null
    private var activeAutoModeStartedAt = 0L
    private var manualStats = ModeStats()
    private var manualStatsMode = TestMode.NORMAL

    private data class ModeClassification(val result: String, val stability: String)

    private data class ModeStats(
        var samples: Int = 0,
        var leftDetectedSamples: Int = 0,
        var rightDetectedSamples: Int = 0,
        var lostTransitions: Int = 0,
        var leftMagSum: Double = 0.0,
        var rightMagSum: Double = 0.0,
        var lastLeftDetected: Boolean? = null,
        var lastRightDetected: Boolean? = null
    ) {
        fun leftUptime(): Double = if (samples == 0) 0.0 else leftDetectedSamples * 100.0 / samples
        fun rightUptime(): Double = if (samples == 0) 0.0 else rightDetectedSamples * 100.0 / samples
        fun leftAvgMagnitude(): Double = if (samples == 0) 0.0 else leftMagSum / samples
        fun rightAvgMagnitude(): Double = if (samples == 0) 0.0 else rightMagSum / samples
    }

    companion object {
        private const val AUTO_DIAG_WARMUP_MS = 500L
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    BluetoothHelper.addLog("Acoustic", "A2DP Disconnected - Stopping Test")
                    stopLoopback()
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAcousticLoopbackBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnToggleTone.setOnClickListener { if (isRunning) stopLoopback() else { isAutoDiagnosticRunning = false; startLoopback() } }
        binding.btnAutoDiagnostic.setOnClickListener { if (isAutoDiagnosticRunning) stopLoopback() else startAutoDiagnostic() }

        // 使用現有的 UI 元素模擬「Advanced Metrics」切換（若佈局無此開關則預設隱藏）
        binding.acousticLogText.setOnLongClickListener {
            isAdvancedMetricsVisible = !isAdvancedMetricsVisible
            addLog("Advanced Metrics: ${if(isAdvancedMetricsVisible) "ON" else "OFF"}")
            true
        }

        binding.toggleGroupMode.addOnButtonCheckedListener { _, id, isChecked ->
            if (isChecked && !isAutoDiagnosticRunning) {
                currentMode = when (id) {
                    R.id.btnModeSwap -> TestMode.SWAP
                    R.id.btnModeLeft -> TestMode.LEFT_ONLY
                    R.id.btnModeRight -> TestMode.RIGHT_ONLY
                    else -> TestMode.NORMAL
                }
                if (isRunning) {
                    if (manualStats.samples > 0) persistManualSummary()
                    resetManualStats(currentMode)
                }
            }
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ContextCompat.RECEIVER_EXPORTED else 0
        ContextCompat.registerReceiver(requireContext(), profileReceiver, IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED), flags)
    }

    private fun startAutoDiagnostic() {
        if (!checkRecordPermission()) return
        isAutoDiagnosticRunning = true
        diagStats.clear()
        activeAutoMode = null
        binding.autoDiagSummaryText.text = "Auto Diag Summary: running..."
        binding.btnAutoDiagnostic.text = "Stop Auto"
        binding.btnToggleTone.isEnabled = false
        Thread {
            try {
                // 擴充後的測試序列
                val sequence = listOf(
                    TestMode.LEFT_ONLY to 3, 
                    TestMode.RIGHT_ONLY to 3, 
                    TestMode.NORMAL to 5,
                    TestMode.SWAP_LEFT_ONLY to 5, 
                    TestMode.SWAP_RIGHT_ONLY to 5, 
                    TestMode.SWAP to 5
                )
                for ((mode, sec) in sequence) {
                    if (!isAutoDiagnosticRunning) break

                    // 立即更新模式，讓發聲執行緒 (startTone) 立刻改變頻率
                    currentMode = mode
                    activeAutoMode = mode
                    activeAutoModeStartedAt = System.currentTimeMillis()

                    activity?.runOnUiThread {
                        // 同步更新 UI 按鈕狀態
                        val btnId = when(mode) {
                            TestMode.LEFT_ONLY, TestMode.SWAP_LEFT_ONLY -> R.id.btnModeLeft
                            TestMode.RIGHT_ONLY, TestMode.SWAP_RIGHT_ONLY -> R.id.btnModeRight
                            TestMode.SWAP -> R.id.btnModeSwap
                            else -> R.id.btnModeNormal
                        }
                        binding.toggleGroupMode.check(btnId)
                        if (!isRunning) startLoopback()
                        addLog(">>> AUTO: $mode (${sec}s)")
                    }
                    Thread.sleep(sec * 1000L)
                }
            } catch (e: Exception) {} finally {
                activity?.runOnUiThread {
                    finishAutoDiagnosticSummary()
                    currentMode = TestMode.NORMAL
                    stopLoopback()
                }
            }
        }.start()
    }

    private fun startLoopback() {
        if (!checkRecordPermission()) return
        isRunning = true
        if (!isAutoDiagnosticRunning) resetManualStats(currentMode)
        binding.btnToggleTone.text = "Stop Test"
        addLog("Starting Stereo Test...")
        startTone()
        startAnalysis()
    }

    private fun stopLoopback() {
        val wasRunning = isRunning
        val wasAutoDiagnostic = isAutoDiagnosticRunning
        isRunning = false
        isAutoDiagnosticRunning = false
        activity?.runOnUiThread {
            binding.btnToggleTone.text = "Start Manual"
            binding.btnToggleTone.isEnabled = true
            binding.btnAutoDiagnostic.text = "Auto Diag"
            // 重置標題為預設值
            binding.labelLeftChannel.text = "LEFT Channel (1kHz):"
            binding.labelRightChannel.text = "RIGHT Channel (2kHz):"
            binding.statusLeft.text = "IDLE"; binding.statusLeft.setBackgroundColor(0xFFDDDDDD.toInt())
            binding.statusRight.text = "IDLE"; binding.statusRight.setBackgroundColor(0xFFDDDDDD.toInt())
        }
        activeAutoMode = null
        if (wasRunning && !wasAutoDiagnostic && manualStats.samples > 0) persistManualSummary()
        stopTone(); stopAnalysis()
    }

    private fun startTone() {
        val size = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (size <= 0) return
        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build()

            val frameCount = size / 4
            val samples = ShortArray(frameCount * 2)
            var pL = 0.0; var pR = 0.0
            audioTrack?.play()

            Thread {
                while (isRunning) {
                    val (fL, fR) = when (currentMode) {
                        TestMode.NORMAL -> 1000.0 to 2000.0
                        TestMode.SWAP -> 2000.0 to 1000.0
                        TestMode.LEFT_ONLY -> 1000.0 to 0.0
                        TestMode.RIGHT_ONLY -> 0.0 to 2000.0
                        TestMode.SWAP_LEFT_ONLY -> 2000.0 to 0.0
                        TestMode.SWAP_RIGHT_ONLY -> 0.0 to 1000.0
                    }
                    val iL = 2 * PI * fL / sampleRate; val iR = 2 * PI * fR / sampleRate
                    for (i in 0 until frameCount) {
                        // 修正直流偏置：如果頻率為 0，則輸出 0
                        samples[i * 2] = if (fL > 0) (sin(pL) * 16384).toInt().toShort() else 0
                        samples[i * 2 + 1] = if (fR > 0) (sin(pR) * 16384).toInt().toShort() else 0
                        pL += iL; pR += iR
                        if (pL > 2 * PI) pL -= 2 * PI
                        if (pR > 2 * PI) pR -= 2 * PI
                    }
                    audioTrack?.write(samples, 0, samples.size)
                }
            }.start()
        } catch (e: Exception) { addLog("Tone Error: ${e.message}") }
    }

    private fun stopTone() {
        runCatching { audioTrack?.stop(); audioTrack?.release() }
        audioTrack = null
    }

    private fun startAnalysis() {
        val size = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (size <= 0) return
        try {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            audioRecord?.startRecording()
            analysisThread = Thread {
                val buf = ShortArray(1024)
                while (isRunning) {
                    val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                    if (read > 0) {
                        val mL = goertzel(buf, 1000.0)
                        val mR = goertzel(buf, 2000.0)
                        val rms = calculateRMS(buf)
                        activity?.runOnUiThread { if (_binding != null) updateUI(mL, mR, rms) }
                    }
                }
            }.apply { start() }
        } catch (e: Exception) { addLog("Analysis Error: ${e.message}") }
    }

    private fun stopAnalysis() {
        runCatching { audioRecord?.stop(); audioRecord?.release() }
        audioRecord = null; analysisThread = null
    }

    // 優化後的 Goertzel：加入漢寧窗減少洩漏
    private fun goertzel(samples: ShortArray, target: Double): Double {
        val n = samples.size
        val k = (n * target / sampleRate).toInt()
        val omega = 2.0 * PI * k / n
        val coeff = 2.0 * cos(omega)
        var q1 = 0.0; var q2 = 0.0
        for (i in samples.indices) {
            // Hann window: 0.5 * (1 - cos(2*PI*i/(N-1)))
            val win = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
            val q0 = coeff * q1 - q2 + (samples[i] * win)
            q2 = q1; q1 = q0
        }
        return sqrt(q1 * q1 + q2 * q2 - coeff * q1 * q2)
    }

    private fun calculateRMS(s: ShortArray): Double {
        var sum = 0.0; for (v in s) sum += v * v
        return sqrt(sum / s.size)
    }

    private var cachedCodecInfo = "Scanning..."
    private var lastCodecUpdateTime = 0L

    private fun updateCodecInfo() {
        val now = System.currentTimeMillis()
        if (now - lastCodecUpdateTime < 2000) return
        lastCodecUpdateTime = now

        val adapter = (context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                val device = proxy.connectedDevices.firstOrNull()
                cachedCodecInfo = BluetoothHelper.getCodecInfo(proxy, device)
                adapter.closeProfileProxy(id, proxy)
            }
            override fun onServiceDisconnected(id: Int) {}
        }, BluetoothProfile.A2DP)
    }

    private fun updateUI(mL: Double, mR: Double, rms: Double) {
        if (_binding == null) return
        binding.levelProgressBar.progress = (rms / 32768.0 * 500).toInt().coerceIn(0, 100)

        if (isAdvancedMetricsVisible) updateCodecInfo()
        
        // 頻率互換判定：只要是 Swap 系列，監控 Slot 就對調
        val isFreqSwapped = currentMode == TestMode.SWAP || 
                            currentMode == TestMode.SWAP_LEFT_ONLY || 
                            currentMode == TestMode.SWAP_RIGHT_ONLY
        
        val leftMag = if (isFreqSwapped) mR else mL
        val rightMag = if (isFreqSwapped) mL else mR
        
        val leftFreqStr = if (isFreqSwapped) "2kHz" else "1kHz"
        val rightFreqStr = if (isFreqSwapped) "1kHz" else "2kHz"

        // 更新大標題 (LEFT/RIGHT Channel 1k/2k)
        binding.labelLeftChannel.text = String.format(Locale.US, "LEFT Channel (%s):", leftFreqStr)
        binding.labelRightChannel.text = String.format(Locale.US, "RIGHT Channel (%s):", rightFreqStr)

        val codecSuffix = if (isAdvancedMetricsVisible) "|$cachedCodecInfo" else ""

        binding.magLeftText.textSize = 12f // 進一步縮小字體確保不裁切
        binding.magRightText.textSize = 12f
        
        binding.magLeftText.text = String.format(Locale.US, "L(%s):%.0f%s", leftFreqStr, leftMag, codecSuffix)
        binding.magRightText.text = String.format(Locale.US, "R(%s):%.0f", rightFreqStr, rightMag)

        val thr = 35000.0
        val detL = leftMag > thr; val detR = rightMag > thr
        recordAutoDiagSample(currentMode, detL, detR, leftMag, rightMag)
        recordManualSample(currentMode, detL, detR, leftMag, rightMag)
        
        if (detL != lastLeftDetected) { 
            lastLeftDetected = detL
            addLog(if (detL) "Left ($leftFreqStr) DETECTED" else "Left ($leftFreqStr) LOST") 
        }
        if (detR != lastRightDetected) { 
            lastRightDetected = detR
            addLog(if (detR) "Right ($rightFreqStr) DETECTED" else "Right ($rightFreqStr) LOST")
        }
        
        updateStatus(binding.statusLeft, detL, isFreqSwapped)
        updateStatus(binding.statusRight, detR, isFreqSwapped)
    }

    private fun getA2dpCodec(): String = cachedCodecInfo

    private fun updateStatus(v: android.widget.TextView, det: Boolean, isSwapped: Boolean) {
        val ctx = context ?: return
        if (det) {
            v.text = if (isSwapped) "SWAP OK" else "OK"
            v.setBackgroundColor(ContextCompat.getColor(ctx, if (isSwapped) android.R.color.holo_blue_dark else android.R.color.holo_green_dark))
        } else {
            v.text = "LOST"
            v.setBackgroundColor(ContextCompat.getColor(ctx, android.R.color.holo_red_dark))
        }
    }

    private fun recordAutoDiagSample(mode: TestMode, detL: Boolean, detR: Boolean, leftMag: Double, rightMag: Double) {
        if (!isAutoDiagnosticRunning || activeAutoMode != mode) return
        if (System.currentTimeMillis() - activeAutoModeStartedAt < AUTO_DIAG_WARMUP_MS) return
        val stats = diagStats.getOrPut(mode) { ModeStats() }
        updateStats(stats, detL, detR, leftMag, rightMag)
    }

    private fun recordManualSample(mode: TestMode, detL: Boolean, detR: Boolean, leftMag: Double, rightMag: Double) {
        if (isAutoDiagnosticRunning || !isRunning || manualStatsMode != mode) return
        updateStats(manualStats, detL, detR, leftMag, rightMag)
        binding.autoDiagSummaryText.text = manualSummaryText(mode, manualStats)
    }

    private fun updateStats(stats: ModeStats, detL: Boolean, detR: Boolean, leftMag: Double, rightMag: Double) {
        if (stats.lastLeftDetected == true && !detL) stats.lostTransitions += 1
        if (stats.lastRightDetected == true && !detR) stats.lostTransitions += 1
        stats.samples += 1
        if (detL) stats.leftDetectedSamples += 1
        if (detR) stats.rightDetectedSamples += 1
        stats.leftMagSum += leftMag
        stats.rightMagSum += rightMag
        stats.lastLeftDetected = detL
        stats.lastRightDetected = detR
    }

    private fun resetManualStats(mode: TestMode) {
        manualStatsMode = mode
        manualStats = ModeStats()
        binding.autoDiagSummaryText.text = "Manual Stats: ${mode.name} running..."
        addLog("Manual Stats reset: ${mode.name}")
    }

    private fun manualSummaryText(mode: TestMode, stats: ModeStats): String {
        return "Manual Stats: ${mode.name} L=${String.format(Locale.US, "%.0f", stats.leftUptime())}% R=${String.format(Locale.US, "%.0f", stats.rightUptime())}% lost=${stats.lostTransitions} avgL=${String.format(Locale.US, "%.0f", stats.leftAvgMagnitude())} avgR=${String.format(Locale.US, "%.0f", stats.rightAvgMagnitude())}"
    }

    private fun persistManualSummary() {
        val classification = classifyMode(manualStatsMode, manualStats)
        LogPersistenceManager.persistAcousticDiagResult(
            requireContext(),
            "MANUAL_${manualStatsMode.name}",
            classification.result,
            classification.stability,
            manualStats.leftUptime(),
            manualStats.rightUptime(),
            manualStats.lostTransitions,
            manualStats.leftAvgMagnitude(),
            manualStats.rightAvgMagnitude()
        )
        LogPersistenceManager.persistTestSummary(
            requireContext(),
            "AcousticManual",
            manualStatsMode.name,
            classification.result,
            "stability=${classification.stability}; L=${String.format(Locale.US, "%.0f", manualStats.leftUptime())}%; R=${String.format(Locale.US, "%.0f", manualStats.rightUptime())}%; lost=${manualStats.lostTransitions}"
        )
        addLog("${manualSummaryText(manualStatsMode, manualStats)} result=${classification.result} stability=${classification.stability}")
        manualStats = ModeStats()
    }

    private fun finishAutoDiagnosticSummary() {
        if (diagStats.isEmpty()) {
            binding.autoDiagSummaryText.text = "Auto Diag Summary: no samples"
            return
        }
        val orderedModes = listOf(
            TestMode.LEFT_ONLY,
            TestMode.RIGHT_ONLY,
            TestMode.NORMAL,
            TestMode.SWAP_LEFT_ONLY,
            TestMode.SWAP_RIGHT_ONLY,
            TestMode.SWAP
        )
        val summaryLines = orderedModes.mapNotNull { mode ->
            val stats = diagStats[mode] ?: return@mapNotNull null
            val classification = classifyMode(mode, stats)
            LogPersistenceManager.persistAcousticDiagResult(
                requireContext(),
                mode.name,
                classification.result,
                classification.stability,
                stats.leftUptime(),
                stats.rightUptime(),
                stats.lostTransitions,
                stats.leftAvgMagnitude(),
                stats.rightAvgMagnitude()
            )
            "${mode.name}: result=${classification.result} stability=${classification.stability} L=${String.format(Locale.US, "%.0f", stats.leftUptime())}% R=${String.format(Locale.US, "%.0f", stats.rightUptime())}% lost=${stats.lostTransitions}"
        }
        val overallPass = summaryLines.all { it.contains("result=PASS") }
        val overallStability = if (summaryLines.any { it.contains("stability=UNSTABLE") }) "UNSTABLE" else "STABLE"
        val summary = "Auto Diag Summary: result=${if (overallPass) "PASS" else "FAIL"} stability=$overallStability\n${summaryLines.joinToString("\n")}"
        binding.autoDiagSummaryText.text = summary
        LogPersistenceManager.persistTestSummary(
            requireContext(),
            "AcousticAutoDiag",
            "Overall",
            if (overallPass) "PASS" else "FAIL",
            "stability=$overallStability; warmupDiscardMs=$AUTO_DIAG_WARMUP_MS; ${summaryLines.joinToString(" | ")}"
        )
        addLog(summary.replace("\n", " | "))
    }

    private fun classifyMode(mode: TestMode, stats: ModeStats): ModeClassification {
        val left = stats.leftUptime()
        val right = stats.rightUptime()
        val minDual = minOf(left, right)
        val passThreshold = 80.0
        val quietThreshold = 20.0
        val result = when (mode) {
            TestMode.NORMAL, TestMode.SWAP -> if (minDual >= passThreshold) "PASS" else "FAIL"
            TestMode.LEFT_ONLY, TestMode.SWAP_LEFT_ONLY -> when {
                left >= passThreshold && right <= quietThreshold -> "PASS"
                left >= passThreshold && right > quietThreshold -> "FAIL_CROSSTALK"
                else -> "FAIL_LEFT_LOST"
            }
            TestMode.RIGHT_ONLY, TestMode.SWAP_RIGHT_ONLY -> when {
                right >= passThreshold && left <= quietThreshold -> "PASS"
                right >= passThreshold && left > quietThreshold -> "FAIL_CROSSTALK"
                else -> "FAIL_RIGHT_LOST"
            }
        }
        val stability = if (stats.lostTransitions > 0) "UNSTABLE" else "STABLE"
        return ModeClassification(result, stability)
    }

    private fun addLog(m: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $m\n"
        BluetoothHelper.addLog("Acoustic", m)
        activity?.runOnUiThread {
            _binding?.acousticLogText?.append(entry)
            _binding?.acousticLogText?.let { t -> t.layout?.let { l ->
                val s = l.lineCount * t.lineHeight - t.height
                if (s > 0) t.scrollTo(0, s)
            }}
        }
    }

    private fun checkRecordPermission() = BluetoothHelper.hasPermission(requireContext(), Manifest.permission.RECORD_AUDIO)

    override fun isTestRunning() = isRunning
    override fun stopTest() = stopLoopback()
    override fun onDestroyView() {
        runCatching { requireContext().unregisterReceiver(profileReceiver) }
        stopLoopback()
        super.onDestroyView()
        _binding = null
    }
}
