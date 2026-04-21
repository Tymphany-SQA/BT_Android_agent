package com.sam.btagent

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.sam.btagent.databinding.FragmentStressTestBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class StressTestFragment : Fragment(), MainActivity.TestStatusProvider {

    private var _binding: FragmentStressTestBinding? = null
    private val binding get() = _binding!!

    private var targetDeviceAddress: String? = null
    private var targetDeviceName: String? = null
    
    private var isTesting = false
    private var testThread: Thread? = null
    private var activeAudioTrack: AudioTrack? = null
    private var lastUnderrunCount = 0

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    if (isTesting) {
                        log("CRITICAL: Bluetooth was turned OFF. Aborting test.")
                        stopStressTest()
                        activity?.runOnUiThread {
                            Toast.makeText(context, "Test aborted: Bluetooth OFF", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    // KPI Tracking
    private val connectionTimes = mutableListOf<Long>()
    private val recoveryTimes = mutableListOf<Long>()
    private var connectionAttempts = 0
    private var connectionSuccesses = 0
    private var recoverySuccesses = 0

    private enum class AudioType(val label: String) {
        SOFT_PIANO("Soft Piano (Gentle)"),
        ZEN_BELLS("Zen Bells (Quiet)"),
        BEEP_TONE("Standard Beep (440Hz)")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStressTestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        targetDeviceAddress = arguments?.getString("DEVICE_ADDRESS")
        targetDeviceName = arguments?.getString("DEVICE_NAME") ?: "Unknown Device"

        if (targetDeviceAddress == null) {
            val context = requireContext()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                if (adapter != null) {
                    val bonded = adapter.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }
                    if (bonded.isNotEmpty()) {
                        val defaultDevice = bonded.first()
                        targetDeviceAddress = defaultDevice.address
                        targetDeviceName = defaultDevice.name ?: "Unknown Device"
                    }
                }
            }
        }

        binding.targetDeviceName.text = getString(R.string.target_device_label, targetDeviceName, targetDeviceAddress ?: "None")
        binding.testLogText.movementMethod = ScrollingMovementMethod()

        // Setup Audio Selector
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, AudioType.values().map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.audioSelector.adapter = adapter
        binding.audioSelector.setSelection(0) // Default to Soft Piano

        binding.startTestButton.setOnClickListener { startStressTest() }
        binding.stopTestButton.setOnClickListener { stopStressTest() }
        
        binding.copyLogButton.setOnClickListener { copyLogToClipboard() }
        binding.clearLogButton.setOnClickListener { clearLog() }

        // Register Bluetooth observer
        requireContext().registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        resetKpiUI()
    }

    private fun startStressTest() {
        val address = targetDeviceAddress
        if (address == null) {
            log("Error: No device selected")
            return
        }

        checkDeviceConnection(address) { isConnected ->
            if (!isConnected) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Warning: Device is not connected", Toast.LENGTH_LONG).show()
                }
                log("Warning: Target device is not connected via A2DP/HFP")
            }
            runActualTest()
        }
    }

    private fun checkDeviceConnection(address: String, callback: (Boolean) -> Unit) {
        val context = context ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            callback(false)
            return
        }

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            callback(false)
            return
        }

        var isConnected = false
        var checkedCount = 0
        val profiles = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

        profiles.forEach { profileId ->
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                    if (proxy.connectedDevices.any { it.address == address }) {
                        isConnected = true
                    }
                    adapter.closeProfileProxy(id, proxy)
                    checkedCount++
                    if (checkedCount == profiles.size) {
                        callback(isConnected)
                    }
                }
                override fun onServiceDisconnected(id: Int) {
                    checkedCount++
                    if (checkedCount == profiles.size) {
                        callback(isConnected)
                    }
                }
            }, profileId)
        }
    }

    private fun runActualTest() {
        val playSec = binding.playDurationInput.text.toString().toIntOrNull() ?: 10
        val pauseSec = binding.pauseDurationInput.text.toString().toIntOrNull() ?: 5
        val repeats = binding.repeatCountInput.text.toString().toIntOrNull() ?: 3
        val selectedAudio = AudioType.values()[binding.audioSelector.selectedItemPosition]
        val address = targetDeviceAddress ?: ""

        isTesting = true
        connectionTimes.clear()
        recoveryTimes.clear()
        connectionAttempts = 0
        connectionSuccesses = 0
        recoverySuccesses = 0
        resetKpiUI()

        activity?.runOnUiThread {
            binding.startTestButton.isEnabled = false
            binding.stopTestButton.isEnabled = true
            binding.playDurationInput.isEnabled = false
            binding.pauseDurationInput.isEnabled = false
            binding.repeatCountInput.isEnabled = false
            binding.audioSelector.isEnabled = false
            binding.swStopOnError.isEnabled = false
            
            binding.testProgressBar.max = repeats
            binding.testProgressBar.progress = 0
            binding.loopProgressText.text = getString(R.string.loop_progress_label, 0, repeats)
            
            // Item 2: Keep screen on during test
            binding.root.keepScreenOn = true
        }

        // Item 1: Start Foreground Service
        val serviceIntent = Intent(requireContext(), StressTestService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            requireContext().startForegroundService(serviceIntent)
        } else {
            requireContext().startService(serviceIntent)
        }

        log("Starting stress test: $repeats loops")
        log("Settings: Play ${playSec}s, Intervals ${pauseSec}s, Audio: ${selectedAudio.label}")

        testThread = Thread {
            try {
                for (i in 1..repeats) {
                    if (!isTesting) break
                    
                    activity?.runOnUiThread {
                        binding.loopProgressText.text = getString(R.string.loop_progress_label, i, repeats)
                        binding.testProgressBar.progress = i
                    }

                    runLoopStep(i, playSec, pauseSec, selectedAudio)
                }
                log("Stress test completed successfully.")
                activity?.runOnUiThread { logFinalKpi() }
            } catch (e: InterruptedException) {
                log("Test manually stopped.")
            } catch (e: Exception) {
                log("Error during test: ${e.message}")
            } finally {
                activity?.runOnUiThread {
                    isTesting = false
                    binding.startTestButton.isEnabled = true
                    binding.stopTestButton.isEnabled = false
                    binding.playDurationInput.isEnabled = true
                    binding.pauseDurationInput.isEnabled = true
                    binding.repeatCountInput.isEnabled = true
                    binding.audioSelector.isEnabled = true
                    binding.swStopOnError.isEnabled = true
                    binding.testStatusText.text = getString(R.string.test_status_label, getString(R.string.test_status_idle))
                    binding.root.keepScreenOn = false
                }
                // Stop service when test ends
                requireContext().stopService(serviceIntent)
            }
        }
        testThread?.start()
    }

    private fun runLoopStep(loopIndex: Int, playSec: Int, pauseSec: Int, audioType: AudioType) {
        log("--- [START LOOP #$loopIndex] ---")
        
        // 1. Play music
        if (!isTesting) return
        updateStatus("Playing ${audioType.label} ($playSec s)")
        playGeneratedAudio(playSec, audioType)
        
        // 2. Pause
        if (!isTesting) return
        updateStatus("Pause after play ($pauseSec s)")
        interruptibleSleep(pauseSec)
        
        // 3. Disconnect
        if (!isTesting) return
        updateStatus("Action: Disconnect")
        performConnectionAction(connect = false)
        interruptibleSleep(pauseSec)
        
        // 4. Pause
        if (!isTesting) return
        updateStatus("Pause after disconnect ($pauseSec s)")
        interruptibleSleep(pauseSec)
        
        // 5. Connect (With KPI Tracking)
        if (!isTesting) return
        val connectStartTime = System.currentTimeMillis()
        connectionAttempts++
        
        var connectedResult: Boolean? = null
        performConnectionAction(connect = true) { success ->
            connectedResult = success
        }
        
        // Wait for connection to finish or timeout (max 15s)
        val waitStart = System.currentTimeMillis()
        while (connectedResult == null && System.currentTimeMillis() - waitStart < 16000) {
            if (!isTesting) break
            val elapsed = (System.currentTimeMillis() - waitStart) / 1000
            activity?.runOnUiThread {
                binding.testStatusText.text = "Connecting... (${elapsed}s/15s)"
            }
            Thread.sleep(200)
        }

        if (connectedResult == true) {
            val duration = System.currentTimeMillis() - connectStartTime
            connectionTimes.add(duration)
            connectionSuccesses++
            val recoveryDuration = measureConnectionRecoveryTime(connectStartTime)
            if (recoveryDuration != null) {
                recoveryTimes.add(recoveryDuration)
                recoverySuccesses++
                log("Recovery KPI: A2DP + acoustic output in ${recoveryDuration} ms")
            }
            activity?.runOnUiThread { 
                updateKpiUI()
                val recoveryText = recoveryDuration?.let { ", recovery ${it}ms" } ?: ""
                updateStatus("Connected in ${duration}ms$recoveryText")
            }
            log("Connection KPI: $duration ms")
            // Item 3: Persist structured KPI data
            LogPersistenceManager.persistStressKPI(requireContext(), loopIndex, "CONNECT", true, duration, recoveryMs = recoveryDuration)
        } else {
            val reason = if (connectedResult == false) "Profile Mismatch/Fail" else "Global Timeout"
            log("Connection Error: $reason")
            LogPersistenceManager.persistStressKPI(requireContext(), loopIndex, "CONNECT", false, 0)
            handleTestError(reason)
        }
        
        interruptibleSleep(pauseSec)
        
        // 6. Play music again
        if (!isTesting) return
        updateStatus("Playing Audio Again ($playSec s)")
        playGeneratedAudio(playSec, audioType)
        
        // 7. Final pause
        if (!isTesting) return
        updateStatus("Wait before next loop ($pauseSec s)")
        interruptibleSleep(pauseSec)
        
        log("--- [END LOOP #$loopIndex] ---")
    }

    private fun handleTestError(reason: String) {
        val stopOnError = binding.swStopOnError.isChecked
        if (stopOnError) {
            log("STOP ON ERROR: $reason triggered. Saving snapshot...")
            activity?.let { LogPersistenceManager.saveErrorSnapshot(it.applicationContext, reason) }
            isTesting = false
            activity?.runOnUiThread {
                Toast.makeText(context, "Test stopped due to error: $reason", Toast.LENGTH_LONG).show()
            }
        } else {
            // Even if we don't stop, still save a snapshot if it's a major error
            activity?.let { LogPersistenceManager.saveErrorSnapshot(it.applicationContext, "SoftError: $reason") }
        }
    }

    private fun updateStatus(status: String) {
        activity?.runOnUiThread {
            binding.testStatusText.text = getString(R.string.test_status_label, status)
            log(status)
        }
    }

    private fun log(message: String) {
        val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        activity?.let { act ->
            act.runOnUiThread {
                binding.testLogText.append("[$timeStamp] $message\n")
                val scrollAmount = binding.testLogText.layout?.let { 
                    it.getLineTop(binding.testLogText.lineCount) - binding.testLogText.height 
                } ?: 0
                if (scrollAmount > 0) {
                    binding.testLogText.scrollTo(0, scrollAmount)
                }
            }
            LogPersistenceManager.persistLog(act.applicationContext, "StressTest", message)
        }
    }

    private fun interruptibleSleep(seconds: Int) {
        val ms = seconds * 1000L
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < ms) {
            if (!isTesting) throw InterruptedException()
            Thread.sleep(200)
        }
    }

    private fun performConnectionAction(connect: Boolean, onComplete: ((Boolean) -> Unit)? = null) {
        val address = targetDeviceAddress ?: return
        val context = context ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            log("Error: Missing BLUETOOTH_CONNECT permission")
            onComplete?.invoke(false)
            return
        }

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = adapter.getRemoteDevice(address)

        // 1. 偵測裝置支援哪些 Profile
        val supportedProfiles = mutableListOf(BluetoothProfile.A2DP)
        try {
            val uuids = device.uuids
            val hasHfp = uuids?.any { it.uuid.toString().uppercase().contains("111E") || it.uuid.toString().uppercase().contains("111F") } ?: true
            if (hasHfp) {
                supportedProfiles.add(BluetoothProfile.HEADSET)
            }
        } catch (e: SecurityException) {
            log("SecurityException reading UUIDs: ${e.message}")
        }

        log("Target profiles: ${supportedProfiles.map { if(it == BluetoothProfile.A2DP) "A2DP" else "HFP" }}")

        val connectionResults = mutableMapOf<Int, Boolean>()
        var profilesProcessed = 0

        supportedProfiles.forEach { profileId ->
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                    try {
                        val methodName = if (connect) "connect" else "disconnect"
                        val method = proxy.javaClass.getMethod(methodName, BluetoothDevice::class.java)
                        method.isAccessible = true
                        val callSuccess = method.invoke(proxy, device) as? Boolean ?: false
                        
                        if (connect) {
                            // 2. 進入輪詢檢查連線狀態
                            Thread {
                                var success = false
                                val pollStart = System.currentTimeMillis()
                                while (System.currentTimeMillis() - pollStart < 15000 && isTesting) {
                                    val state = proxy.getConnectionState(device)
                                    if (state == BluetoothProfile.STATE_CONNECTED) {
                                        success = true
                                        break
                                    }
                                    Thread.sleep(200)
                                }
                                
                                val lastState = proxy.getConnectionState(device)
                                if (!success) {
                                    log("${profileName(id)} failed to connect. Final state: ${stateName(lastState)}")
                                }

                                synchronized(connectionResults) {
                                    connectionResults[id] = success
                                    profilesProcessed++
                                    if (profilesProcessed == supportedProfiles.size) {
                                        val overallSuccess = supportedProfiles.all { connectionResults[it] == true }
                                        onComplete?.invoke(overallSuccess)
                                    }
                                }
                                adapter.closeProfileProxy(id, proxy)
                            }.start()
                        } else {
                            // 斷開連線邏輯
                            log("${profileName(id)} Disconnect call: $callSuccess")
                            synchronized(connectionResults) {
                                profilesProcessed++
                                if (profilesProcessed == supportedProfiles.size) {
                                    onComplete?.invoke(true)
                                }
                            }
                            adapter.closeProfileProxy(id, proxy)
                        }
                    } catch (e: Exception) {
                        log("${profileName(id)} action failed: ${e.message}")
                        synchronized(connectionResults) {
                            profilesProcessed++
                            if (profilesProcessed == supportedProfiles.size) {
                                onComplete?.invoke(false)
                            }
                        }
                        adapter.closeProfileProxy(id, proxy)
                    }
                }
                override fun onServiceDisconnected(id: Int) {}
            }, profileId)
        }
    }

    private fun stateName(state: Int) = when(state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "UNKNOWN($state)"
    }

    private fun profileName(id: Int) = when(id) {
        BluetoothProfile.A2DP -> "A2DP"
        BluetoothProfile.HEADSET -> "HFP"
        else -> "Profile $id"
    }

    private fun resetKpiUI() {
        activity?.runOnUiThread {
            binding.tvKpiAvg.text = getString(R.string.kpi_avg_none)
            binding.tvKpiSuccessRate.text = getString(R.string.kpi_success_rate_none)
            binding.tvKpiMinMax.text = getString(R.string.kpi_min_max_none)
            binding.tvKpiP90.text = getString(R.string.kpi_p90_none)
            binding.tvKpiP95.text = "P95: - ms"
            binding.tvKpiRecovery.text = "Recovery: - ms"
        }
    }

    private fun updateKpiUI() {
        if (connectionTimes.isEmpty()) return
        
        val avg = connectionTimes.average().toLong()
        val min = connectionTimes.minOrNull() ?: 0
        val max = connectionTimes.maxOrNull() ?: 0
        
        val sorted = connectionTimes.sorted()
        val p90 = percentile(sorted, 0.90)
        val p95 = percentile(sorted, 0.95)
        val recoveryAvg = if (recoveryTimes.isNotEmpty()) recoveryTimes.average().toLong() else null

        binding.tvKpiAvg.text = getString(R.string.kpi_avg_format, avg)
        binding.tvKpiSuccessRate.text = getString(R.string.kpi_success_rate_format, connectionSuccesses, connectionAttempts)
        binding.tvKpiMinMax.text = getString(R.string.kpi_min_max_format, min.toInt(), max.toInt())
        binding.tvKpiP90.text = getString(R.string.kpi_p90_format, p90.toInt())
        binding.tvKpiP95.text = "P95: ${p95} ms"
        binding.tvKpiRecovery.text = "Recovery: ${recoveryAvg?.let { "$it ms" } ?: "- ms"}"
    }

    private fun logFinalKpi() {
        if (connectionTimes.isEmpty()) return
        log("=== Final KPI Summary ===")
        log("Total Attempts: $connectionAttempts")
        log("Success Count: $connectionSuccesses")
        log("Average Time: ${connectionTimes.average().toInt()} ms")
        log("Min Time: ${connectionTimes.minOrNull()} ms")
        log("Max Time: ${connectionTimes.maxOrNull()} ms")
        val sorted = connectionTimes.sorted()
        val p90 = percentile(sorted, 0.90)
        val p95 = percentile(sorted, 0.95)
        log("P90 Time: $p90 ms")
        log("P95 Time: $p95 ms")
        val summaryDetail = StringBuilder()
            .append("attempts=$connectionAttempts; successes=$connectionSuccesses; avg=${connectionTimes.average().toInt()}ms; min=${connectionTimes.minOrNull()}ms; max=${connectionTimes.maxOrNull()}ms; p90=${p90}ms; p95=${p95}ms")
        if (recoveryTimes.isNotEmpty()) {
            log("Recovery Success: $recoverySuccesses/$connectionSuccesses")
            log("Average Recovery: ${recoveryTimes.average().toInt()} ms")
            log("P95 Recovery: ${percentile(recoveryTimes.sorted(), 0.95)} ms")
            summaryDetail.append("; recoverySuccess=$recoverySuccesses/$connectionSuccesses; recoveryAvg=${recoveryTimes.average().toInt()}ms; recoveryP95=${percentile(recoveryTimes.sorted(), 0.95)}ms")
        }
        LogPersistenceManager.persistTestSummary(
            requireContext(),
            "StressTest",
            "ConnectionKPI",
            "${connectionSuccesses}/${connectionAttempts}",
            summaryDetail.toString()
        )
        log("=========================")
    }

    private fun percentile(sortedValues: List<Long>, p: Double): Long {
        if (sortedValues.isEmpty()) return 0
        val index = kotlin.math.ceil(sortedValues.size * p).toInt().minus(1).coerceIn(0, sortedValues.size - 1)
        return sortedValues[index]
    }

    private fun measureConnectionRecoveryTime(connectStartTime: Long): Long? {
        val context = context ?: return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("Recovery KPI skipped: RECORD_AUDIO permission missing")
            return null
        }

        val sampleRate = 44100
        val probeDurationMs = 5000L
        val recordBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val playBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (recordBufferSize <= 0 || playBufferSize <= 0) {
            log("Recovery KPI skipped: audio probe unavailable")
            return null
        }

        var probeTrack: AudioTrack? = null
        var probeRecord: AudioRecord? = null
        return try {
            probeTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(playBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            probeRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufferSize.coerceAtLeast(4096)
            )

            val playbackSamples = ShortArray(1024)
            var phase = 0.0
            val phaseIncrement = 2 * PI * 440.0 / sampleRate
            var detectedAt: Long? = null
            var writerRunning = true

            probeTrack.play()
            probeRecord.startRecording()

            val writerThread = Thread {
                while (writerRunning && isTesting) {
                    for (i in playbackSamples.indices) {
                        playbackSamples[i] = (sin(phase) * Short.MAX_VALUE * 0.35).toInt().toShort()
                        phase += phaseIncrement
                        if (phase > 2 * PI) phase -= 2 * PI
                    }
                    try {
                        probeTrack.write(playbackSamples, 0, playbackSamples.size)
                    } catch (e: Exception) {
                        break
                    }
                }
            }.apply { start() }

            val readBuffer = ShortArray(2048)
            val waitStart = System.currentTimeMillis()
            while (isTesting && System.currentTimeMillis() - waitStart < probeDurationMs) {
                val read = probeRecord.read(readBuffer, 0, readBuffer.size)
                if (read > 0 && goertzel(readBuffer, read, 440.0, sampleRate) > 500_000.0) {
                    detectedAt = System.currentTimeMillis()
                    break
                }
            }

            writerRunning = false
            writerThread.join(500)
            detectedAt?.minus(connectStartTime).also {
                if (it == null) log("Recovery KPI: acoustic output not detected within ${probeDurationMs}ms probe window")
            }
        } catch (e: Exception) {
            log("Recovery KPI error: ${e.message}")
            null
        } finally {
            runCatching { probeRecord?.stop(); probeRecord?.release() }
            runCatching { probeTrack?.stop(); probeTrack?.release() }
        }
    }

    private fun goertzel(samples: ShortArray, read: Int, targetFreq: Double, sampleRate: Int): Double {
        val n = read.coerceAtMost(samples.size)
        if (n <= 1) return 0.0
        val k = (0.5 + (n * targetFreq / sampleRate)).toInt()
        val omega = 2.0 * PI * k / n
        val coeff = 2.0 * cos(omega)
        var q1 = 0.0
        var q2 = 0.0
        for (i in 0 until n) {
            val q0 = coeff * q1 - q2 + samples[i]
            q2 = q1
            q1 = q0
        }
        return sqrt(q1 * q1 + q2 * q2 - coeff * q1 * q2)
    }

    private fun playGeneratedAudio(durationSec: Int, type: AudioType, loopIndex: Int = 0) {
        val sampleRate = 44100
        val numSamples = durationSec * sampleRate
        val buffer = ShortArray(numSamples)

        // ... (保持原本的音訊生成邏輯)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val sample = when(type) {
                AudioType.SOFT_PIANO -> {
                    (sin(2 * PI * 261.63 * t) * 0.4 + sin(2 * PI * 329.63 * t) * 0.3 + sin(2 * PI * 392.00 * t) * 0.2) * 
                    (1.0 - (i % (sampleRate/2)).toDouble() / (sampleRate/2))
                }
                AudioType.ZEN_BELLS -> {
                    sin(2 * PI * 880.0 * t) * 0.2 * Math.exp(-5.0 * (i % sampleRate).toDouble() / sampleRate)
                }
                AudioType.BEEP_TONE -> sin(2 * PI * 440.0 * t) * 0.5
            }
            buffer[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        activeAudioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(numSamples * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        activeAudioTrack?.let {
            it.write(buffer, 0, numSamples)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                lastUnderrunCount = it.underrunCount
            }
            it.play()
        }
        
        try {
            interruptibleSleep(durationSec)
            
            // Check for Glitches (Underruns) after playback
            activeAudioTrack?.let {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    val currentUnderruns = it.underrunCount
                    val diff = currentUnderruns - lastUnderrunCount
                    if (diff > 0) {
                        log("AUDIO GLITCH DETECTED: $diff underruns during playback")
                        LogPersistenceManager.persistStressKPI(requireContext(), loopIndex, "AUDIO_GLITCH", true, 0, diff)
                    }
                }
            }
        } catch (e: InterruptedException) {
        } finally {
            // ... (原本的 release 邏輯)
            activeAudioTrack?.let {
                try {
                    if (it.state != AudioTrack.STATE_UNINITIALIZED) {
                        it.stop()
                        it.release()
                    }
                } catch (e: Exception) {}
                activeAudioTrack = null
            }
        }
    }

    private fun stopStressTest() {
        isTesting = false
        testThread?.interrupt()
        activeAudioTrack?.let {
            try {
                if (it.state != AudioTrack.STATE_UNINITIALIZED) {
                    it.pause()
                    it.flush()
                    it.release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            activeAudioTrack = null
        }
    }

    private fun copyLogToClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Stress Test Log", binding.testLogText.text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun clearLog() {
        val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        binding.testLogText.text = getString(R.string.log_cleared_at, timeStamp)
    }

    override fun isTestRunning(): Boolean {
        return isTesting
    }

    override fun stopTest() {
        stopStressTest()
    }

    override fun onDestroyView() {
        try {
            requireContext().unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception) {}
        stopStressTest()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(deviceAddress: String, deviceName: String): StressTestFragment {
            val fragment = StressTestFragment()
            val args = Bundle()
            args.putString("DEVICE_ADDRESS", deviceAddress)
            args.putString("DEVICE_NAME", deviceName)
            fragment.arguments = args
            return fragment
        }
    }
}
