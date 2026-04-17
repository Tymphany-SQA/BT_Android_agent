package com.sam.btagent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
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

class BatteryLoggingService : Service() {

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private var logRunnable: Runnable? = null
    
    private var targetDevice: BluetoothDevice? = null
    private var intervalSec: Long = 60
    private var isLogging = false
    
    private val logHistory = StringBuilder()
    private var onLogUpdateListener: ((String) -> Unit)? = null

    companion object {
        private const val CHANNEL_ID = "BatteryLoggingChannel"
        private const val NOTIFICATION_ID = 101
    }

    inner class LocalBinder : Binder() {
        fun getService(): BatteryLoggingService = this@BatteryLoggingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val filter = IntentFilter("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED") {
                val level = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                }
                
                if (device?.address == targetDevice?.address) {
                    addLog("Broadcast received: $level%")
                }
            }
        }
    }

    fun startLogging(device: BluetoothDevice, interval: Long) {
        if (isLogging) return
        
        this.targetDevice = device
        this.intervalSec = interval
        this.isLogging = true
        
        startForeground(NOTIFICATION_ID, createNotification("Battery Logging Active"))
        
        addLog("Starting Battery Log for ${device.address} (Interval: ${interval}s)")
        
        logRunnable = object : Runnable {
            override fun run() {
                if (!isLogging) return
                
                val level = readBatteryLevel(device)
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                addLog("[$time] Battery: ${if (level >= 0) "$level%" else "Unknown"}")
                
                updateNotification("Battery: ${if (level >= 0) "$level%" else "--%"}")
                handler.postDelayed(this, intervalSec * 1000)
            }
        }
        handler.post(logRunnable!!)
    }

    fun stopLogging() {
        isLogging = false
        logRunnable?.let { handler.removeCallbacks(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        addLog("Logging stopped.")
        stopSelf()
    }

    fun getFullLog(): String = logHistory.toString()

    fun setLogUpdateListener(listener: (String) -> Unit) {
        this.onLogUpdateListener = listener
    }

    fun isLoggingActive(): Boolean = isLogging

    private fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = if (message.startsWith("[")) message else "[$time] $message"
        logHistory.append(entry).append("\n")
        onLogUpdateListener?.invoke(entry + "\n")
    }

    private fun readBatteryLevel(device: BluetoothDevice): Int {
        return try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            method.invoke(device) as Int
        } catch (e: Exception) {
            -1
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
