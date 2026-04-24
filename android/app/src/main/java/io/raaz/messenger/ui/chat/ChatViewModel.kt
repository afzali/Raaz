package io.raaz.messenger.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.raaz.messenger.crypto.CryptoManager
import io.raaz.messenger.crypto.QrCodeHelper
import io.raaz.messenger.data.db.RaazDatabase
import io.raaz.messenger.data.db.dao.ContactDao
import io.raaz.messenger.data.db.dao.MessageDao
import io.raaz.messenger.data.db.dao.PendingMessageDao
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

    private val TAG = AppLogger.Cat.UI

    private val _messages = MutableLiveData<List<Message>>(emptyList())
    val messages: LiveData<List<Message>> = _messages

    private val _session = MutableLiveData<Session?>()
    val session: LiveData<Session?> = _session

    sealed class RekeyResult {
        object Success : RekeyResult()
        object InvalidCode : RekeyResult()
        object UserMismatch : RekeyResult()
    }
    private val _rekeyResult = MutableLiveData<RekeyResult?>()
    val rekeyResult: LiveData<RekeyResult?> = _rekeyResult

    private var repo: MessageRepository? = null
    private var fileRepo: io.raaz.messenger.data.repository.FileTransferRepository? = null
    private var messageDaoRef: MessageDao? = null
    private var contactPublicKey: String = ""
    private var sessionId: String = ""
    private var contactId: String = ""
    private var db: RaazDatabase? = null

    fun init(sessionId: String, dbKey: String) {
        this.sessionId = sessionId
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    db = RaazDatabase.getInstance(getApplication(), dbKey)
                    val sessionDao = SessionDao(db!!.db)
                    val pendingDao = PendingMessageDao(db!!.db)
                    val messageDao = MessageDao(db!!.db)
                    messageDaoRef = messageDao
                    val prefs = RaazPreferences(getApplication())
                    val settings = io.raaz.messenger.data.db.dao.SettingsDao(db!!.db).get()

                    repo = MessageRepository(
                        messageDao, sessionDao, pendingDao, prefs,
                        settings.serverUrl, getApplication()
                    )
                    fileRepo = io.raaz.messenger.data.repository.FileTransferRepository(
                        messageDao, sessionDao, prefs, settings.serverUrl, getApplication()
                    )

                    val s = sessionDao.getById(sessionId)
                    _session.postValue(s)
                    contactPublicKey = s?.contactPublicKey ?: ""
                    contactId = s?.contactId ?: ""
                    val deviceId = s?.contactDeviceId ?: ""
                    AppLogger.i(TAG, "ChatVM init: session=${sessionId.take(8)}..., contact=${contactId.take(8)}...")

                    // Move any pending messages from this device to this session
                    if (deviceId.isNotBlank()) {
                        val moved = repo?.movePendingMessagesToSession(deviceId, sessionId) ?: 0
                        if (moved > 0) {
                            AppLogger.i(TAG, "Moved $moved pending messages to this chat")
                        }
                    }

                    loadMessages()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to init ChatViewModel: ${e.message}", e)
                }
            }
        }
    }

    fun reloadMessages() {
        val list = repo?.getMessages(sessionId) ?: emptyList()
        _messages.postValue(list)
    }

    private fun loadMessages() {
        reloadMessages()
        // Mark all delivered incoming messages as read (confirmed) when chat is opened
        markMessagesAsRead()
    }
    
    fun markMessagesAsRead() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val count = repo?.markIncomingMessagesAsRead(sessionId) ?: 0
                if (count > 0) {
                    AppLogger.i(TAG, "Marked $count messages as read in session ${sessionId.take(8)}...")
                }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || contactPublicKey.isBlank()) {
            if (contactPublicKey.isBlank()) AppLogger.w(TAG, "sendMessage: no contact public key — cannot encrypt")
            return
        }
        AppLogger.i(TAG, "Sending message (${text.length} chars) to session ${sessionId.take(8)}...")
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val ciphertext = CryptoManager.encryptMessage(text, contactPublicKey)
                    AppLogger.d(TAG, "Message encrypted — ciphertext ${ciphertext.length} chars")
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
                    AppLogger.i(TAG, "Message queued — triggering immediate send")
                    repo?.syncOutgoing()
                    loadMessages()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to send message: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Send a media attachment (voice note / file / image).
     * [localFile]: file already present in app storage (copy from URI done by caller).
     * [mediaType]: one of Message.MEDIA_AUDIO / MEDIA_FILE / MEDIA_IMAGE
     */
    fun sendAttachment(
        localFile: java.io.File,
        mediaType: Int,
        fileName: String,
        mimeType: String,
        durationMs: Long? = null
    ) {
        if (contactPublicKey.isBlank()) return
        AppLogger.i(TAG, "Sending attachment type=$mediaType name=$fileName size=${localFile.length()}B")
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val now = System.currentTimeMillis()
                    val msg = Message(
                        id = CryptoManager.generateMessageId(),
                        sessionId = sessionId,
                        direction = Message.DIR_OUTGOING,
                        ciphertext = "",  // filled after upload (envelope)
                        plaintextCache = null,
                        status = Message.STATUS_QUEUED,
                        createdAt = now,
                        expiresAt = now + 86_400_000L,
                        serverMsgId = null,
                        mediaType = mediaType,
                        fileName = fileName,
                        fileSize = localFile.length(),
                        mimeType = mimeType,
                        localPath = localFile.absolutePath,
                        uploadProgress = 0,
                        durationMs = durationMs
                    )
                    repo?.insertOutgoing(msg)
                    loadMessages()
                    val ok = fileRepo?.uploadAttachment(msg) ?: false
                    if (!ok) {
                        messageDaoRef?.updateStatus(msg.id, Message.STATUS_QUEUED)
                        AppLogger.w(TAG, "Attachment upload failed — keeping queued")
                    }
                    loadMessages()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to send attachment: ${e.message}", e)
                }
            }
        }
    }

    /** Download a received media attachment's chunks and decrypt to local storage. */
    fun downloadAttachment(msg: Message) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val path = fileRepo?.downloadAttachment(msg)
                    if (path != null) {
                        AppLogger.i(TAG, "Download complete: $path")
                    } else {
                        AppLogger.w(TAG, "Download failed for msg ${msg.id.take(8)}...")
                    }
                    loadMessages()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "downloadAttachment exception: ${e.message}", e)
                }
            }
        }
    }

    fun syncIncoming() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val count = repo?.syncIncoming() ?: 0
                if (count > 0) {
                    loadMessages()
                    // Mark newly arrived messages as read since user is in chat
                    markMessagesAsRead()
                }
            }
        }
    }

    fun rekeyContact(newCode: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val payload = QrCodeHelper.decodeContact(newCode.trim())
                if (payload == null) {
                    _rekeyResult.postValue(RekeyResult.InvalidCode)
                    return@withContext
                }
                // userId must match — we're updating keys for the same person
                if (payload.userId != contactId) {
                    _rekeyResult.postValue(RekeyResult.UserMismatch)
                    return@withContext
                }
                try {
                    val currentDb = db ?: return@withContext
                    ContactDao(currentDb.db).updateKeyAndDevice(
                        contactId, payload.publicKey, payload.deviceId, payload.serverUrl
                    )
                    contactPublicKey = payload.publicKey
                    AppLogger.i(TAG, "Re-keyed contact ${contactId.take(8)}... with new public key")
                    _rekeyResult.postValue(RekeyResult.Success)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Re-key failed: ${e.message}", e)
                    _rekeyResult.postValue(RekeyResult.InvalidCode)
                }
            }
        }
    }

    fun clearRekeyResult() { _rekeyResult.value = null }

    fun renameContact(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank() || contactId.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val currentDb = db ?: return@withContext
                    ContactDao(currentDb.db).rename(contactId, trimmed)
                    AppLogger.i(TAG, "Contact renamed: ${contactId.take(8)}... → $trimmed")
                    val s = SessionDao(currentDb.db).getById(sessionId)
                    _session.postValue(s)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Rename failed: ${e.message}", e)
                }
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val currentDb = db ?: return@withContext
                    MessageDao(currentDb.db).deleteBySession(sessionId)
                    AppLogger.i(TAG, "History cleared for session ${sessionId.take(8)}...")
                    reloadMessages()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Clear history failed: ${e.message}", e)
                }
            }
        }
    }

    fun deleteContact() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val currentDb = db ?: return@withContext
                    // Delete contact (cascade will delete session and messages)
                    ContactDao(currentDb.db).delete(contactId)
                    AppLogger.i(TAG, "Contact deleted: ${contactId.take(8)}...")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Delete contact failed: ${e.message}", e)
                }
            }
        }
    }
}
