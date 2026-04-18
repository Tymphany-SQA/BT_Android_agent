package com.sam.btagent

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
import kotlin.math.sin

class StressTestFragment : Fragment(), MainActivity.TestStatusProvider {

    private var _binding: FragmentStressTestBinding? = null
    private val binding get() = _binding!!

    private var targetDeviceAddress: String? = null
    private var targetDeviceName: String? = null
    
    private var isTesting = false
    private var testThread: Thread? = null
    private var activeAudioTrack: AudioTrack? = null

    // KPI Tracking
    private val connectionTimes = mutableListOf<Long>()
    private var connectionAttempts = 0
    private var connectionSuccesses = 0

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

        isTesting = true
        connectionTimes.clear()
        connectionAttempts = 0
        connectionSuccesses = 0
        resetKpiUI()

        activity?.runOnUiThread {
            binding.startTestButton.isEnabled = false
            binding.stopTestButton.isEnabled = true
            binding.playDurationInput.isEnabled = false
            binding.pauseDurationInput.isEnabled = false
            binding.repeatCountInput.isEnabled = false
            binding.audioSelector.isEnabled = false
            
            binding.testProgressBar.max = repeats
            binding.testProgressBar.progress = 0
            binding.loopProgressText.text = getString(R.string.loop_progress_label, 0, repeats)
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
                    binding.testStatusText.text = getString(R.string.test_status_label, getString(R.string.test_status_idle))
                }
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
        updateStatus("Action: Connect")
        val connectStartTime = System.currentTimeMillis()
        connectionAttempts++
        
        var connected = false
        performConnectionAction(connect = true) { success ->
            if (success) {
                val duration = System.currentTimeMillis() - connectStartTime
                connectionTimes.add(duration)
                connectionSuccesses++
                activity?.runOnUiThread { updateKpiUI() }
                log("Connection KPI: $duration ms")
            } else {
                log("Connection failed or timed out")
            }
            connected = true
        }
        
        // Wait for connection to finish or timeout (max 15s)
        val waitStart = System.currentTimeMillis()
        while (!connected && System.currentTimeMillis() - waitStart < 15000) {
            if (!isTesting) break
            Thread.sleep(200)
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
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = adapter.getRemoteDevice(address)
        
        // For KPI, we focus on A2DP as the primary audio indicator
        val profileId = BluetoothProfile.A2DP
        
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                try {
                    val methodName = if (connect) "connect" else "disconnect"
                    val method = proxy.javaClass.getMethod(methodName, BluetoothDevice::class.java)
                    method.isAccessible = true
                    val result = method.invoke(proxy, device) as? Boolean ?: false
                    
                    if (connect && result) {
                        // Polling for connection state
                        Thread {
                            var success = false
                            val pollStart = System.currentTimeMillis()
                            while (System.currentTimeMillis() - pollStart < 12000) { // 12s timeout
                                if (proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                                    success = true
                                    break
                                }
                                Thread.sleep(100)
                            }
                            onComplete?.invoke(success)
                            adapter.closeProfileProxy(id, proxy)
                        }.start()
                    } else {
                        log("${profileName(id)} ${if (connect) "Connect" else "Disconnect"} call: $result")
                        onComplete?.invoke(result && !connect) // Disconnect always "succeeds" if call returns true
                        adapter.closeProfileProxy(id, proxy)
                    }
                } catch (e: Exception) {
                    log("${profileName(id)} action failed: ${e.message}")
                    onComplete?.invoke(false)
                    adapter.closeProfileProxy(id, proxy)
                }
            }
            override fun onServiceDisconnected(id: Int) {}
        }, profileId)
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
        }
    }

    private fun updateKpiUI() {
        if (connectionTimes.isEmpty()) return
        
        val avg = connectionTimes.average().toLong()
        val min = connectionTimes.minOrNull() ?: 0
        val max = connectionTimes.maxOrNull() ?: 0
        
        val sorted = connectionTimes.sorted()
        val p90Index = (sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)
        val p90 = sorted[p90Index]

        binding.tvKpiAvg.text = getString(R.string.kpi_avg_format, avg)
        binding.tvKpiSuccessRate.text = getString(R.string.kpi_success_rate_format, connectionSuccesses, connectionAttempts)
        binding.tvKpiMinMax.text = getString(R.string.kpi_min_max_format, min.toInt(), max.toInt())
        binding.tvKpiP90.text = getString(R.string.kpi_p90_format, p90.toInt())
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
        val p90 = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)]
        log("P90 Time: $p90 ms")
        log("=========================")
    }

    private fun playGeneratedAudio(durationSec: Int, type: AudioType) {
        val sampleRate = 44100
        val numSamples = durationSec * sampleRate
        val buffer = ShortArray(numSamples)

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
            it.play()
        }
        
        try {
            interruptibleSleep(durationSec)
        } catch (e: InterruptedException) {
        } finally {
            activeAudioTrack?.let {
                try {
                    if (it.state != AudioTrack.STATE_UNINITIALIZED) {
                        it.stop()
                        it.release()
                    }
                } catch (e: Exception) {
                    // Ignore already released or uninitialized
                }
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
                    it.pause() // Use pause() instead of stop() for immediate safety
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
