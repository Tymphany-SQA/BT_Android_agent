package com.sam.btagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import com.google.android.material.navigation.NavigationView
import com.sam.btagent.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var drawerToggle: ActionBarDrawerToggle

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(this, "Permissions required for full functionality.", Toast.LENGTH_LONG).show()
        }
    }

    interface TestStatusProvider {
        fun isTestRunning(): Boolean
        fun stopTest()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize global crash handler for log persistence
        LogPersistenceManager.initCrashHandler(applicationContext)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup Toolbar
        setSupportActionBar(binding.toolbar)

        // Setup Drawer Toggle (Hamburger Icon)
        drawerToggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.app_name, // You might want to add proper accessibility strings later
            R.string.app_name
        )
        binding.drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        // Setup Navigation View Listener
        binding.navView.setNavigationItemSelectedListener(this)

        updateNavHeaderVersion()
        checkPermissions()

        if (savedInstanceState == null) {
            // Default to Dashboard
            navigateToItem(R.id.nav_dashboard)
            binding.navView.setCheckedItem(R.id.nav_dashboard)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)

        if (currentFragment is TestStatusProvider && currentFragment.isTestRunning()) {
            showStopTestDialog(item.itemId)
        } else {
            navigateToItem(item.itemId)
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        
        // Bluetooth Permissions (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Notification Permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun navigateToItem(itemId: Int) {
        val fragment: Fragment = when (itemId) {
            R.id.nav_dashboard -> DashboardFragment()
            R.id.nav_stress_test -> StressTestFragment()
            R.id.nav_media_control -> MediaControlStressFragment()
            R.id.nav_hfp_stress -> HfpStressFragment()
            R.id.nav_battery_monitor -> BatteryMonitorFragment()
            R.id.nav_acoustic_loopback -> AcousticLoopbackFragment()
            R.id.nav_volume_linearity -> VolumeLinearityFragment()
            R.id.nav_audio_latency -> AudioLatencyFragment()
            R.id.nav_clock_drift -> AudioClockDriftFragment()
            R.id.nav_log_viewer -> LogViewerFragment()
            R.id.nav_about -> AboutFragment()
            else -> DashboardFragment()
        }

        val title = when (itemId) {
            R.id.nav_dashboard -> "Dashboard"
            R.id.nav_stress_test -> "Stress Test"
            R.id.nav_media_control -> "Media Control"
            R.id.nav_hfp_stress -> "HFP / SCO Stress"
            R.id.nav_battery_monitor -> "Stability Monitor"
            R.id.nav_acoustic_loopback -> "Acoustic Loopback"
            R.id.nav_volume_linearity -> "Volume Linearity"
            R.id.nav_audio_latency -> "Audio Latency"
            R.id.nav_log_viewer -> "Log Explorer"
            R.id.nav_about -> "About"
            else -> "BT Agent"
        }

        supportActionBar?.title = title
        replaceFragment(fragment)
    }

    private fun showStopTestDialog(targetItemId: Int) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.stop_test_title)
            .setMessage(R.string.stop_test_message)
            .setPositiveButton(R.string.stop_and_switch) { _, _ ->
                val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                (currentFragment as? TestStatusProvider)?.stopTest()
                binding.navView.setCheckedItem(targetItemId)
                navigateToItem(targetItemId)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment, fragment)
            .commit()
    }

    private fun updateNavHeaderVersion() {
        try {
            val headerView = binding.navView.getHeaderView(0)
            val titleView = headerView.findViewById<android.widget.TextView>(R.id.navHeaderTitle)
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            titleView.text = "BT Agent 2.0 (v$versionName)"
        } catch (e: Exception) {
            // Fallback
        }
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
