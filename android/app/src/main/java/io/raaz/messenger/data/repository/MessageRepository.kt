package io.raaz.messenger.data.repository

import android.content.Context
import io.raaz.messenger.crypto.CryptoManager
import io.raaz.messenger.data.db.dao.MessageDao
import io.raaz.messenger.data.db.dao.SessionDao
import io.raaz.messenger.data.model.Message
import io.raaz.messenger.data.network.PushMessageRequest
import io.raaz.messenger.data.network.RaazApiService
import io.raaz.messenger.data.network.ServerMessage
import io.raaz.messenger.data.preferences.RaazPreferences
import io.raaz.messenger.notification.RaazNotificationManager
import io.raaz.messenger.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MessageRepository(
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    private val prefs: RaazPreferences,
    private val serverUrl: String,
    private val context: Context? = null
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
        val myDeviceId = prefs.deviceId ?: return@withContext 0
        val queued = messageDao.getQueuedOutgoing()
        var sent = 0

        for (msg in queued) {
            val session = sessionDao.getById(msg.sessionId) ?: continue
            // recipientDeviceId — fixed: was incorrectly using contactPublicKey
            val recipientDeviceId = session.contactDeviceId
            if (recipientDeviceId.isBlank()) {
                AppLogger.w(TAG, "No deviceId for session ${msg.sessionId}, skipping")
                continue
            }
            try {
                val api = RaazApiService.get(serverUrl)
                val req = PushMessageRequest(
                    messageId = msg.id,
                    recipientDeviceId = recipientDeviceId,
                    senderDeviceId = myDeviceId,
                    ciphertext = msg.ciphertext,
                    ttlSeconds = session.messageTtlMs / 1000
                )
                val resp = api.pushMessage("Bearer $token", req)
                if (resp.isSuccessful) {
                    val body = resp.body()!!
                    messageDao.updateStatus(msg.id, Message.STATUS_SENT, body.serverMessageId)
                    sent++
                    AppLogger.d(TAG, "Pushed message ${msg.id} to device $recipientDeviceId")
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
            var unknownCount = 0

            for (serverMsg in messages) {
                when (processIncoming(serverMsg, token)) {
                    ProcessResult.STORED -> received++
                    ProcessResult.UNKNOWN_SENDER -> unknownCount++
                    ProcessResult.FAILED -> {}
                }
            }

            // Notify user about messages from unknown senders
            if (unknownCount > 0 && context != null) {
                AppLogger.i(TAG, "$unknownCount message(s) from unknown senders — notifying user")
                RaazNotificationManager.showUnknownSenderNotification(context, unknownCount)
            }

            received
        } catch (e: Exception) {
            AppLogger.e(TAG, "Sync incoming failed: ${e.message}", e)
            0
        }
    }

    private enum class ProcessResult { STORED, UNKNOWN_SENDER, FAILED }

    private suspend fun processIncoming(serverMsg: ServerMessage, token: String): ProcessResult {
        val plaintext = CryptoManager.decryptMessage(serverMsg.ciphertext) ?: return ProcessResult.FAILED

        val session = sessionDao.getByContactDeviceId(serverMsg.senderDeviceId)

        if (session == null) {
            // Message is from someone not in our contacts yet
            // ACK it so server doesn't keep resending, but don't store
            AppLogger.w(TAG, "Message from unknown sender ${serverMsg.senderDeviceId} — not in contacts")
            ackMessage(token, serverMsg.serverMessageId)
            return ProcessResult.UNKNOWN_SENDER
        }

        val msg = Message(
            id = serverMsg.serverMessageId,
            sessionId = session.id,
            direction = Message.DIR_INCOMING,
            ciphertext = serverMsg.ciphertext,
            plaintextCache = plaintext,
            status = Message.STATUS_DELIVERED,
            createdAt = serverMsg.createdAt * 1000,
            expiresAt = serverMsg.expiresAt * 1000,
            serverMsgId = serverMsg.serverMessageId
        )
        messageDao.insert(msg)
        sessionDao.updateLastMessage(session.id, msg.createdAt)

        ackMessage(token, serverMsg.serverMessageId)
        AppLogger.d(TAG, "Stored message ${serverMsg.serverMessageId} in session ${session.id}")
        return ProcessResult.STORED
    }

    private suspend fun ackMessage(token: String, serverMessageId: String) {
        try {
            RaazApiService.get(serverUrl).ackMessage("Bearer $token", serverMessageId)
            AppLogger.d(TAG, "ACKed message $serverMessageId")
        } catch (e: Exception) {
            AppLogger.w(TAG, "ACK failed for $serverMessageId: ${e.message}")
        }
    }

    fun deleteExpired() = messageDao.deleteExpired()
}
