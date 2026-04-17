package com.sam.btagent

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.sam.btagent.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    interface TestStatusProvider {
        fun isTestRunning(): Boolean
        fun stopTest()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, DashboardFragment())
                .commit()
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
            
            if (currentFragment is TestStatusProvider && currentFragment.isTestRunning()) {
                showStopTestDialog(item.itemId)
                false // Don't switch yet
            } else {
                performNavigation(item.itemId)
                true
            }
        }
    }

    private fun showStopTestDialog(targetItemId: Int) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.stop_test_title)
            .setMessage(R.string.stop_test_message)
            .setPositiveButton(R.string.stop_and_switch) { _, _ ->
                val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                (currentFragment as? TestStatusProvider)?.stopTest()
                binding.bottomNavigation.selectedItemId = targetItemId
                performNavigation(targetItemId)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performNavigation(itemId: Int) {
        when (itemId) {
            R.id.nav_dashboard -> replaceFragment(DashboardFragment())
            R.id.nav_stress_test -> replaceFragment(StressTestFragment())
            R.id.nav_media_control -> replaceFragment(MediaControlStressFragment())
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment, fragment)
            .commit()
    }

    fun switchToStressTest(address: String, name: String) {
        val fragment = StressTestFragment.newInstance(address, name)
        binding.bottomNavigation.selectedItemId = R.id.nav_stress_test
        replaceFragment(fragment)
    }
}
