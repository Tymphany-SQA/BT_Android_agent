package com.sam.btagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.sam.btagent.databinding.FragmentHfpStressBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HfpStressFragment : Fragment(), MainActivity.TestStatusProvider {

    private var _binding: FragmentHfpStressBinding? = null
    private val binding get() = _binding!!

    private lateinit var audioManager: AudioManager
    private var isAutoTesting = false
    private var testThread: Thread? = null
    private var focusRequest: AudioFocusRequest? = null

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            val stateStr = when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> "CONNECTED"
                AudioManager.SCO_AUDIO_STATE_CONNECTING -> "CONNECTING"
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> "DISCONNECTED"
                AudioManager.SCO_AUDIO_STATE_ERROR -> "ERROR"
                else -> "UNKNOWN ($state)"
            }
            log("SCO State Changed: $stateStr")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHfpStressBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager

        requireContext().registerReceiver(
            scoReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        )

        binding.btnStartSco.setOnClickListener { startSco() }
        binding.btnStopSco.setOnClickListener { stopSco() }
        binding.btnStartHfpStress.setOnClickListener { startHfpStress() }
        binding.btnStopHfpStress.setOnClickListener { stopHfpStress() }
        binding.btnClearHfpLog.setOnClickListener {
            binding.hfpLogText.text = ""
            log("Log cleared.")
        }
    }

    private fun startSco() {
        log("Requesting SCO & Audio Focus...")
        
        // Request Audio Focus to stop/duck music
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    log("Focus Change: $focusChange")
                }
                .build()
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        updateStatus("Manual: SCO ON (HFP Request)")
    }

    private fun stopSco() {
        log("Stopping SCO & Releasing Focus...")
        
        // Abandon focus to let music resume
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }

        audioManager.isBluetoothScoOn = false
        audioManager.stopBluetoothSco()
        audioManager.mode = AudioManager.MODE_NORMAL
        updateStatus("Manual: SCO OFF (A2DP Request)")
    }

    private fun startHfpStress() {
        val a2dpDur = binding.a2dpDurationInput.text.toString().toLongOrNull() ?: 5000L
        val hfpDur = binding.hfpDurationInput.text.toString().toLongOrNull() ?: 5000L

        isAutoTesting = true
        updateUiForTest(true)
        log("Starting HFP Stress Loop (A2DP: ${a2dpDur}ms, HFP: ${hfpDur}ms)")

        testThread = Thread {
            try {
                var loopCount = 1
                while (isAutoTesting) {
                    // Step 1: A2DP Mode
                    activity?.runOnUiThread {
                        stopSco()
                        updateStatus("Loop #$loopCount: A2DP Mode")
                    }
                    log("Loop #$loopCount: A2DP for ${a2dpDur}ms")
                    Thread.sleep(a2dpDur)

                    if (!isAutoTesting) break

                    // Step 2: HFP Mode
                    activity?.runOnUiThread {
                        startSco()
                        updateStatus("Loop #$loopCount: HFP Mode")
                    }
                    log("Loop #$loopCount: HFP for ${hfpDur}ms")
                    Thread.sleep(hfpDur)

                    loopCount++
                }
            } catch (e: InterruptedException) {
                log("Test Interrupted")
            } catch (e: Exception) {
                log("Error: ${e.message}")
            } finally {
                activity?.runOnUiThread {
                    stopSco()
                    updateUiForTest(false)
                    updateStatus("Stopped")
                }
            }
        }
        testThread?.start()
    }

    private fun stopHfpStress() {
        isAutoTesting = false
        testThread?.interrupt()
        log("HFP Stress stopping...")
    }

    private fun updateStatus(status: String) {
        binding.hfpStatusText.text = "Status: $status"
    }

    private fun updateUiForTest(running: Boolean) {
        binding.btnStartHfpStress.isEnabled = !running
        binding.a2dpDurationInput.isEnabled = !running
        binding.hfpDurationInput.isEnabled = !running
        binding.btnStartSco.isEnabled = !running
        binding.btnStopSco.isEnabled = !running
        binding.btnStopHfpStress.visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun log(message: String) {
        val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        activity?.let { act ->
            act.runOnUiThread {
                if (_binding != null) {
                    binding.hfpLogText.append("[$timeStamp] $message\n")
                    val scrollAmount = binding.hfpLogText.layout?.let {
                        it.getLineTop(binding.hfpLogText.lineCount) - binding.hfpLogText.height
                    } ?: 0
                    if (scrollAmount > 0) {
                        binding.hfpLogText.scrollTo(0, scrollAmount)
                    }
                }
            }
            LogPersistenceManager.persistLog(act.applicationContext, "HFP_Stress", message)
        }
    }

    override fun isTestRunning(): Boolean = isAutoTesting

    override fun stopTest() = stopHfpStress()

    override fun onDestroyView() {
        stopHfpStress()
        try {
            requireContext().unregisterReceiver(scoReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
        super.onDestroyView()
        _binding = null
    }
}
