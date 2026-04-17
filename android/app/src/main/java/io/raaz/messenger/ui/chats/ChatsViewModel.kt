package io.raaz.messenger.ui.chats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.raaz.messenger.data.db.RaazDatabase
import io.raaz.messenger.data.db.dao.ContactDao
import io.raaz.messenger.data.db.dao.SessionDao
import io.raaz.messenger.data.model.Session
import io.raaz.messenger.data.repository.ContactRepository
import io.raaz.messenger.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatsViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = "ChatsVM"

    private val _sessions = MutableLiveData<List<Session>>(emptyList())
    val sessions: LiveData<List<Session>> = _sessions

    private var repo: ContactRepository? = null

    fun init(dbKey: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val db = RaazDatabase.getInstance(getApplication(), dbKey)
                    repo = ContactRepository(ContactDao(db.db), SessionDao(db.db))
                    loadSessions()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to init ChatsViewModel: ${e.message}", e)
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { withContext(Dispatchers.IO) { loadSessions() } }
    }

    private fun loadSessions() {
        val list = repo?.getSessions() ?: emptyList()
        _sessions.postValue(list)
        AppLogger.d(TAG, "Loaded ${list.size} sessions")
    }
}
