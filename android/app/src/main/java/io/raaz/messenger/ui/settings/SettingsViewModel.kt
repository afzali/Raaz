package io.raaz.messenger.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.raaz.messenger.data.db.RaazDatabase
import io.raaz.messenger.data.db.dao.SettingsDao
import io.raaz.messenger.data.model.AppSettings
import io.raaz.messenger.data.network.RegisterDeviceRequest
import io.raaz.messenger.data.network.RaazApiService
import io.raaz.messenger.data.preferences.RaazPreferences
import io.raaz.messenger.util.AppLogger
import io.raaz.messenger.util.SessionLockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = AppLogger.Cat.AUTH

    private val _settings = MutableLiveData<AppSettings?>()
    val settings: LiveData<AppSettings?> = _settings

    private val _saved = MutableLiveData<Boolean>()
    val saved: LiveData<Boolean> = _saved

    private var db: RaazDatabase? = null

    fun setDb(database: RaazDatabase) {
        db = database
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val s = db?.let { SettingsDao(it.db).get() }
                    _settings.postValue(s)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to load settings: ${e.message}", e)
                }
            }
        }
    }

    fun saveServerUrl(url: String) {
        if (url.isBlank()) return
        val trimmedUrl = url.trim().trimEnd('/')
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val currentDb = db ?: return@withContext
                    val dao = SettingsDao(currentDb.db)
                    dao.updateServerUrl(trimmedUrl)
                    AppLogger.i(TAG, "Server URL updated to: $trimmedUrl — re-registering device...")

                    // Re-register with new server so we get a fresh token
                    val settings = dao.get()
                    val prefs = RaazPreferences(getApplication())
                    val userId = settings.userId ?: prefs.userId
                    val deviceId = settings.deviceId ?: prefs.deviceId
                    val publicKey = settings.publicKey

                    if (userId != null && deviceId != null && publicKey != null) {
                        try {
                            val api = RaazApiService.get(trimmedUrl)
                            val resp = api.registerDevice(RegisterDeviceRequest(userId, deviceId, publicKey))
                            if (resp.isSuccessful) {
                                val token = resp.body()!!.token
                                prefs.bearerToken = token
                                AppLogger.i(TAG, "Re-registration OK — new token received (${token.length} chars)")
                            } else {
                                AppLogger.w(TAG, "Re-registration failed HTTP ${resp.code()}")
                            }
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Re-registration exception: ${e.message}")
                        }
                    } else {
                        AppLogger.w(TAG, "Cannot re-register: userId=$userId, deviceId=$deviceId, publicKey=${publicKey?.take(8)}")
                    }

                    loadSettings()
                    _saved.postValue(true)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to save server URL: ${e.message}", e)
                }
            }
        }
    }

    fun saveLockTimeout(timeoutMs: Long) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db?.let { SettingsDao(it.db).updateLockTimeout(timeoutMs) }
                }
                // updateTimeout (not init) — addObserver already called at startup
                SessionLockManager.updateTimeout(timeoutMs)
                _saved.postValue(true)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to save lock timeout: ${e.message}", e)
            }
        }
    }
}
