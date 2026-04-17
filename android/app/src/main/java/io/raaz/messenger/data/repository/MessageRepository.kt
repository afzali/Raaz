package io.raaz.messenger.data.repository

import io.raaz.messenger.crypto.CryptoManager
import io.raaz.messenger.data.db.dao.MessageDao
import io.raaz.messenger.data.db.dao.SessionDao
import io.raaz.messenger.data.model.Message
import io.raaz.messenger.data.network.PushMessageRequest
import io.raaz.messenger.data.network.RaazApiService
import io.raaz.messenger.data.network.ServerMessage
import io.raaz.messenger.data.preferences.RaazPreferences
import io.raaz.messenger.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MessageRepository(
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    private val prefs: RaazPreferences,
    private val serverUrl: String
) {
    private val TAG = "MessageRepo"

    fun getMessages(sessionId: String): List<Message> = messageDao.getBySession(sessionId)

    fun insertOutgoing(message: Message) {
        messageDao.insert(message)
        sessionDao.updateLastMessage(message.sessionId, message.createdAt)
        AppLogger.d(TAG, "Inserted outgoing message ${message.id}")
    }

    suspend fun syncOutgoing(): Int = withContext(Dispatchers.IO) {
        val token = prefs.bearerToken ?: return@withContext 0
        val deviceId = prefs.deviceId ?: return@withContext 0
        val queued = messageDao.getQueuedOutgoing()
        var sent = 0

        for (msg in queued) {
            val session = sessionDao.getById(msg.sessionId) ?: continue
            try {
                val api = RaazApiService.get(serverUrl)
                val req = PushMessageRequest(
                    messageId = msg.id,
                    recipientDeviceId = session.contactPublicKey, // deviceId of recipient
                    senderDeviceId = deviceId,
                    ciphertext = msg.ciphertext,
                    ttlSeconds = session.messageTtlMs / 1000
                )
                val resp = api.pushMessage("Bearer $token", req)
                if (resp.isSuccessful) {
                    val body = resp.body()!!
                    messageDao.updateStatus(msg.id, Message.STATUS_SENT, body.serverMessageId)
                    sent++
                    AppLogger.d(TAG, "Pushed message ${msg.id}")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to push ${msg.id}: ${e.message}", e)
            }
        }
        sent
    }

    suspend fun syncIncoming(): Int = withContext(Dispatchers.IO) {
        val token = prefs.bearerToken ?: return@withContext 0
        if (!CryptoManager.isReady()) return@withContext 0

        try {
            val api = RaazApiService.get(serverUrl)
            val resp = api.pullMessages("Bearer $token")
            if (!resp.isSuccessful) return@withContext 0

            val messages = resp.body()?.messages ?: return@withContext 0
            var received = 0

            for (serverMsg in messages) {
                if (processIncoming(serverMsg, token)) received++
            }
            received
        } catch (e: Exception) {
            AppLogger.e(TAG, "Sync incoming failed: ${e.message}", e)
            0
        }
    }

    private suspend fun processIncoming(serverMsg: ServerMessage, token: String): Boolean {
        val plaintext = CryptoManager.decryptMessage(serverMsg.ciphertext) ?: return false
        AppLogger.d(TAG, "Decrypted incoming message ${serverMsg.serverMessageId}")

        // Find which session this belongs to (by matching sender device ID)
        val sessions = sessionDao.getAllWithPreview()
        val session = sessions.firstOrNull() // TODO: match by sender device ID

        val sessionId = session?.id ?: run {
            AppLogger.w(TAG, "No session found for message ${serverMsg.serverMessageId}")
            return false
        }

        val msg = Message(
            id = serverMsg.serverMessageId,
            sessionId = sessionId,
            direction = Message.DIR_INCOMING,
            ciphertext = serverMsg.ciphertext,
            plaintextCache = plaintext,
            status = Message.STATUS_DELIVERED,
            createdAt = serverMsg.createdAt * 1000,
            expiresAt = serverMsg.expiresAt * 1000,
            serverMsgId = serverMsg.serverMessageId
        )
        messageDao.insert(msg)
        sessionDao.updateLastMessage(sessionId, msg.createdAt)

        // ACK
        try {
            RaazApiService.get(serverUrl).ackMessage("Bearer $token", serverMsg.serverMessageId)
            AppLogger.d(TAG, "ACKed message ${serverMsg.serverMessageId}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "ACK failed for ${serverMsg.serverMessageId}: ${e.message}")
        }
        return true
    }

    fun deleteExpired() = messageDao.deleteExpired()
}
