package com.sam.btagent

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.sam.btagent.databinding.FragmentBatteryMonitorBinding

class BatteryMonitorFragment : Fragment(), MainActivity.TestStatusProvider {

    private var _binding: FragmentBatteryMonitorBinding? = null
    private val binding get() = _binding!!

    private var service: BatteryLoggingService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? BatteryLoggingService.LocalBinder
            val s = localBinder?.getService()
            service = s
            isBound = true
            
            if (s != null) {
                if (s.isLoggingActive()) {
                    updateLoggingUI(true)
                    binding.batteryHistoryText.text = s.getFullLog()
                    autoScrollLog()
                    updateRssiUI(s.getLastKnownRssi())
                    updateGlitchesUI(s.getTotalGlitches())
                }
                s.setLogUpdateListener { newEntry: String ->
                    activity?.runOnUiThread {
                        binding.batteryHistoryText.append(newEntry)
                        autoScrollLog()
                        updateRssiUI(service?.getLastKnownRssi())
                        updateGlitchesUI(service?.getTotalGlitches() ?: 0)
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            service = null
        }
    }

    private var targetDevice: BluetoothDevice? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED") {
                val level = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                updateCurrentBatteryUI(level)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBatteryMonitorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        requireActivity().title = getString(R.string.nav_battery_monitor)
        binding.batteryHistoryText.movementMethod = ScrollingMovementMethod()
        
        // 資源化 Spinner 選項
        val adapterOptions = arrayOf(
            getString(R.string.strategy_standard),
            getString(R.string.strategy_stable)
        )
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, adapterOptions)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.bufferStrategySpinner.adapter = spinnerAdapter

        findConnectedDevice()

        binding.btnStartBatteryLog.setOnClickListener { startLogging() }
        binding.btnStopBatteryLog.setOnClickListener { stopLogging() }
        binding.btnClearBatteryLog.setOnClickListener {
            binding.batteryHistoryText.text = ""
            service?.clearHistory()
            Toast.makeText(requireContext(), R.string.log_cleared_toast, Toast.LENGTH_SHORT).show()
        }

        val intent = Intent(requireContext(), BatteryLoggingService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        val filter = IntentFilter("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.RECEIVER_EXPORTED
        } else {
            0
        }
        requireContext().registerReceiver(bluetoothReceiver, filter, flags)
    }

    private fun findConnectedDevice() {
        val manager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return
        
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) 
            != PackageManager.PERMISSION_GRANTED) {
            binding.targetDeviceName.text = getString(R.string.permission_required)
            return
        }

        adapter.getProfileProxy(requireContext(), object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val connected = try { proxy.connectedDevices } catch (e: SecurityException) { emptyList() }
                if (connected.isNotEmpty()) {
                    val device = connected[0]
                    targetDevice = device
                    val deviceName = try {
                        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            device.name ?: device.address
                        } else {
                            device.address
                        }
                    } catch (e: Exception) {
                        device.address
                    }
                    binding.targetDeviceName.text = getString(R.string.battery_device_format, deviceName ?: "Unknown")
                    refreshBatteryNow()
                }
                adapter.closeProfileProxy(profile, proxy)
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
    }

    private fun refreshBatteryNow() {
        targetDevice?.let { device ->
            val level = readBatteryLevel(device)
            updateCurrentBatteryUI(level)
        }
    }

    private fun readBatteryLevel(device: BluetoothDevice): Int {
        return try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            method.invoke(device) as Int
        } catch (e: Exception) { -1 }
    }

    private fun updateCurrentBatteryUI(level: Int) {
        val levelText = if (level >= 0) "$level%" else "--%"
        binding.currentBatteryText.text = getString(R.string.battery_current_format, levelText)
    }

    private fun updateRssiUI(rssi: Int?) {
        val rssiValue = rssi?.toString() ?: "--"
        binding.currentRssiText.text = getString(R.string.battery_rssi_format, rssiValue)
    }

    private fun updateGlitchesUI(glitches: Int) {
        binding.currentGlitchesText.text = getString(R.string.battery_glitches_format, glitches)
        val colorRes = if (glitches > 0) android.R.color.holo_red_dark else android.R.color.black
        binding.currentGlitchesText.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
    }

    private fun startLogging() {
        val device = targetDevice ?: return
        val intervalSec = binding.batteryIntervalInput.text.toString().toLongOrNull() ?: 60L
        if (intervalSec < 1) return

        val intent = Intent(requireContext(), BatteryLoggingService::class.java)
        ContextCompat.startForegroundService(requireContext(), intent)
        
        service?.startLogging(device, intervalSec, binding.swEnableAudioMonitor.isChecked, 
            binding.cbSilentMode.isChecked, binding.bufferStrategySpinner.selectedItemPosition)
        updateLoggingUI(true)
    }

    private fun stopLogging() {
        service?.stopLogging()
        updateLoggingUI(false)
    }

    private fun updateLoggingUI(active: Boolean) {
        binding.btnStartBatteryLog.visibility = if (active) View.GONE else View.VISIBLE
        binding.btnStopBatteryLog.visibility = if (active) View.VISIBLE else View.GONE
        binding.batteryIntervalInput.isEnabled = !active
        binding.swEnableAudioMonitor.isEnabled = !active
        binding.cbSilentMode.isEnabled = !active
        binding.bufferStrategySpinner.isEnabled = !active
    }

    private fun autoScrollLog() {
        binding.batteryHistoryText.post {
            val layout = binding.batteryHistoryText.layout ?: return@post
            val scrollAmount = layout.getLineBottom(binding.batteryHistoryText.lineCount - 1) -
                    (binding.batteryHistoryText.height - binding.batteryHistoryText.paddingTop - binding.batteryHistoryText.paddingBottom)
            if (scrollAmount > 0) binding.batteryHistoryText.scrollTo(0, scrollAmount)
        }
    }

    override fun isTestRunning(): Boolean = service?.isLoggingActive() ?: false
    override fun stopTest() = stopLogging()

    override fun onDestroyView() {
        if (isBound) {
            service?.setLogUpdateListener(null)
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
        try { requireContext().unregisterReceiver(bluetoothReceiver) } catch (e: Exception) {}
        super.onDestroyView()
        _binding = null
    }
}
