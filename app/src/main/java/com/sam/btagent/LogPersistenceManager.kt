package com.sam.btagent

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogPersistenceManager {
    private const val TAG = "LogPersistenceManager"
    private const val DIR_NAME = "BT_Android_Agent_Logs"
    private const val BUFFER_SIZE = 1000
    private val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    private val logBuffer = java.util.Collections.synchronizedList(mutableListOf<String>())

    fun getSessionId(): String = sessionId

    /**
     * Appends a log entry to a file and also maintains a circular buffer in memory.
     */
    fun persistLog(context: Context, prefix: String, message: String) {
        val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logEntry = "[$timeStamp] [$prefix] $message"
        
        // Add to circular buffer
        synchronized(logBuffer) {
            logBuffer.add(logEntry)
            if (logBuffer.size > BUFFER_SIZE) {
                logBuffer.removeAt(0)
            }
        }

        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val appLogDir = File(downloadDir, DIR_NAME)
            if (!appLogDir.exists()) {
                appLogDir.mkdirs()
            }

            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val fileName = "${prefix}_$dateStr.csv"
            val file = File(appLogDir, fileName)

            // Clean message for CSV (remove newlines, escape quotes if needed)
            val cleanMessage = message.replace("\n", " ").replace("\r", " ")
            val csvLine = "\"$timeStamp\",\"$cleanMessage\"\n"

            FileOutputStream(file, true).use {
                it.write(csvLine.toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist log: ${e.message}")
        }
    }

    /**
     * Specialized for Stress Test KPI logging to create a structured CSV
     */
    fun persistStressKPI(
        context: Context,
        loopIndex: Int,
        action: String,
        success: Boolean,
        durationMs: Long,
        glitches: Int = 0,
        recoveryMs: Long? = null
    ) {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val appLogDir = File(downloadDir, DIR_NAME)
            if (!appLogDir.exists()) appLogDir.mkdirs()

            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val fileName = "StressKPI_$dateStr.csv"
            val file = File(appLogDir, fileName)

            val isNewFile = !file.exists()
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            
            FileOutputStream(file, true).use { fos ->
                if (isNewFile) {
                    fos.write("Timestamp,SessionId,LoopIndex,Action,Result,DurationMs,Glitches,RecoveryMs\n".toByteArray())
                }
                val line = "$timeStamp,$sessionId,$loopIndex,$action,${if (success) "SUCCESS" else "FAIL"},$durationMs,$glitches,${recoveryMs ?: ""}\n"
                fos.write(line.toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist stress KPI: ${e.message}")
        }
    }

    /**
     * Specialized for Battery/RSSI logging to create a structured CSV
     */
    fun persistBatteryLog(context: Context, deviceAddress: String, level: Int, rssi: Int?) {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val appLogDir = File(downloadDir, DIR_NAME)
            if (!appLogDir.exists()) {
                appLogDir.mkdirs()
            }

            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val fileName = "BatteryLog_$dateStr.csv"
            val file = File(appLogDir, fileName)

            val isNewFile = !file.exists()
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            
            FileOutputStream(file, true).use { fos ->
                if (isNewFile) {
                    fos.write("Timestamp,DeviceAddress,BatteryLevel,RSSI\n".toByteArray())
                }
                val line = "$timeStamp,$deviceAddress,${if (level >= 0) level else ""},${rssi ?: ""}\n"
                fos.write(line.toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist battery log: ${e.message}")
        }
    }

    /**
     * Sets up a global uncaught exception handler to record crashes into a CSV file.
     */
    fun initCrashHandler(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = Log.getStackTraceString(throwable)
            val crashMessage = "FATAL EXCEPTION: [${thread.name}]\n$stackTrace"
            
            // Sync call to ensure it's written before the process dies
            persistLog(context, "CrashLog", crashMessage)
            
            // Also call original handler (shows the "App has stopped" dialog)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Saves the current log buffer to a snapshot file for error analysis.
     */
    fun saveErrorSnapshot(context: Context, errorReason: String) {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val appLogDir = File(downloadDir, DIR_NAME)
            if (!appLogDir.exists()) appLogDir.mkdirs()

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "Snapshot_ERROR_$timeStamp.csv"
            val file = File(appLogDir, fileName)

            val snapshotContent = synchronized(logBuffer) {
                logBuffer.toList()
            }

            FileOutputStream(file).use { fos ->
                fos.write("Snapshot triggered by: $errorReason\n".toByteArray())
                fos.write("--- Log Buffer Start ---\n".toByteArray())
                snapshotContent.forEach { line ->
                    fos.write("$line\n".toByteArray())
                }
                fos.write("--- Log Buffer End ---\n".toByteArray())
            }
            Log.d(TAG, "Error snapshot saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save snapshot: ${e.message}")
        }
    }

    /**
     * Specialized for Audio Clock Drift logging
     */
    fun persistDriftLog(context: Context, method: String, ppm: Double, actualRate: Double) {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val appLogDir = File(downloadDir, DIR_NAME)
            if (!appLogDir.exists()) appLogDir.mkdirs()

            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val fileName = "ClockDrift_$dateStr.csv"
            val file = File(appLogDir, fileName)

            val isNewFile = !file.exists() || file.length() == 0L
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            
            FileOutputStream(file, true).use { fos ->
                if (isNewFile) {
                    fos.write("Timestamp,Method,PPM,ActualRateHz\n".toByteArray())
                }
                val line = "$timeStamp,$method,${String.format(Locale.US, "%.2f", ppm)},${String.format(Locale.US, "%.2f", actualRate)}\n"
                fos.write(line.toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist drift log: ${e.message}")
        }
    }

    fun persistAcousticDiagResult(
        context: Context,
        mode: String,
        result: String,
        stability: String,
        leftUptime: Double,
        rightUptime: Double,
        lostCount: Int,
        leftAvgMagnitude: Double,
        rightAvgMagnitude: Double
    ) {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val appLogDir = File(downloadDir, DIR_NAME)
            if (!appLogDir.exists()) appLogDir.mkdirs()

            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val fileName = "AcousticDiag_$dateStr.csv"
            val file = File(appLogDir, fileName)
            val isNewFile = !file.exists() || file.length() == 0L
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())

            FileOutputStream(file, true).use { fos ->
                if (isNewFile) {
                    fos.write("Timestamp,SessionId,Mode,Result,Stability,LeftUptimePct,RightUptimePct,LostCount,LeftAvgMagnitude,RightAvgMagnitude\n".toByteArray())
                }
                val line = "$timeStamp,$sessionId,$mode,$result,$stability,${String.format(Locale.US, "%.1f", leftUptime)},${String.format(Locale.US, "%.1f", rightUptime)},$lostCount,${String.format(Locale.US, "%.0f", leftAvgMagnitude)},${String.format(Locale.US, "%.0f", rightAvgMagnitude)}\n"
                fos.write(line.toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist acoustic diagnostic result: ${e.message}")
        }
    }

    fun persistTestSummary(context: Context, source: String, metric: String, value: String, detail: String = "") {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val appLogDir = File(downloadDir, DIR_NAME)
            if (!appLogDir.exists()) appLogDir.mkdirs()

            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val fileName = "TestSummary_$dateStr.csv"
            val file = File(appLogDir, fileName)
            val isNewFile = !file.exists() || file.length() == 0L
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val cleanDetail = detail.replace("\n", " ").replace("\r", " ").replace("\"", "'")

            FileOutputStream(file, true).use { fos ->
                if (isNewFile) {
                    fos.write("Timestamp,SessionId,Source,Metric,Value,Detail\n".toByteArray())
                }
                fos.write("\"$timeStamp\",\"$sessionId\",\"$source\",\"$metric\",\"$value\",\"$cleanDetail\"\n".toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist test summary: ${e.message}")
        }
    }

    /**
     * 通知系統重新掃描資料夾，確保 Log Explorer 看到的是最新的檔案狀態
     */
    fun refreshLogFiles(context: Context) {
        // ... (現有代碼保持不變)
    }

    fun getResourceDir(): File {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val resDir = File(downloadDir, "$DIR_NAME/Resources")
        if (!resDir.exists()) resDir.mkdirs()
        return resDir
    }

    fun generateTestWav(context: Context, tts: TextToSpeech, text: String, fileName: String, pan: Float) {
        val file = File(getResourceDir(), fileName)
        if (file.exists() && file.length() > 1000) return // 已存在且有效則跳過

        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, pan)
        
        // synthesizeToFile 將語音直接轉為 WAV 存檔
        tts.synthesizeToFile(text, params, file, "GEN_$fileName")
        Log.d(TAG, "Generating WAV: $fileName for text: $text")
    }
}
