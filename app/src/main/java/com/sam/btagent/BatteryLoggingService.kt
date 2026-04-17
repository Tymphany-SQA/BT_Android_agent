package com.sam.btagent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class BatteryLoggingService : Service() {

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private var logRunnable: Runnable? = null
    
    private var targetDevice: BluetoothDevice? = null
    private var intervalSec: Long = 60
    private var isLogging = false
    
    private val logHistory = StringBuilder()
    private var onLogUpdateListener: ((String) -> Unit)? = null

    private var activeGatt: BluetoothGatt? = null
    private var lastKnownGattBatteryLevel: Int = -1

    companion object {
        private const val CHANNEL_ID = "BatteryLoggingChannel"
        private const val NOTIFICATION_ID = 101
        
        private val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_CHAR_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    }

    inner class LocalBinder : Binder() {
        fun getService(): BatteryLoggingService = this@BatteryLoggingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val filter = IntentFilter().apply {
            addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_FOUND)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    if (device?.address == targetDevice?.address && rssi != Short.MIN_VALUE.toInt()) {
                        lastKnownRssi = rssi
                    }
                }
                "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" -> {
                    val level = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                    if (device?.address == targetDevice?.address) {
                        addLog("Broadcast: Battery $level%")
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (device?.address == targetDevice?.address) {
                        addLog("!!! ACL Disconnected !!!")
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (device?.address == targetDevice?.address) {
                        addLog("--- ACL Connected ---")
                    }
                }
            }
        }
    }

    fun startLogging(device: BluetoothDevice, interval: Long) {
        if (isLogging) return
        
        this.targetDevice = device
        this.intervalSec = interval
        this.isLogging = true
        this.lastKnownGattBatteryLevel = -1
        
        startForeground(NOTIFICATION_ID, createNotification("Battery Logging Active"))
        
        addLog("Starting Battery Log for ${device.address} (Interval: ${interval}s)")
        
        // Initial GATT connection to try and pick up BLE battery
        tryConnectGatt(device)

        logRunnable = object : Runnable {
            override fun run() {
                if (!isLogging) return
                
                var level = readBatteryLevel(device)
                
                // Always try to refresh RSSI via GATT if connected
                activeGatt?.readRemoteRssi()
                
                // Fallback to discovery if GATT is not available
                if (activeGatt == null && hasScanPermission()) {
                    tryToGetRssi(device)
                }

                if (level < 0 && lastKnownGattBatteryLevel >= 0) {
                    level = lastKnownGattBatteryLevel
                } else if (level < 0) {
                    // If still unknown, poke GATT for battery service
                    refreshGattBattery()
                }
                
                val rssi = lastKnownRssi
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val rssiStr = rssi?.let { " | RSSI: ${it}dBm" } ?: ""
                addLog("[$time] Battery: ${if (level >= 0) "$level%" else "Unknown"}$rssiStr")
                
                updateNotification("Battery: ${if (level >= 0) "$level%" else "--%"} $rssiStr")
                handler.postDelayed(this, intervalSec * 1000)
            }
        }
        handler.post(logRunnable!!)
    }

    fun stopLogging() {
        isLogging = false
        logRunnable?.let { handler.removeCallbacks(it) }
        activeGatt?.disconnect()
        activeGatt?.close()
        activeGatt = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        addLog("Logging stopped.")
        stopSelf()
    }

    fun getFullLog(): String = logHistory.toString()

    fun setLogUpdateListener(listener: (String) -> Unit) {
        this.onLogUpdateListener = listener
    }

    fun getLastKnownRssi(): Int? = lastKnownRssi

    fun isLoggingActive(): Boolean = isLogging

    private fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = if (message.startsWith("[")) message else "[$time] $message"
        logHistory.append(entry).append("\n")
        onLogUpdateListener?.invoke(entry + "\n")
    }

    private fun readBatteryLevel(device: BluetoothDevice): Int {
        val classicLevel = try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            method.invoke(device) as Int
        } catch (e: Exception) {
            -1
        }
        return if (classicLevel >= 0) classicLevel else lastKnownGattBatteryLevel
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (hasScanPermission()) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // If disconnected, we might want to retry later in next loop
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                refreshGattBattery()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL_CHAR_UUID) {
                @Suppress("DEPRECATION")
                val value = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                lastKnownGattBatteryLevel = value
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                lastKnownRssi = rssi
            }
        }
    }

    private fun tryConnectGatt(device: BluetoothDevice) {
        if (!hasScanPermission()) return
        activeGatt?.close()
        activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_AUTO)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    private fun refreshGattBattery() {
        val service = activeGatt?.getService(BATTERY_SERVICE_UUID)
        val char = service?.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)
        if (char != null && hasScanPermission()) {
            activeGatt?.readCharacteristic(char)
        }
    }

    private var lastKnownRssi: Int? = null
    
    // We use a temporary discovery to peek at the RSSI of the connected device
    private fun tryToGetRssi(target: BluetoothDevice): Int? {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter != null && !adapter.isDiscovering) {
            runCatching { adapter.startDiscovery() }
            // Discovery takes a few seconds to report ACTION_FOUND.
            // We'll catch it in the receiver and use it in the next interval.
        }
        return lastKnownRssi 
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Logging Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BT Battery Monitor")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }

    override fun onDestroy() {
        unregisterReceiver(bluetoothReceiver)
        super.onDestroy()
    }
}
