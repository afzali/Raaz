package io.raaz.messenger.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.raaz.messenger.crypto.CryptoManager
import io.raaz.messenger.data.db.RaazDatabase
import io.raaz.messenger.data.db.dao.ContactDao
import io.raaz.messenger.data.db.dao.MessageDao
import io.raaz.messenger.data.db.dao.SessionDao
import io.raaz.messenger.data.model.Message
import io.raaz.messenger.data.model.Session
import io.raaz.messenger.data.preferences.RaazPreferences
import io.raaz.messenger.data.repository.MessageRepository
import io.raaz.messenger.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = "ChatVM"

    private val _messages = MutableLiveData<List<Message>>(emptyList())
    val messages: LiveData<List<Message>> = _messages

    private val _session = MutableLiveData<Session?>()
    val session: LiveData<Session?> = _session

    private var repo: MessageRepository? = null
    private var contactPublicKey: String = ""
    private var sessionId: String = ""

    fun init(sessionId: String, dbKey: String) {
        this.sessionId = sessionId
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val db = RaazDatabase.getInstance(getApplication(), dbKey)
                    val sessionDao = SessionDao(db.db)
                    val prefs = RaazPreferences(getApplication())
                    val settings = io.raaz.messenger.data.db.dao.SettingsDao(db.db).get()

                    repo = MessageRepository(
                        MessageDao(db.db), sessionDao, prefs,
                        settings.serverUrl
                    )

                    val s = sessionDao.getById(sessionId)
                    _session.postValue(s)
                    contactPublicKey = s?.contactPublicKey ?: ""

                    loadMessages()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to init ChatViewModel: ${e.message}", e)
                }
            }
        }
    }

    private fun loadMessages() {
        val list = repo?.getMessages(sessionId) ?: emptyList()
        _messages.postValue(list)
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || contactPublicKey.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val ciphertext = CryptoManager.encryptMessage(text, contactPublicKey)
                    val now = System.currentTimeMillis()
                    val msg = Message(
                        id = CryptoManager.generateMessageId(),
                        sessionId = sessionId,
                        direction = Message.DIR_OUTGOING,
                        ciphertext = ciphertext,
                        plaintextCache = text,
                        status = Message.STATUS_QUEUED,
                        createdAt = now,
                        expiresAt = now + 86_400_000L,
                        serverMsgId = null
                    )
                    repo?.insertOutgoing(msg)
                    loadMessages()
                    repo?.syncOutgoing()
                    loadMessages()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to send message: ${e.message}", e)
                }
            }
        }
    }

    fun syncIncoming() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val count = repo?.syncIncoming() ?: 0
                if (count > 0) loadMessages()
            }
        }
    }
}
