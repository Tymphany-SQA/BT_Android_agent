package com.sam.btagent

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.sam.btagent.databinding.FragmentMediaControlStressBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MediaControlStressFragment : Fragment(), MainActivity.TestStatusProvider {

    private var _binding: FragmentMediaControlStressBinding? = null
    private val binding get() = _binding!!

    private lateinit var audioManager: AudioManager
    private var isAutoTesting = false
    private var testThread: Thread? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaControlStressBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Manual Controls
        binding.openSpotifyButton.setOnClickListener { openSpotify() }
        binding.playPauseButton.setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        binding.prevButton.setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        binding.nextButton.setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
        binding.stopButton.setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_STOP) }
        
        binding.volUpButton.setOnClickListener { 
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            log("Manual Vol Up")
        }
        binding.volDownButton.setOnClickListener { 
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            log("Manual Vol Down")
        }

        // Automation Controls
        binding.startVolCycleButton.setOnClickListener { startVolumeCycle() }
        binding.startRapidPlayButton.setOnClickListener { startRapidPlayPause() }
        binding.stopAutoButton.setOnClickListener { stopAutomation() }
        binding.clearLogButton.setOnClickListener { clearLog() }
    }

    private fun openSpotify() {
        val intent = requireContext().packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (intent != null) {
            startActivity(intent)
            log("Opening Spotify...")
        } else {
            Toast.makeText(context, "Spotify is not installed.", Toast.LENGTH_SHORT).show()
            log("Spotify not found. Please open your music app manually.")
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        audioManager.dispatchMediaKeyEvent(eventDown)
        val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(eventUp)
        
        val actionName = when(keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> "PLAY"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "PAUSE"
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "PLAY/PAUSE"
            KeyEvent.KEYCODE_MEDIA_STOP -> "STOP"
            KeyEvent.KEYCODE_MEDIA_NEXT -> "NEXT"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "PREV"
            else -> "KEY_$keyCode"
        }
        
        updateStatus("Manual: $actionName")
        log("Sent Media Key: $actionName")
    }

    private fun startVolumeCycle() {
        val interval = binding.volIntervalInput.text.toString().toLongOrNull() ?: 1000L
        val minPct = binding.minVolInput.text.toString().toIntOrNull() ?: 20
        val maxPct = binding.maxVolInput.text.toString().toIntOrNull() ?: 70
        
        isAutoTesting = true
        updateUiForTest(true)
        updateStatus("Auto: Vol Cycle (${interval}ms)")
        log("Starting Volume Cycle Stress ($minPct% to $maxPct%, Interval: ${interval}ms)")

        testThread = Thread {
            try {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val minVolIndex = (maxVol * (minPct / 100f)).toInt().coerceIn(0, maxVol)
                val maxVolIndex = (maxVol * (maxPct / 100f)).toInt().coerceIn(0, maxVol)
                
                var increasing = true
                // Initialize to min
                activity?.runOnUiThread {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, minVolIndex, 0)
                }
                
                while (isAutoTesting) {
                    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    
                    if (increasing) {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                        if (currentVol >= maxVolIndex) increasing = false
                    } else {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                        if (currentVol <= minVolIndex) increasing = true
                    }
                    
                    val currentPct = if (maxVol > 0) (currentVol.toFloat() / maxVol * 100).toInt() else 0
                    log("Vol Auto Adj: $currentPct% ($currentVol/$maxVol)")
                    Thread.sleep(interval)
                }
            } catch (e: InterruptedException) {
                // Thread stopped
            } catch (e: Exception) {
                log("Vol Cycle Error: ${e.message}")
            } finally {
                activity?.runOnUiThread { updateUiForTest(false) }
            }
        }
        testThread?.start()
    }

    private fun startRapidPlayPause() {
        val baseInterval = binding.rapidIntervalInput.text.toString().toLongOrNull() ?: 1000L
        val randomRange = binding.randomRangeInput.text.toString().toIntOrNull() ?: 500
        
        val selectedKeys = mutableListOf<Int>()
        if (binding.cbPlayPause.isChecked) selectedKeys.add(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        if (binding.cbNext.isChecked) selectedKeys.add(KeyEvent.KEYCODE_MEDIA_NEXT)
        if (binding.cbPrev.isChecked) selectedKeys.add(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        if (binding.cbStop.isChecked) selectedKeys.add(KeyEvent.KEYCODE_MEDIA_STOP)
        
        if (selectedKeys.isEmpty()) {
            Toast.makeText(context, "Please select at least one command", Toast.LENGTH_SHORT).show()
            return
        }

        isAutoTesting = true
        updateUiForTest(true)
        
        log("Starting Rapid Stress (Base: ${baseInterval}ms, Random: 0-$randomRange, Keys: ${selectedKeys.size})")

        testThread = Thread {
            try {
                var index = 0
                while (isAutoTesting) {
                    val extraDelay = if (randomRange > 0) Random.nextInt(1, randomRange + 1) else 0
                    val totalDelay = baseInterval + extraDelay
                    
                    val keyCode = selectedKeys[index % selectedKeys.size]
                    
                    activity?.runOnUiThread {
                        updateStatus("Auto: Rapid Cmd (${totalDelay}ms)")
                    }
                    
                    sendMediaKey(keyCode)
                    log("Key sent, next in ${totalDelay}ms (random: +${extraDelay}ms)")
                    
                    index++
                    Thread.sleep(totalDelay)
                }
            } catch (e: InterruptedException) {
                // Thread stopped
            } catch (e: Exception) {
                log("Rapid Stress Error: ${e.message}")
            } finally {
                activity?.runOnUiThread { updateUiForTest(false) }
            }
        }
        testThread?.start()
    }

    private fun stopAutomation() {
        isAutoTesting = false
        testThread?.interrupt()
        log("Automation stopped.")
        updateStatus("Stopped")
        updateUiForTest(false)
    }

    private fun updateStatus(status: String) {
        activity?.runOnUiThread {
            if (_binding != null) {
                binding.currentStatusText.text = "Status: $status"
            }
        }
    }

    private fun updateUiForTest(running: Boolean) {
        binding.startVolCycleButton.isEnabled = !running
        binding.startRapidPlayButton.isEnabled = !running
        binding.volIntervalInput.isEnabled = !running
        binding.rapidIntervalInput.isEnabled = !running
        binding.randomRangeInput.isEnabled = !running
        binding.minVolInput.isEnabled = !running
        binding.maxVolInput.isEnabled = !running
        binding.cbPlayPause.isEnabled = !running
        binding.cbNext.isEnabled = !running
        binding.cbPrev.isEnabled = !running
        binding.cbStop.isEnabled = !running
        binding.stopAutoButton.visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun log(message: String) {
        val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        activity?.runOnUiThread {
            if (_binding != null) {
                binding.mediaLogText.append("[$timeStamp] $message\n")
                val scrollAmount = binding.mediaLogText.layout?.let { 
                    it.getLineTop(binding.mediaLogText.lineCount) - binding.mediaLogText.height 
                } ?: 0
                if (scrollAmount > 0) {
                    binding.mediaLogText.scrollTo(0, scrollAmount)
                }
            }
        }
    }

    private fun clearLog() {
        binding.mediaLogText.text = ""
        log("Log cleared.")
    }

    override fun isTestRunning(): Boolean {
        return isAutoTesting
    }

    override fun stopTest() {
        stopAutomation()
    }

    override fun onDestroyView() {
        stopAutomation()
        super.onDestroyView()
        _binding = null
    }
}
