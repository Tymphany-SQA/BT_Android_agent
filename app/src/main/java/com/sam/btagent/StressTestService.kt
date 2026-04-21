package com.sam.btagent

import android.app.*
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

class StressTestService : Service() {

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private val isRunning = AtomicBoolean(false)
    
    inner class LocalBinder : Binder() {
        fun getService(): StressTestService = this@StressTestService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Stress Test Ready")
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    fun startTest(address: String, repeats: Int, playSec: Int, pauseSec: Int) {
        if (isRunning.get()) return
        isRunning.set(true)
        
        // Acquire WakeLock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BTAgent:StressTestWakeLock")
        wakeLock?.acquire(repeats * (playSec + pauseSec * 4) * 1000L + 60000L)

        // The actual test logic would be moved here or triggered via a callback to the fragment
        // For now, we'll keep the thread in the fragment but let the service manage the lifecycle.
    }

    fun stopTest() {
        isRunning.set(false)
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bluetooth Stress Test")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_bluetooth) // Ensure this exists or use a system icon
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Stress Test Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    companion object {
        const val CHANNEL_ID = "StressTestServiceChannel"
        const val NOT_RUNNING_ID = 1
        const val NOTIFICATION_ID = 2
    }
}
