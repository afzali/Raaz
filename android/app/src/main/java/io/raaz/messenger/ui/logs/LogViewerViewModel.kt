package io.raaz.messenger.ui.logs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.raaz.messenger.crypto.PasswordManager
import io.raaz.messenger.data.db.AuthDatabase
import io.raaz.messenger.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogViewerViewModel(app: Application) : AndroidViewModel(app) {

    private val _logs = MutableLiveData<List<AppLogger.LogEntry>>(emptyList())
    val logs: LiveData<List<AppLogger.LogEntry>> = _logs

    private var currentLevel: String? = null
    private var currentQuery: String? = null

    private val authDb by lazy {
        val key = PasswordManager.generateAuthDbKey(getApplication())
        AuthDatabase.getInstance(getApplication(), key)
    }

    fun load(level: String? = currentLevel, query: String? = currentQuery) {
        currentLevel = level
        currentQuery = query
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                authDb.getLogs(level = level, query = query)
            }
            _logs.value = list
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { authDb.clearLogs() }
            AppLogger.clearBuffer()
            load()
        }
    }
}
