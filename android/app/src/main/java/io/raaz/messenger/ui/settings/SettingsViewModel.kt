package io.raaz.messenger.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.raaz.messenger.data.db.RaazDatabase
import io.raaz.messenger.data.db.dao.SettingsDao
import io.raaz.messenger.data.model.AppSettings
import io.raaz.messenger.util.AppLogger
import io.raaz.messenger.util.SessionLockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = "SettingsVM"

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
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    db?.let { SettingsDao(it.db).updateServerUrl(url) }
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
