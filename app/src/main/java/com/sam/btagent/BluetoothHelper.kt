package com.sam.btagent

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object BluetoothHelper {

    fun addLog(tag: String, message: String) {
        android.util.Log.d("BTAgent_$tag", message)
    }

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun getCodecInfo(proxy: BluetoothProfile, device: BluetoothDevice?): String {
        if (proxy !is BluetoothA2dp || device == null) return "N/A"
        
        return try {
            val getCodecStatus = proxy.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
            val codecStatus = getCodecStatus.invoke(proxy, device)
            
            val getCodecConfig = codecStatus?.javaClass?.getMethod("getCodecConfig")
            val config = getCodecConfig?.invoke(codecStatus)
            
            if (config != null) {
                val cType = config.javaClass.methods.find { it.name == "getCodecType" }?.invoke(config) as? Int ?: -1
                val sRate = config.javaClass.methods.find { it.name == "getSampleRate" }?.invoke(config) as? Int ?: -1
                
                val type = when(cType) {
                    0 -> "SBC"
                    1 -> "AAC"
                    2 -> "aptX"
                    3 -> "aptX HD"
                    4 -> "LDAC"
                    11 -> "LC3"
                    else -> "Type($cType)"
                }
                val rate = when(sRate) {
                    0x1 -> "44.1k"
                    0x2 -> "48k"
                    0x4 -> "88.2k"
                    0x8 -> "96k"
                    else -> "SR($sRate)"
                }
                "$type @ $rate"
            } else {
                "Unknown Config"
            }
        } catch (e: Exception) {
            "Reflection Error"
        }
    }
}
