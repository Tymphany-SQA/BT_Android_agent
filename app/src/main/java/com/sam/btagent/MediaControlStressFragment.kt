package com.sam.btagent

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.sam.btagent.databinding.FragmentMediaControlStressBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaControlStressFragment : Fragment(), MainActivity.TestStatusProvider, TextToSpeech.OnInitListener {

    private var _binding: FragmentMediaControlStressBinding? = null
    private val binding get() = _binding!!

    private lateinit var audioManager: AudioManager
    private var isAutoTesting = false
    private var testThread: Thread? = null

    // Stereo Test (File Based with TTS Pre-generation)
    private var isStereoTesting = false
    private var tts: TextToSpeech? = null
    private var stereoPlayer: MediaPlayer? = null
    private var currentStereoMode = StereoMode.NONE

    private enum class StereoMode { NONE, LEFT_ONLY, RIGHT_ONLY, ALTERNATING }

    private val FILE_LEFT = "test_left_30s.wav"
    private val FILE_RIGHT = "test_right_30s.wav"
    private val FILE_ALT = "test_alt_30s.wav"

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
        
        // 1. 初始禁用按鈕
        setStereoButtonsEnabled(false)

        // Initialize TTS
        tts = TextToSpeech(requireContext(), this)

        // Manual Controls
        binding.openSpotifyButton.setOnClickListener { openSpotify() }
        binding.playPauseButton.setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        binding.prevButton.setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        binding.nextButton.setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
        binding.stopButton.setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_STOP) }
        
        binding.volUpButton.setOnClickListener { 
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        }
        binding.volDownButton.setOnClickListener { 
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }

        // Automation Controls
        binding.startVolCycleButton.setOnClickListener { startVolumeCycle() }
        binding.startRapidPlayButton.setOnClickListener { startRapidPlayPause() }
        binding.stopAutoButton.setOnClickListener { stopAutomation() }
        binding.clearLogButton.setOnClickListener { clearLog() }

        // Stereo Check (File Based)
        binding.btnLeftOnly.setOnClickListener { startFileStereoTest(StereoMode.LEFT_ONLY) }
        binding.btnRightOnly.setOnClickListener { startFileStereoTest(StereoMode.RIGHT_ONLY) }
        binding.btnAlternating.setOnClickListener { startFileStereoTest(StereoMode.ALTERNATING) }
        binding.btnStopStereoTest.setOnClickListener { stopStereoTest() }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            log("TTS Ready for resource generation")
            
            // 設定監聽器，當最後一個檔案合成完成時啟用按鈕
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "GEN_$FILE_ALT") {
                        activity?.runOnUiThread {
                            log("All test resources are ready.")
                            setStereoButtonsEnabled(true)
                        }
                    }
                }
                override fun onError(utteranceId: String?) {
                    activity?.runOnUiThread { log("Error generating resource: $utteranceId") }
                }
            })
            
            checkAndPrepareFiles()
        }
    }

    private fun setStereoButtonsEnabled(enabled: Boolean) {
        binding.btnLeftOnly.isEnabled = enabled
        binding.btnRightOnly.isEnabled = enabled
        binding.btnAlternating.isEnabled = enabled
        
        if (!enabled) {
            binding.tvStereoStatus.visibility = View.VISIBLE
            binding.tvStereoStatus.text = "Preparing resources..."
        } else {
            binding.tvStereoStatus.text = "Resources Ready"
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isStereoTesting && _binding != null) binding.tvStereoStatus.visibility = View.GONE
            }, 2000)
        }
    }

    private fun checkAndPrepareFiles() {
        val t = tts ?: return
        val context = requireContext()
        val resDir = LogPersistenceManager.getResourceDir()
        
        val fLeft = File(resDir, FILE_LEFT)
        val fRight = File(resDir, FILE_RIGHT)
        val fAlt = File(resDir, FILE_ALT)

        // 如果檔案都已經存在且有效，直接啟用按鈕
        if (fLeft.exists() && fRight.exists() && fAlt.exists() && fLeft.length() > 1000) {
            log("Test resources found. Enabling buttons.")
            setStereoButtonsEnabled(true)
            return
        }

        log("Generating new test resources (approx 3-5 seconds)...")
        // 按順序產生，最後一個產生 FILE_ALT 會觸發 onDone 啟用按鈕
        LogPersistenceManager.generateTestWav(context, t, "Left channel. Left channel. Left channel.", FILE_LEFT, -1.0f)
        LogPersistenceManager.generateTestWav(context, t, "Right channel. Right channel. Right channel.", FILE_RIGHT, 1.0f)
        LogPersistenceManager.generateTestWav(context, t, "Left. Right. Left. Right.", FILE_ALT, 0.0f)
    }

    private fun startFileStereoTest(mode: StereoMode) {
        stopStereoTest()
        stopAutomation()

        isStereoTesting = true
        currentStereoMode = mode
        
        val fileName = when(mode) {
            StereoMode.LEFT_ONLY -> FILE_LEFT
            StereoMode.RIGHT_ONLY -> FILE_RIGHT
            StereoMode.ALTERNATING -> FILE_ALT
            else -> return
        }

        val file = File(LogPersistenceManager.getResourceDir(), fileName)
        if (!file.exists()) {
            Toast.makeText(context, "File missing, re-generating...", Toast.LENGTH_SHORT).show()
            checkAndPrepareFiles()
            return
        }

        log("Playing: $fileName")
        binding.tvStereoStatus.visibility = View.VISIBLE
        binding.tvStereoStatus.text = "Playing: ${mode.name}"

        try {
            stereoPlayer = MediaPlayer.create(requireContext(), Uri.fromFile(file)).apply {
                isLooping = true
                start()
            }
        } catch (e: Exception) {
            log("Playback failed: ${e.message}")
        }
    }

    private fun stopStereoTest() {
        isStereoTesting = false
        try {
            stereoPlayer?.stop()
            stereoPlayer?.release()
        } catch (e: Exception) {}
        stereoPlayer = null
        
        activity?.runOnUiThread {
            binding.tvStereoStatus.text = "Stopped"
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isStereoTesting && _binding != null) {
                    binding.tvStereoStatus.visibility = View.GONE
                }
            }, 2000)
        }
    }

    private fun openSpotify() {
        val intent = requireContext().packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (intent != null) startActivity(intent)
        else log("Spotify not found.")
    }

    private fun sendMediaKey(keyCode: Int) {
        val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        audioManager.dispatchMediaKeyEvent(eventDown)
        val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(eventUp)
    }

    private fun startVolumeCycle() {
        val interval = binding.volIntervalInput.text.toString().toLongOrNull() ?: 1000L
        val minPct = binding.minVolInput.text.toString().toIntOrNull() ?: 20
        val maxPct = binding.maxVolInput.text.toString().toIntOrNull() ?: 70
        
        isAutoTesting = true
        updateUiForTest(true)
        testThread = Thread {
            try {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val minVolLimit = (maxVol * minPct / 100).coerceAtLeast(0)
                val maxVolLimit = (maxVol * maxPct / 100).coerceAtMost(maxVol)
                
                log("Starting Volume Cycle: $minVolLimit to $maxVolLimit (Max: $maxVol)")
                var up = true
                while (isAutoTesting) {
                    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (up) {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                        if (current >= maxVolLimit) up = false
                    } else {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                        if (current <= minVolLimit) up = true
                    }
                    Thread.sleep(interval)
                }
            } catch (e: Exception) {
                log("Volume Cycle Error: ${e.message}")
            } finally { 
                activity?.runOnUiThread { updateUiForTest(false) } 
            }
        }
        testThread?.start()
    }

    private fun startRapidPlayPause() {
        val baseInterval = binding.rapidIntervalInput.text.toString().toLongOrNull() ?: 1000L
        val randomRange = binding.randomRangeInput.text.toString().toLongOrNull() ?: 0L
        val isNonStop = binding.cbNonStop.isChecked
        val loopLimit = if (isNonStop) Int.MAX_VALUE else (binding.rapidLoopCountInput.text.toString().toIntOrNull() ?: 100)
        
        val commands = mutableListOf<Int>()
        if (binding.cbPlayPause.isChecked) commands.add(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        if (binding.cbNext.isChecked) commands.add(KeyEvent.KEYCODE_MEDIA_NEXT)
        if (binding.cbPrev.isChecked) commands.add(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        if (binding.cbStop.isChecked) commands.add(KeyEvent.KEYCODE_MEDIA_STOP)
        
        if (commands.isEmpty()) {
            Toast.makeText(context, "Please select at least one command", Toast.LENGTH_SHORT).show()
            return
        }

        isAutoTesting = true
        updateUiForTest(true)
        
        if (!isNonStop) {
            binding.rapidProgressBar.visibility = View.VISIBLE
            binding.rapidProgressBar.max = loopLimit
            binding.rapidProgressBar.progress = 0
        }

        testThread = Thread {
            try {
                var count = 0
                while (isAutoTesting && count < loopLimit) {
                    val cmd = commands.random()
                    sendMediaKey(cmd)
                    count++
                    
                    val currentCount = count
                    activity?.runOnUiThread {
                        binding.currentStatusText.text = "Status: Running ($currentCount/$loopLimit)"
                        if (!isNonStop) binding.rapidProgressBar.progress = currentCount
                    }

                    val sleepTime = if (randomRange > 0) {
                        baseInterval + ((-randomRange..randomRange).random())
                    } else {
                        baseInterval
                    }.coerceAtLeast(10L)
                    
                    Thread.sleep(sleepTime)
                }
                log("Rapid Stress completed $count cycles.")
            } catch (e: InterruptedException) {
                log("Rapid Stress interrupted.")
            } catch (e: Exception) {
                log("Rapid Stress error: ${e.message}")
            } finally {
                activity?.runOnUiThread {
                    updateUiForTest(false)
                    binding.rapidProgressBar.visibility = View.GONE
                    binding.currentStatusText.text = "Status: Idle"
                }
            }
        }
        testThread?.start()
    }

    private fun stopAutomation() {
        isAutoTesting = false
        testThread?.interrupt()
    }

    private fun updateUiForTest(running: Boolean) {
        binding.startVolCycleButton.isEnabled = !running
        binding.startRapidPlayButton.isEnabled = !running
        binding.stopAutoButton.visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun log(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        activity?.runOnUiThread { if (_binding != null) binding.mediaLogText.append("[$ts] $message\n") }
    }

    private fun clearLog() { binding.mediaLogText.text = "" }

    override fun isTestRunning(): Boolean = isAutoTesting || isStereoTesting
    override fun stopTest() { stopAutomation(); stopStereoTest() }

    override fun onDestroyView() {
        stopAutomation()
        stopStereoTest()
        tts?.shutdown()
        super.onDestroyView()
        _binding = null
    }
}
