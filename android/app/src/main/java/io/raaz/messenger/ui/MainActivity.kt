package io.raaz.messenger.ui

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import io.raaz.messenger.R
import io.raaz.messenger.databinding.ActivityMainBinding
import io.raaz.messenger.util.LocaleManager
import io.raaz.messenger.util.SessionLockManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
