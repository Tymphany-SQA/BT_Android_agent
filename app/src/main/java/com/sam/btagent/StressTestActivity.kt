package com.sam.btagent

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.sam.btagent.databinding.ActivityStressTestBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

class StressTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStressTestBinding
    private var targetDeviceAddress: String? = null
    private var targetDeviceName: String? = null
    
    private var isTesting = false
    private var testThread: Thread? = null
    private var activeAudioTrack: AudioTrack? = null

    private enum class AudioType(val label: String) {
        SOFT_PIANO("Soft Piano (Gentle)"),
        ZEN_BELLS("Zen Bells (Quiet)"),
        BEEP_TONE("Standard Beep (440Hz)")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStressTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        targetDeviceAddress = intent.getStringExtra("DEVICE_ADDRESS")
        targetDeviceName = intent.getStringExtra("DEVICE_NAME") ?: "Unknown Device"

        binding.targetDeviceName.text = "Target: $targetDeviceName ($targetDeviceAddress)"
        binding.testLogText.movementMethod = ScrollingMovementMethod()

        // Setup Audio Selector
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, AudioType.values().map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.audioSelector.adapter = adapter
        binding.audioSelector.setSelection(0) // Default to Soft Piano

        binding.startTestButton.setOnClickListener { startStressTest() }
        binding.stopTestButton.setOnClickListener { stopStressTest() }
    }

    private fun startStressTest() {
        if (targetDeviceAddress == null) {
            log("Error: No device selected")
            return
        }

        val playSec = binding.playDurationInput.text.toString().toIntOrNull() ?: 10
        val pauseSec = binding.pauseDurationInput.text.toString().toIntOrNull() ?: 5
        val repeats = binding.repeatCountInput.text.toString().toIntOrNull() ?: 3
        val selectedAudio = AudioType.values()[binding.audioSelector.selectedItemPosition]

        isTesting = true
        binding.startTestButton.isEnabled = false
        binding.stopTestButton.isEnabled = true
        binding.playDurationInput.isEnabled = false
        binding.pauseDurationInput.isEnabled = false
        binding.repeatCountInput.isEnabled = false
        binding.audioSelector.isEnabled = false
        
        binding.testProgressBar.max = repeats
        binding.testProgressBar.progress = 0
        binding.loopProgressText.text = "Loop: 0 / $repeats"

        log("Starting stress test: $repeats loops")
        log("Settings: Play ${playSec}s, Intervals ${pauseSec}s, Audio: ${selectedAudio.label}")

        testThread = Thread {
            try {
                for (i in 1..repeats) {
                    if (!isTesting) break
                    
                    runOnUiThread {
                        binding.loopProgressText.text = "Loop: $i / $repeats"
                        binding.testProgressBar.progress = i
                    }

                    runLoopStep(i, playSec, pauseSec, selectedAudio)
                }
                log("Stress test completed successfully.")
            } catch (e: InterruptedException) {
                log("Test manually stopped.")
            } catch (e: Exception) {
                log("Error during test: ${e.message}")
            } finally {
                runOnUiThread {
                    isTesting = false
                    binding.startTestButton.isEnabled = true
                    binding.stopTestButton.isEnabled = false
                    binding.playDurationInput.isEnabled = true
                    binding.pauseDurationInput.isEnabled = true
                    binding.repeatCountInput.isEnabled = true
                    binding.audioSelector.isEnabled = true
                    binding.testStatusText.text = "Status: Idle"
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
        
        // 5. Connect
        if (!isTesting) return
        updateStatus("Action: Connect")
        performConnectionAction(connect = true)
        interruptibleSleep(pauseSec + 5)
        
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
        runOnUiThread {
            binding.testStatusText.text = "Status: $status"
            log(status)
        }
    }

    private fun log(message: String) {
        val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            binding.testLogText.append("[$timeStamp] $message\n")
            val scrollAmount = binding.testLogText.layout?.let { 
                it.getLineTop(binding.testLogText.lineCount) - binding.testLogText.height 
            } ?: 0
            if (scrollAmount > 0) {
                binding.testLogText.scrollTo(0, scrollAmount)
            }
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

    private fun performConnectionAction(connect: Boolean) {
        val address = targetDeviceAddress ?: return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = adapter.getRemoteDevice(address)
        
        val profiles = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        
        profiles.forEach { profileId ->
            adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                    try {
                        val methodName = if (connect) "connect" else "disconnect"
                        val method = proxy.javaClass.getMethod(methodName, BluetoothDevice::class.java)
                        method.isAccessible = true
                        val result = method.invoke(proxy, device) as? Boolean ?: false
                        log("${profileName(id)} ${if (connect) "Connect" else "Disconnect"} sent: $result")
                    } catch (e: Exception) {
                        log("${profileName(id)} action failed: ${e.message}")
                    } finally {
                        adapter.closeProfileProxy(id, proxy)
                    }
                }
                override fun onServiceDisconnected(id: Int) {}
            }, profileId)
        }
    }

    private fun profileName(id: Int) = when(id) {
        BluetoothProfile.A2DP -> "A2DP"
        BluetoothProfile.HEADSET -> "HFP"
        else -> "Profile $id"
    }

    private fun playGeneratedAudio(durationSec: Int, type: AudioType) {
        val sampleRate = 44100
        val numSamples = durationSec * sampleRate
        val buffer = ShortArray(numSamples)

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val sample = when(type) {
                AudioType.SOFT_PIANO -> {
                    // Combine a few low-frequency sine waves for a "mellow" sound
                    (sin(2 * PI * 261.63 * t) * 0.4 + sin(2 * PI * 329.63 * t) * 0.3 + sin(2 * PI * 392.00 * t) * 0.2) * 
                    (1.0 - (i % (sampleRate/2)).toDouble() / (sampleRate/2)) // Simple decay for "plucking" effect
                }
                AudioType.ZEN_BELLS -> {
                    // Very high frequency with fast decay
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
            activeAudioTrack?.stop()
            activeAudioTrack?.release()
            activeAudioTrack = null
        }
    }

    private fun stopStressTest() {
        isTesting = false
        testThread?.interrupt()
        activeAudioTrack?.stop()
        activeAudioTrack?.release()
        activeAudioTrack = null
    }

    override fun onDestroy() {
        stopStressTest()
        super.onDestroy()
    }
}
