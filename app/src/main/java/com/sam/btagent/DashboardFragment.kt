package com.sam.btagent

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
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
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.sam.btagent.databinding.FragmentDashboardBinding
import java.util.UUID
import kotlin.math.PI
import kotlin.math.roundToInt
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
    private var audioDeviceSummary = ""
    private var audioReadySummary = ""
    private var validationSummary = ""
    private var connectionHintSummary = ""
    private var a2dpPlayingSummary = ""
    private var codecSummary = "Codec info unavailable to regular app"
    private var connectionActionSummary = "No connect/disconnect action run yet."
    private var testAudioSummary = "No 10-second test audio run yet."
    
    private var activeTestAudioTrack: AudioTrack? = null
    private var activeTestAudioThread: Thread? = null
    private var selectedDeviceAddress: String? = null
    private var detailsExpanded = true
    private var lastScanBackend = "None"

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updateBluetoothSummary()
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
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
                        renderFoundDevices()
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        updateScanUI()
                    }

                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val device = parcelableBluetoothDevice(intent, BluetoothDevice.EXTRA_DEVICE)
                        if (device?.bondState == BluetoothDevice.BOND_BONDED) {
                            selectedDeviceAddress = device.address
                        }
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
                renderFoundDevices()
                updateScanUI()
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
        
        binding.startStressTestButton.setOnClickListener {
            val address = selectedDeviceAddress
            if (address != null) {
                val device = bondedDevices[address]
                (activity as? MainActivity)?.switchToStressTest(address, device?.name ?: "Unknown")
            }
        }

        val smartScrollListener = View.OnTouchListener { v, event ->
            val canScroll = v.canScrollVertically(1) || v.canScrollVertically(-1)
            if (canScroll) {
                v.parent.requestDisallowInterceptTouchEvent(true)
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
            } else {
                v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        binding.bondedListView.setOnTouchListener(smartScrollListener)
        binding.discoveryListView.setOnTouchListener(smartScrollListener)

        binding.bondedListView.setOnItemClickListener { _, _, position, _ ->
            selectedDeviceAddress = bondedDevices.keys.elementAtOrNull(position)
            setDetailsExpanded(true)
            renderDeviceDetails()
            updateBondedDeviceList()
        }

        binding.discoveryListView.setOnItemClickListener { _, _, position, _ ->
            val allFound = getFilteredFoundDevices()
            val device = allFound.elementAtOrNull(position) ?: return@setOnItemClickListener
            showPairingDialog(device)
        }

        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.RECEIVER_EXPORTED
        } else {
            0
        }

        requireContext().registerReceiver(
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
        updateDetailsVisibility()

        val versionName = try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            pInfo.versionName
        } catch (e: Exception) {
            "v0.00.01"
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
        val bonded = adapter.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }
        bondedDevices.clear()
        bonded.forEach { device ->
            bondedDevices[device.address] = device
        }

        val currentSelected = selectedDeviceAddress
        if (bonded.size == 1) {
            selectedDeviceAddress = bonded.first().address
        } else if (currentSelected == null || currentSelected !in bondedDevices) {
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
        binding.discoveryListView.visibility = View.VISIBLE
        runCatching { adapter.cancelDiscovery() }
        stopBleScan()
        
        val started = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        if (started && scanMode == ScanMode.CLASSIC_AND_BLE) {
            startBleScanFallback(parallelWithClassic = true)
        }
        updateScanUI()
    }

    private fun stopScan() {
        bluetoothAdapter()?.cancelDiscovery()
        stopBleScan()
    }

    private fun startBleScanFallback(parallelWithClassic: Boolean = false) {
        val scanner = bluetoothAdapter()?.bluetoothLeScanner ?: return
        isBleScanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(null, settings, bleScanCallback) }
        
        Handler(Looper.getMainLooper()).postDelayed({
            stopBleScan()
        }, 10_000)
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
        return (classicFoundDevices.values + bleFoundDevices.values)
            .distinctBy { it.address }
            .sortedWith(compareByDescending<BluetoothDevice> { it.bondState == BluetoothDevice.BOND_BONDING }
                .thenByDescending { 
                    val name = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) it.name else null
                    !name.isNullOrBlank()
                }
                .thenBy { if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) it.name ?: it.address else it.address })
    }

    private fun renderFoundDevices() {
        val allFound = getFilteredFoundDevices()
        val adapter = object : android.widget.ArrayAdapter<BluetoothDevice>(
            requireContext(),
            R.layout.device_list_item,
            allFound
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.device_list_item, parent, false)
                val device = getItem(position)!!
                val nameView = view.findViewById<android.widget.TextView>(R.id.deviceName)
                val addressView = view.findViewById<android.widget.TextView>(R.id.deviceAddress)
                
                nameView.text = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) device.name ?: "Unknown" else "Unknown"
                addressView.text = device.address
                return view
            }
        }
        binding.discoveryListView.adapter = adapter
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

    private fun updateBondedDeviceList() {
        val adapter = object : android.widget.ArrayAdapter<BluetoothDevice>(
            requireContext(),
            R.layout.device_list_item,
            bondedDevices.values.toList()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.device_list_item, parent, false)
                val device = getItem(position)!!
                val nameView = view.findViewById<android.widget.TextView>(R.id.deviceName)
                val addressView = view.findViewById<android.widget.TextView>(R.id.deviceAddress)

                nameView.text = device.name ?: "Unknown"
                addressView.text = device.address
                
                val isSelected = device.address == selectedDeviceAddress
                view.setBackgroundColor(if (isSelected) 0x1A000000 else 0)
                return view
            }
        }
        binding.bondedListView.adapter = adapter
    }

    private fun renderDeviceDetails() {
        val address = selectedDeviceAddress
        val device = address?.let { bondedDevices[it] }
        if (device == null) {
            binding.deviceDetailsCard.visibility = View.GONE
            return
        }
        binding.deviceDetailsCard.visibility = View.VISIBLE
        binding.detailDeviceName.text = device.name ?: "Unknown"
        binding.detailDeviceAddress.text = device.address
        
        val isA2dpConnected = a2dpSummary.contains(device.address)
        val isHfpConnected = headsetSummary.contains(device.address)
        val isAnyConnected = isA2dpConnected || isHfpConnected
        val isFullyConnected = isA2dpConnected && isHfpConnected

        binding.connectionStatusSummary.text = buildString {
            append("A2DP: ")
            append(if (isA2dpConnected) "Connected" else "Disconnected")
            append("\nHFP: ")
            append(if (isHfpConnected) "Connected" else "Disconnected")
        }

        // Update button states based on connection status
        binding.connectDeviceButton.isEnabled = !isFullyConnected
        binding.disconnectDeviceButton.isEnabled = isAnyConnected
        binding.playTestAudioButton.isEnabled = isA2dpConnected
        binding.startStressTestButton.isEnabled = true
    }

    private fun toggleDeviceDetails() {
        val visible = binding.rawDeviceInfoText.visibility == View.VISIBLE
        binding.rawDeviceInfoText.visibility = if (visible) View.GONE else View.VISIBLE
        binding.rawDeviceInfoText.text = buildString {
            append("A2DP Raw: $a2dpSummary\n")
            append("HFP Raw: $headsetSummary\n")
            append("Codec: $codecSummary")
        }
    }

    private fun setDetailsExpanded(expanded: Boolean) {
        detailsExpanded = expanded
        updateDetailsVisibility()
    }

    private fun updateDetailsVisibility() {
        binding.deviceDetailsCard.visibility = if (selectedDeviceAddress != null) View.VISIBLE else View.GONE
    }

    private fun requestDeviceConnection(connect: Boolean) {
        val device = selectedDeviceAddress?.let { bondedDevices[it] } ?: return
        requestProfileConnection(listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET), device, connect)
    }

    private fun unpairSelectedDevice() {
        val device = selectedDeviceAddress?.let { bondedDevices[it] } ?: return
        runCatching {
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device)
        }
    }

    private fun requestProfileConnection(profiles: List<Int>, device: BluetoothDevice, connect: Boolean) {
        val adapter = bluetoothAdapter() ?: return
        val context = context ?: return
        profiles.forEach { profile ->
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                    runCatching {
                        val methodName = if (connect) "connect" else "disconnect"
                        val method = proxy.javaClass.getMethod(methodName, BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    }
                    adapter.closeProfileProxy(id, proxy)
                }
                override fun onServiceDisconnected(id: Int) {}
            }, profile)
        }
        Handler(Looper.getMainLooper()).postDelayed({ 
            if (isAdded) {
                updateBluetoothSummary()
            }
        }, 2000)
    }

    private fun playTestAudio() {
        stopTestAudio()
        val thread = Thread {
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
            } catch (e: InterruptedException) {
                // Thread interrupted, exit safely
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                activeTestAudioTrack?.let {
                    try {
                        if (it.state != AudioTrack.STATE_UNINITIALIZED) {
                            it.stop()
                            it.release()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    activeTestAudioTrack = null
                }
            }
        }
        activeTestAudioThread = thread
        thread.start()
    }

    private fun stopTestAudio() {
        activeTestAudioThread?.interrupt()
        activeTestAudioThread = null
        activeTestAudioTrack?.let {
            try {
                if (it.state != AudioTrack.STATE_UNINITIALIZED) {
                    it.stop()
                    it.release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            activeTestAudioTrack = null
        }
    }

    private fun loadProfileDiagnostics() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        val adapter = bluetoothAdapter() ?: return
        val context = context ?: return
        
        listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach { profile ->
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                    val devices = proxy.connectedDevices
                    val summary = devices.joinToString { it.address }
                    if (id == BluetoothProfile.A2DP) a2dpSummary = summary else headsetSummary = summary
                    if (id == BluetoothProfile.A2DP && devices.isNotEmpty()) {
                        val a2dp = proxy as? BluetoothA2dp
                        codecSummary = readCodecSummary(a2dp, devices.first())
                    }
                    if (isAdded) {
                        renderDeviceDetails()
                    }
                    adapter.closeProfileProxy(id, proxy)
                }
                override fun onServiceDisconnected(id: Int) {}
            }, profile)
        }
    }

    private fun readCodecSummary(a2dp: BluetoothA2dp?, device: BluetoothDevice?): String {
        if (a2dp == null || device == null) return "Unknown"
        return runCatching {
            val method = a2dp.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
            val status = method.invoke(a2dp, device)
            val config = status?.javaClass?.getMethod("getCodecConfig")?.invoke(status)
            config?.toString() ?: "Unknown"
        }.getOrDefault("Unknown")
    }

    private fun requiredPermissions() = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    private fun hasPermission(p: String) = context?.let { ContextCompat.checkSelfPermission(it, p) == PackageManager.PERMISSION_GRANTED } ?: false
    private fun hasScanPermission() = hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun isLocationEnabled() = (context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isLocationEnabled ?: false
    private fun bluetoothAdapter() = (context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private fun parcelableBluetoothDevice(i: Intent?, k: String) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) i?.getParcelableExtra(k, BluetoothDevice::class.java) else i?.getParcelableExtra(k)
}
