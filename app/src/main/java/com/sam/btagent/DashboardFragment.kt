package com.sam.btagent

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
// 移除編譯時可能衝突的隱藏 API 導入
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.sam.btagent.databinding.FragmentDashboardBinding
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

class DashboardFragment : Fragment() {
    private enum class ScanMode {
        CLASSIC_ONLY,
        CLASSIC_AND_BLE,
    }

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    
    private val classicFoundDevices = linkedMapOf<String, BluetoothDevice>()
    private val bleFoundDevices = linkedMapOf<String, BluetoothDevice>()
    private val bondedDevices = linkedMapOf<String, BluetoothDevice>()
    private var isBleScanning = false
    private var a2dpSummary = ""
    private var headsetSummary = ""
    private var codecSummary = ""
    private var selectedDeviceAddress: String? = null
    private var scanTimer: CountDownTimer? = null
    private val SCAN_DURATION_MS = 12_800L

    private lateinit var discoveryAdapter: DeviceAdapter
    private lateinit var bondedAdapter: DeviceAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = Runnable { renderFoundDevices() }
    private var lastUpdateTime = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updateBluetoothSummary()
        }

    private val discoveryReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                        updateScanUI()
                    }
                    BluetoothDevice.ACTION_FOUND,
                    BluetoothDevice.ACTION_NAME_CHANGED -> {
                        val device = parcelableBluetoothDevice(intent, BluetoothDevice.EXTRA_DEVICE)
                        val address = device?.address ?: return
                        classicFoundDevices[address] = device
                        requestRenderFoundDevices()
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        updateScanUI()
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        updateBluetoothSummary()
                        renderFoundDevices()
                    }
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                        updateBluetoothSummary()
                    }
                }
            }
        }

    private val bleScanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address ?: return
                bleFoundDevices[address] = result.device
                requestRenderFoundDevices()
            }
            override fun onScanFailed(errorCode: Int) {
                isBleScanning = false
                updateScanUI()
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.scanButton.setOnClickListener { startScan(ScanMode.CLASSIC_AND_BLE) }
        binding.stopScanButton.setOnClickListener { stopScan() }

        binding.connectDeviceButton.setOnClickListener { requestDeviceConnection(connect = true) }
        binding.disconnectDeviceButton.setOnClickListener { requestDeviceConnection(connect = false) }
        binding.unpairDeviceButton.setOnClickListener { unpairSelectedDevice() }
        binding.playTestAudioButton.setOnClickListener { playTestAudio() }
        binding.toggleDetailsButton.setOnClickListener { toggleDeviceDetails() }

        // Setup RecyclerViews
        discoveryAdapter = DeviceAdapter { device ->
            showPairingDialog(device)
        }
        binding.discoveryRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.discoveryRecyclerView.adapter = discoveryAdapter

        bondedAdapter = DeviceAdapter { device ->
            selectedDeviceAddress = device.address
            renderDeviceDetails()
            updateBondedDeviceList()
        }
        binding.bondedRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.bondedRecyclerView.adapter = bondedAdapter

        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.RECEIVER_EXPORTED
        } else {
            0
        }

        ContextCompat.registerReceiver(
            requireContext(),
            discoveryReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            },
            receiverFlags,
        )

        updateBluetoothSummary()
        requestBluetoothPermissions()

        val versionName = try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            pInfo.versionName
        } catch (e: Exception) {
            "0.00.07"
        }
        binding.versionNumberText.text = "v$versionName"
    }

    override fun onResume() {
        super.onResume()
        updateBluetoothSummary()
    }

    override fun onDestroyView() {
        runCatching { requireContext().unregisterReceiver(discoveryReceiver) }
        bluetoothAdapter()?.cancelDiscovery()
        stopBleScan()
        scanTimer?.cancel()
        stopTestAudio()
        super.onDestroyView()
        _binding = null
    }

    private fun updateBluetoothSummary() {
        if (!isAdded) return
        bondedDeviceSummary()
        loadProfileDiagnostics()
        renderDeviceDetails()
    }

    private fun bondedDeviceSummary() {
        val adapter = bluetoothAdapter() ?: return
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            bondedDevices.clear()
            updateBondedDeviceList()
            return
        }

        val bonded = try {
            adapter.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }
        } catch (e: SecurityException) {
            emptyList()
        }
        
        bondedDevices.clear()
        bonded.forEach { device ->
            bondedDevices[device.address] = device
        }

        val currentSelected = selectedDeviceAddress
        if (currentSelected == null || !bondedDevices.containsKey(currentSelected)) {
            selectedDeviceAddress = bonded.firstOrNull()?.address
        }
        updateBondedDeviceList()
    }

    private fun requestBluetoothPermissions() {
        val missing = requiredPermissions().filterNot(::hasPermission)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startScan(scanMode: ScanMode) {
        val adapter = bluetoothAdapter() ?: return
        if (!hasScanPermission()) {
            requestBluetoothPermissions()
            return
        }
        if (!isLocationEnabled()) return
        
        classicFoundDevices.clear()
        bleFoundDevices.clear()
        renderFoundDevices()
        binding.discoveryRecyclerView.visibility = View.VISIBLE
        runCatching { adapter.cancelDiscovery() }
        stopBleScan()
        
        val started = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        if (started) {
            if (scanMode == ScanMode.CLASSIC_AND_BLE) {
                startBleScanFallback()
            }
            startCountdownTimer()
        }
        updateScanUI()
    }

    private fun startCountdownTimer() {
        scanTimer?.cancel()
        binding.scanCountdownText.visibility = View.VISIBLE
        scanTimer = object : CountDownTimer(SCAN_DURATION_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                binding.scanCountdownText.text = "(Scanning... ${seconds}s)"
            }
            override fun onFinish() {
                binding.scanCountdownText.visibility = View.GONE
                stopScan()
            }
        }.start()
    }

    private fun stopScan() {
        bluetoothAdapter()?.cancelDiscovery()
        stopBleScan()
        scanTimer?.cancel()
        binding.scanCountdownText.visibility = View.GONE
        updateScanUI()
    }

    private fun startBleScanFallback() {
        val scanner = bluetoothAdapter()?.bluetoothLeScanner ?: return
        isBleScanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(null, settings, bleScanCallback) }
    }

    private fun stopBleScan() {
        if (!isBleScanning) return
        val scanner = bluetoothAdapter()?.bluetoothLeScanner ?: return
        runCatching { scanner.stopScan(bleScanCallback) }
        isBleScanning = false
        updateScanUI()
    }

    private fun updateScanUI() {
        val isAnyScanning = (bluetoothAdapter()?.isDiscovering ?: false) || isBleScanning
        binding.scanButton.isEnabled = !isAnyScanning
        binding.stopScanButton.isEnabled = isAnyScanning
    }

    private fun getFilteredFoundDevices(): List<BluetoothDevice> {
        // Create a copy to avoid ConcurrentModificationException
        val classic = synchronized(classicFoundDevices) { classicFoundDevices.values.toList() }
        val ble = synchronized(bleFoundDevices) { bleFoundDevices.values.toList() }

        return (classic + ble)
            .distinctBy { it.address }
            .sortedWith(compareByDescending<BluetoothDevice> { it.bondState == BluetoothDevice.BOND_BONDED }
                .thenByDescending { it.name != null }
                .thenBy { it.name ?: it.address }
            )
    }

    private fun requestRenderFoundDevices() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime > 800) { // Throttle: Max update frequency once per 800ms
            renderFoundDevices()
            lastUpdateTime = now
        } else {
            mainHandler.removeCallbacks(updateRunnable)
            mainHandler.postDelayed(updateRunnable, 800)
        }
    }

    private fun renderFoundDevices() {
        if (_binding == null) return
        discoveryAdapter.submitList(getFilteredFoundDevices())
    }

    private fun updateBondedDeviceList() {
        bondedAdapter.submitList(bondedDevices.values.toList())
    }

    private fun showPairingDialog(device: BluetoothDevice) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Pair Device")
            .setMessage("Pair with ${device.address}?")
            .setPositiveButton("Pair") { _, _ ->
                if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    device.createBond()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderDeviceDetails() {
        val device = selectedDeviceAddress?.let { bondedDevices[it] }
        if (device == null) {
            binding.deviceDetailsCard.visibility = View.GONE
            return
        }
        binding.deviceDetailsCard.visibility = View.VISIBLE

        val deviceName = try {
            if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) device.name else "Permission Denied"
        } catch (e: SecurityException) {
            "Unknown"
        }

        binding.detailDeviceName.text = deviceName ?: "Unknown"
        binding.detailDeviceAddress.text = device.address

        val isA2dpConnected = a2dpSummary.contains(device.address)
        val isHfpConnected = headsetSummary.contains(device.address)

        binding.connectionStatusSummary.text = "A2DP: ${if (isA2dpConnected) "Connected" else "Disconnected"}\nHFP: ${if (isHfpConnected) "Connected" else "Disconnected"}"
        binding.connectDeviceButton.isEnabled = !(isA2dpConnected && isHfpConnected)
        binding.disconnectDeviceButton.isEnabled = isA2dpConnected || isHfpConnected
        binding.playTestAudioButton.isEnabled = isA2dpConnected
    }

    private fun toggleDeviceDetails() {
        val visible = binding.rawDeviceInfoText.visibility == View.VISIBLE
        binding.rawDeviceInfoText.visibility = if (visible) View.GONE else View.VISIBLE
        binding.rawDeviceInfoText.text = "Codec: $codecSummary\nA2DP Raw: $a2dpSummary\nHFP Raw: $headsetSummary"
    }

    private fun requestDeviceConnection(connect: Boolean) {
        val device = selectedDeviceAddress?.let { bondedDevices[it] } ?: return
        val adapter = bluetoothAdapter() ?: return
        val context = context ?: return

        val action = if (connect) "Connect" else "Disconnect"
        android.util.Log.d("BTAgent", "User requested $action for ${device.address}")
        LogPersistenceManager.persistLog(context.applicationContext, "Dashboard", "Action: $action Device: ${device.address}")

        if (connect) {
            adapter.cancelDiscovery()
            stopBleScan()
        }

        Thread {
            try {
                if (!connect) {
                    // 斷開前確保音訊完全釋放
                    stopTestAudio()
                    Thread.sleep(800) // 等待 A2DP 頻道徹底釋放
                }

                // 斷開順序：HFP -> A2DP (反向更穩定)
                // 連線順序：A2DP -> HFP
                val profileOrder = if (connect) {
                    listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
                } else {
                    listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)
                }

                performProfileActionSequentially(profileOrder, device, connect)

                if (connect) {
                    try {
                        val method = device.javaClass.getMethod("connect")
                        method.isAccessible = true
                        method.invoke(device)
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                android.util.Log.e("BTAgent", "Connection thread error: ${e.message}")
            }
        }.start()

        Toast.makeText(context, "Initiating $action...", Toast.LENGTH_SHORT).show()
    }

    private fun performProfileActionSequentially(profiles: List<Int>, device: BluetoothDevice, connect: Boolean) {
        val adapter = bluetoothAdapter() ?: return
        val context = context ?: return

        // 關鍵：使用遠端地址重新獲取裝置物件，確保與系統狀態機同步
        val remoteDevice = adapter.getRemoteDevice(device.address)

        profiles.forEach { profileId ->
            var actionFinished = false
            val pName = if (profileId == BluetoothProfile.A2DP) "A2DP" else "HFP"

            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                    Thread {
                        try {
                            // 1. Set Policy/Priority (Same as before)
                            try {
                                val setPolicy = proxy.javaClass.methods.find { it.name == "setConnectionPolicy" || it.name == "setPriority" }
                                setPolicy?.isAccessible = true
                                if (setPolicy?.parameterTypes?.size == 2) {
                                    setPolicy.invoke(proxy, remoteDevice, 100)
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("BTAgent", "SetPolicy failed: ${e.message}")
                            }

                            // 2. Action (Connect/Disconnect)
                            val methodName = if (connect) "connect" else "disconnect"
                            // 強化查找：從所有 public/private 方法中找尋，不論層級
                            val method = proxy.javaClass.methods.find {
                                it.name == methodName && it.parameterTypes.size == 1 && it.parameterTypes[0] == BluetoothDevice::class.java
                            } ?: proxy.javaClass.getDeclaredMethod(methodName, BluetoothDevice::class.java)

                            method.isAccessible = true
                            val result = method.invoke(proxy, remoteDevice) as? Boolean ?: false

                            android.util.Log.d("BTAgent", "$pName $methodName invoked, result: $result")
                            LogPersistenceManager.persistLog(context.applicationContext, "Dashboard", "$pName $methodName call: $result")

                            // 3. Polling for state change
                            val targetState = if (connect) BluetoothProfile.STATE_CONNECTED else BluetoothProfile.STATE_DISCONNECTED
                            val start = System.currentTimeMillis()
                            var reached = false
                            while (System.currentTimeMillis() - start < 6000) {
                                if (proxy.getConnectionState(remoteDevice) == targetState) {
                                    reached = true
                                    break
                                }
                                Thread.sleep(400)
                            }
                            LogPersistenceManager.persistLog(context.applicationContext, "Dashboard", "$pName $methodName status: ${if(reached) "Success" else "Timeout"}")

                        } catch (e: Exception) {
                            android.util.Log.e("BTAgent", "$pName execution failed: ${e.message}")
                            LogPersistenceManager.persistLog(context.applicationContext, "Dashboard", "$pName error: ${e.message}")
                        } finally {
                            adapter.closeProfileProxy(id, proxy)
                            actionFinished = true
                        }
                    }.start()
                }
                override fun onServiceDisconnected(id: Int) { actionFinished = true }
            }, profileId)

            val waitStart = System.currentTimeMillis()
            while (!actionFinished && System.currentTimeMillis() - waitStart < 8000) {
                Thread.sleep(100)
            }
        }

        mainHandler.post {
            if (isAdded) {
                updateBluetoothSummary()
                val finalAction = if (connect) "Connect" else "Disconnect"
                Toast.makeText(context, "$finalAction complete", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun unpairSelectedDevice() {
        val device = selectedDeviceAddress?.let { bondedDevices[it] } ?: return
        runCatching {
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device)
        }
    }

    private var activeTestAudioTrack: AudioTrack? = null
    private var activeTestAudioThread: Thread? = null

    private fun playTestAudio() {
        stopTestAudio()
        activeTestAudioThread = Thread {
            try {
                val sampleRate = 44_100
                val durationSeconds = 10
                val pcm = ShortArray(sampleRate * durationSeconds) { (sin(2.0 * PI * 440.0 * it / sampleRate) * Short.MAX_VALUE * 0.5).toInt().toShort() }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                activeTestAudioTrack = track
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(durationSeconds * 1000L)
            } catch (e: Exception) {} finally { stopTestAudio() }
        }
        activeTestAudioThread?.start()
    }

    private fun stopTestAudio() {
        android.util.Log.d("BTAgent", "Stopping test audio...")
        activeTestAudioThread?.interrupt()
        activeTestAudioTrack?.apply {
            try {
                if (state != AudioTrack.STATE_UNINITIALIZED) {
                    pause()
                    flush()
                    stop()
                    release()
                }
            } catch (e: Exception) {
                android.util.Log.e("BTAgent", "Audio release error: ${e.message}")
            }
        }
        activeTestAudioTrack = null
        activeTestAudioThread = null
    }

    private fun loadProfileDiagnostics() {
        val adapter = bluetoothAdapter() ?: return
        val context = context ?: return

        listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach { profile ->
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                    val connectedDevices = proxy.connectedDevices
                    val summary = if (connectedDevices.isEmpty()) "None" else {
                        connectedDevices.joinToString("\n") { device ->
                            val policy = try {
                                val getPolicy = proxy.javaClass.methods.find { it.name == "getConnectionPolicy" || it.name == "getPriority" }
                                getPolicy?.isAccessible = true
                                val pValue = getPolicy?.invoke(proxy, device) as? Int ?: -1
                                when (pValue) {
                                    100 -> "ALLOWED"
                                    0 -> "FORBIDDEN"
                                    -1 -> "UNKNOWN"
                                    else -> "P:$pValue"
                                }
                            } catch (e: Exception) { "N/A" }

                            val deviceName = try {
                                if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) device.name else "N/A"
                            } catch (e: Exception) { "Unknown" }

                            "• ${deviceName ?: "Unknown"} [${device.address}] (Policy: $policy)"
                        }
                    }

                    if (id == BluetoothProfile.A2DP) {
                        a2dpSummary = summary
                        // Attempt to get Codec Info (Safe Reflection for API 31+)
                        if (proxy is BluetoothA2dp) {
                            try {
                                val codecStatus = try {
                                    // Try hidden method (API 31/32)
                                    val getCodecStatus = proxy.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
                                    val activeDevice = proxy.javaClass.getMethod("getActiveDevice").invoke(proxy) as? BluetoothDevice
                                    if (activeDevice != null) getCodecStatus.invoke(proxy, activeDevice) else null
                                } catch (e: Exception) {
                                    // Fallback to searching for ANY device codec status
                                    val getCodecStatus = proxy.javaClass.methods.find { it.name == "getCodecStatus" }
                                    val devices = proxy.connectedDevices
                                    if (getCodecStatus != null && devices.isNotEmpty()) {
                                        getCodecStatus.invoke(proxy, devices[0])
                                    } else null
                                }

                                codecSummary = codecStatus?.let { status ->
                                    val config = try {
                                        status.javaClass.getMethod("getCodecConfig").invoke(status)
                                    } catch (e: Exception) { null }

                                    config?.let { cfg ->
                                        val cType = try {
                                            val m = cfg.javaClass.methods.find { it.name == "getCodecType" }
                                            m?.invoke(cfg) as? Int ?: -1
                                        } catch (e: Exception) { -1 }

                                        val sRate = try {
                                            val m = cfg.javaClass.methods.find { it.name == "getSampleRate" }
                                            m?.invoke(cfg) as? Int ?: -1
                                        } catch (e: Exception) { -1 }

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
                                    }
                                } ?: "Inactive"
                            } catch (e: Exception) {
                                codecSummary = "N/A (Reflection Error)"
                            }
                        }
                    } else {
                        headsetSummary = summary
                    }

                    mainHandler.post { if (isAdded) renderDeviceDetails() }
                    adapter.closeProfileProxy(id, proxy)
                }
                override fun onServiceDisconnected(id: Int) {}
            }, profile)
        }
    }

    private fun requiredPermissions() = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    private fun hasPermission(p: String) = context?.let { ContextCompat.checkSelfPermission(it, p) == PackageManager.PERMISSION_GRANTED } ?: false
    private fun hasScanPermission() = hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun isLocationEnabled() = (context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isLocationEnabled ?: false
    private fun bluetoothAdapter() = (context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private fun parcelableBluetoothDevice(i: Intent?, k: String) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) i?.getParcelableExtra(k, BluetoothDevice::class.java) else i?.getParcelableExtra(k)
}
