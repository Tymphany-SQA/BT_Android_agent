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
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class BatteryLoggingService : Service() {

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private var logRunnable: Runnable? = null
    
    private var targetDevice: BluetoothDevice? = null
    private var intervalSec: Long = 60
    private var isLogging = false
    private var enableAudioMonitor = true
    private var silentMode = true
    private var bufferStrategy = 0 
    
    private val logHistory = StringBuilder()
    private var onLogUpdateListener: ((String) -> Unit)? = null

    private var activeGatt: BluetoothGatt? = null
    private var lastKnownGattBatteryLevel: Int = -1
    private var lastKnownRssi: Int? = null

    private var audioTrack: AudioTrack? = null
    private var lastUnderrunCount = 0
    private var totalGlitches = 0
    private val TAG = "BatteryLoggingService"

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
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        registerReceiver(bluetoothReceiver, filter)
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
                        addLog("Event: Battery Update $level%")
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (device?.address == targetDevice?.address) {
                        addLog("!!! ACL Disconnected (Device Power Off/Out of Range) !!!")
                        lastKnownRssi = null
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (device?.address == targetDevice?.address) {
                        addLog("--- ACL Connected ---")
                        device?.let { tryConnectGatt(it) }
                    }
                }
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    addLog("Warning: Audio output redirected (Bluetooth lost?)")
                }
            }
        }
    }

    fun startLogging(device: BluetoothDevice, interval: Long, enableAudio: Boolean, silent: Boolean, strategy: Int) {
        if (isLogging) return
        this.targetDevice = device
        this.intervalSec = interval
        this.enableAudioMonitor = enableAudio
        this.silentMode = silent
        this.bufferStrategy = strategy
        this.isLogging = true
        this.totalGlitches = 0
        this.lastUnderrunCount = 0
        
        startForeground(NOTIFICATION_ID, createNotification("Stability Monitoring Active"))
        addLog("Starting Stability Monitor for ${device.address}")
        
        if (enableAudio) {
            startBackgroundAudio()
        }

        tryConnectGatt(device)

        logRunnable = object : Runnable {
            override fun run() {
                if (!isLogging) return
                
                val level = readBatteryLevel(device)
                val currentIntervalGlitches = if (enableAudioMonitor) checkAudioGlitches() else 0
                totalGlitches += currentIntervalGlitches
                
                activeGatt?.readRemoteRssi()
                
                val route = getAudioRoute()
                addLog("Status: Bat ${if (level >= 0) "$level%" else "--%"} | Route: $route")
                
                updateNotification("Bat: ${if (level >= 0) "$level%" else "--%"} | RSSI: ${lastKnownRssi ?: "--"} | Glit: $totalGlitches")
                persistData(device.address, level, lastKnownRssi, currentIntervalGlitches)
                
                handler.postDelayed(this, intervalSec * 1000)
            }
        }
        handler.post(logRunnable!!)
    }

    private fun getAudioRoute(): String {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return "BT_A2DP"
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return "BT_SCO"
        }
        return "INTERNAL_SPEAKER"
    }

    private fun startBackgroundAudio() {
        try {
            val sampleRate = 44100
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            
            // 優化 Buffer 策略
            // 0: Standard - 使用系統最小值，靈敏偵測傳輸抖動
            // 1: Stable - 使用 4 倍緩衝，模擬音樂 App 行為
            val bufferSize = when (bufferStrategy) {
                1 -> minBufferSize * 4 
                else -> minBufferSize
            }

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val samples = ShortArray(1024)
            audioTrack?.play()
            
            Thread {
                while (isLogging && audioTrack != null) {
                    if (silentMode) {
                        // 使用極低振幅雜訊避免部分系統進入 Deep Sleep
                        for (i in samples.indices) samples[i] = (Random.nextInt(3) - 1).toShort()
                    } else {
                        // 440Hz 提示音
                        for (i in samples.indices) {
                            samples[i] = (sin(2 * PI * 440.0 * (i.toDouble() / sampleRate)) * 500).toInt().toShort()
                        }
                    }
                    try {
                        val state = audioTrack?.playState
                        if (state == AudioTrack.PLAYSTATE_PLAYING) {
                            audioTrack?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        }
                    } catch (e: Exception) { break }
                }
            }.start()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                lastUnderrunCount = audioTrack?.underrunCount ?: 0
            }
            addLog("Audio Monitor started. Buffer: $bufferSize bytes (Strategy: ${if(bufferStrategy==1) "Stable" else "Standard"})")
        } catch (e: Exception) {
            addLog("Audio Monitor Error: ${e.message}")
        }
    }

    private fun checkAudioGlitches(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val track = audioTrack ?: return 0
            val current = try { track.underrunCount } catch (e: Exception) { 0 }
            if (current < lastUnderrunCount) lastUnderrunCount = 0
            val diff = current - lastUnderrunCount
            lastUnderrunCount = current
            return if (diff > 0) diff else 0
        }
        return 0
    }

    private fun persistData(address: String, level: Int, rssi: Int?, glitches: Int) {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val appLogDir = File(downloadDir, "BT_Android_Agent_Logs")
            if (!appLogDir.exists()) appLogDir.mkdirs()

            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val file = File(appLogDir, "StabilityLog_$dateStr.csv")

            val isNewFile = !file.exists() || file.length() == 0L
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val route = getAudioRoute()
            
            FileOutputStream(file, true).use { fos ->
                if (isNewFile) {
                    fos.write("Timestamp,DeviceAddress,Battery,RSSI,Glitches,Route\n".toByteArray())
                }
                val line = "$timeStamp,$address,${if (level >= 0) level else ""},${rssi ?: ""},$glitches,$route\n"
                fos.write(line.toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "CSV Error: ${e.message}")
        }
    }

    fun stopLogging() {
        isLogging = false
        logRunnable?.let { handler.removeCallbacks(it) }
        audioTrack?.let { try { it.stop(); it.release() } catch (e: Exception) {} }
        audioTrack = null
        activeGatt?.close()
        activeGatt = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        addLog("Logging stopped.")
        stopSelf()
    }

    fun isLoggingActive() = isLogging
    fun getFullLog() = logHistory.toString()
    fun getLastKnownRssi() = lastKnownRssi
    fun getTotalGlitches() = totalGlitches
    fun clearHistory() { logHistory.setLength(0) }
    fun setLogUpdateListener(listener: ((String) -> Unit)?) { this.onLogUpdateListener = listener }


    private fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val rssi = lastKnownRssi
        // 確保 addLog 時也能觸發最新 Glitch 檢查
        if (enableAudioMonitor) totalGlitches += checkAudioGlitches()
        
        val rssiStr = if (rssi != null) " | RSSI: ${rssi}dBm" else " | RSSI: --dBm"
        val glitchStr = " | Glitches: $totalGlitches (Phone buffer)"
        
        val cleanMessage = if (message.contains("Status:") || message.contains("Event:")) {
            message.substringBefore(" | RSSI").substringBefore(" | Glitches")
        } else message
        
        val fullMessage = "$cleanMessage$rssiStr$glitchStr"
        val entry = "[$time] $fullMessage"
        
        logHistory.append(entry).append("\n")
        onLogUpdateListener?.invoke(entry + "\n")
    }

    private fun readBatteryLevel(device: BluetoothDevice): Int {
        val classicLevel = try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            method.invoke(device) as Int
        } catch (e: Exception) { -1 }
        return if (classicLevel >= 0) classicLevel else lastKnownGattBatteryLevel
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) lastKnownRssi = null
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) refreshGattBattery()
        }
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL_CHAR_UUID) {
                lastKnownGattBatteryLevel = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
            }
        }
        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) lastKnownRssi = rssi
        }
    }

    private fun tryConnectGatt(device: BluetoothDevice) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        activeGatt?.close()
        activeGatt = device.connectGatt(this, false, gattCallback)
    }

    private fun refreshGattBattery() {
        val service = activeGatt?.getService(BATTERY_SERVICE_UUID)
        val char = service?.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)
        if (char != null && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            activeGatt?.readCharacteristic(char)
        }
    }

    private fun tryToGetRssi(target: BluetoothDevice) {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter != null && !adapter.isDiscovering && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            runCatching { adapter.startDiscovery() }
        }
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
            val channel = NotificationChannel(CHANNEL_ID, "Stability Monitor Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Stability Monitor")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true).build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }

    override fun onDestroy() {
        try { unregisterReceiver(bluetoothReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }
}
