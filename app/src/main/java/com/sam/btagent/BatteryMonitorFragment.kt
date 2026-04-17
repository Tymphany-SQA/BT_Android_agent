package com.sam.btagent

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private var isLogging = false
    private val handler = Handler(Looper.getMainLooper())
    private var logRunnable: Runnable? = null
    
    private var targetDevice: BluetoothDevice? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED) {
                val level = intent.getIntExtra(BluetoothDevice.EXTRA_BATTERY_LEVEL, -1)
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                
                if (device?.address == targetDevice?.address) {
                    log("Broadcast received: $level%")
                    updateCurrentBatteryUI(level)
                }
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

        findConnectedDevice()

        binding.btnStartBatteryLog.setOnClickListener { startLogging() }
        binding.btnStopBatteryLog.setOnClickListener { stopLogging() }
        binding.btnClearBatteryLog.setOnClickListener {
            binding.batteryHistoryText.text = ""
            log("Log cleared.")
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED)
        requireContext().registerReceiver(bluetoothReceiver, filter)
    }

    private fun findConnectedDevice() {
        val manager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) 
            != PackageManager.PERMISSION_GRANTED) return

        // Check A2DP connected devices as priority
        adapter.getProfileProxy(requireContext(), object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val connected = proxy.connectedDevices
                if (connected.isNotEmpty()) {
                    targetDevice = connected[0]
                    binding.targetDeviceName.text = "Device: ${targetDevice?.name ?: targetDevice?.address}"
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
            // Use reflection for older versions if needed, but getBatteryLevel is public in modern SDKs
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

    private fun startLogging() {
        val intervalSec = binding.batteryIntervalInput.text.toString().toLongOrNull() ?: 60L
        if (intervalSec < 1) return

        isLogging = true
        binding.btnStartBatteryLog.visibility = View.GONE
        binding.btnStopBatteryLog.visibility = View.VISIBLE
        binding.batteryIntervalInput.isEnabled = false

        log("Starting Battery Log (Interval: ${intervalSec}s)...")
        
        logRunnable = object : Runnable {
            override fun run() {
                if (!isLogging) return
                
                targetDevice?.let { device ->
                    val level = readBatteryLevel(device)
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    val entry = "[$time] Battery: ${if (level >= 0) "$level%" else "Unknown"}\n"
                    
                    binding.batteryHistoryText.append(entry)
                    updateCurrentBatteryUI(level)
                } ?: run {
                    binding.batteryHistoryText.append("[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] No device connected\n")
                    findConnectedDevice()
                }
                
                handler.postDelayed(this, intervalSec * 1000)
            }
        }
        handler.post(logRunnable!!)
    }

    private fun stopLogging() {
        isLogging = false
        logRunnable?.let { handler.removeCallbacks(it) }
        binding.btnStartBatteryLog.visibility = View.VISIBLE
        binding.btnStopBatteryLog.visibility = View.GONE
        binding.batteryIntervalInput.isEnabled = true
        log("Logging stopped.")
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        binding.batteryHistoryText.append("[$time] $message\n")
    }

    override fun isTestRunning(): Boolean = isLogging

    override fun stopTest() {
        stopLogging()
    }

    override fun onDestroyView() {
        stopLogging()
        try {
            requireContext().unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {}
        super.onDestroyView()
        _binding = null
    }
}
