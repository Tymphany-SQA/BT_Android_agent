package com.sam.btagent

import android.content.Context
import android.media.AudioManager
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
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        updateStatus("Manual: SCO ON (HFP)")
        log("Manual: startBluetoothSco() called")
    }

    private fun stopSco() {
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
        updateStatus("Manual: SCO OFF (A2DP)")
        log("Manual: stopBluetoothSco() called")
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
        activity?.runOnUiThread {
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
    }

    override fun isTestRunning(): Boolean = isAutoTesting

    override fun stopTest() = stopHfpStress()

    override fun onDestroyView() {
        stopHfpStress()
        super.onDestroyView()
        _binding = null
    }
}
