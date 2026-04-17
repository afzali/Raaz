package io.raaz.messenger.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import io.raaz.messenger.R
import io.raaz.messenger.databinding.ActivityMainBinding
import io.raaz.messenger.util.LocaleManager
import io.raaz.messenger.util.SessionLockManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — no action needed */ }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Notification permission is handled in SettingsFragment — not requested here

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Observe session lock — navigate to lock screen whenever locked
        SessionLockManager.isLocked.observe(this) { isLocked ->
            if (isLocked) {
                val current = navController.currentDestination?.id
                if (current != R.id.lockFragment && current != R.id.setupFragment) {
                    navController.navigate(R.id.lockFragment)
                }
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        SessionLockManager.recordActivity()
    }
}
