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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.sam.btagent.databinding.FragmentBatteryMonitorBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BatteryMonitorFragment : Fragment(), MainActivity.TestStatusProvider {

    private var _binding: FragmentBatteryMonitorBinding? = null
    private val binding get() = _binding!!

    private var service: BatteryLoggingService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as BatteryLoggingService.LocalBinder
            service = localBinder.getService()
            isBound = true
            
            service?.let {
                if (it.isLoggingActive()) {
                    updateLoggingUI(true)
                    binding.batteryHistoryText.text = it.getFullLog()
                    autoScrollLog()
                    // Update RSSI immediately if bound
                    updateRssiUI(it.getLastKnownRssi())
                }
                it.setLogUpdateListener { newEntry ->
                    activity?.runOnUiThread {
                        binding.batteryHistoryText.append(newEntry)
                        autoScrollLog()
                        // Extract RSSI from log entry or fetch from service
                        updateRssiUI(service?.getLastKnownRssi())
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBatteryMonitorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.batteryHistoryText.movementMethod = ScrollingMovementMethod()
        findConnectedDevice()

        binding.btnStartBatteryLog.setOnClickListener { startLogging() }
        binding.btnStopBatteryLog.setOnClickListener { stopLogging() }
        binding.btnClearBatteryLog.setOnClickListener {
            binding.batteryHistoryText.text = ""
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
        val adapter = manager.adapter
        
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) 
            != PackageManager.PERMISSION_GRANTED) return

        adapter.getProfileProxy(requireContext(), object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val connected = proxy.connectedDevices
                if (connected.isNotEmpty()) {
                    targetDevice = connected[0]
                    val deviceName = try {
                        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            targetDevice?.name ?: targetDevice?.address
                        } else {
                            targetDevice?.address
                        }
                    } catch (e: SecurityException) {
                        targetDevice?.address
                    }
                    binding.targetDeviceName.text = "Device: $deviceName"
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
        } catch (e: Exception) {
            -1
        }
    }

    private fun updateCurrentBatteryUI(level: Int) {
        val levelText = if (level >= 0) "$level%" else "--%"
        binding.currentBatteryText.text = "Current: $levelText"
    }

    private fun updateRssiUI(rssi: Int?) {
        val rssiText = rssi?.let { "RSSI: $it dBm" } ?: "RSSI: -- dBm"
        binding.currentRssiText.text = rssiText
    }

    private fun startLogging() {
        val device = targetDevice ?: return
        val intervalSec = binding.batteryIntervalInput.text.toString().toLongOrNull() ?: 60L
        if (intervalSec < 1) return

        val intent = Intent(requireContext(), BatteryLoggingService::class.java)
        ContextCompat.startForegroundService(requireContext(), intent)
        
        service?.startLogging(device, intervalSec)
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
    }

    private fun autoScrollLog() {
        val scrollAmount = binding.batteryHistoryText.layout?.let {
            it.lineCount * binding.batteryHistoryText.lineHeight - binding.batteryHistoryText.height
        } ?: 0
        if (scrollAmount > 0) {
            binding.batteryHistoryText.scrollTo(0, scrollAmount)
        }
    }

    override fun isTestRunning(): Boolean = false

    override fun stopTest() {}

    override fun onDestroyView() {
        if (isBound) {
            service?.setLogUpdateListener { }
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
        try {
            requireContext().unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {}
        super.onDestroyView()
        _binding = null
    }
}
