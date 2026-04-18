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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MessageRepository(
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    private val prefs: RaazPreferences,
    private val serverUrl: String,
    private val context: Context? = null
) {
    private val TAG = AppLogger.Cat.SYNC

    fun getMessages(sessionId: String): List<Message> = messageDao.getBySession(sessionId)

    fun insertOutgoing(message: Message) {
        messageDao.insert(message)
        sessionDao.updateLastMessage(message.sessionId, message.createdAt)
        AppLogger.d(TAG, "Queued outgoing msg ${message.id.take(8)}... in session ${message.sessionId.take(8)}...")
    }

    suspend fun syncOutgoing(): Int = withContext(Dispatchers.IO) {
        val token = prefs.bearerToken ?: run {
            AppLogger.w(TAG, "syncOutgoing: no bearer token — skipping")
            return@withContext 0
        }
        val myDeviceId = prefs.deviceId ?: run {
            AppLogger.w(TAG, "syncOutgoing: no deviceId — skipping")
            return@withContext 0
        }
        val queued = messageDao.getQueuedOutgoing()
        AppLogger.i(TAG, "syncOutgoing: ${queued.size} queued message(s)")
        var sent = 0

        for (msg in queued) {
            val session = sessionDao.getById(msg.sessionId)
            if (session == null) {
                AppLogger.w(TAG, "syncOutgoing: session ${msg.sessionId.take(8)}... not found, skipping")
                continue
            }
            val recipientDeviceId = session.contactDeviceId
            if (recipientDeviceId.isBlank()) {
                AppLogger.w(TAG, "syncOutgoing: no deviceId for session ${msg.sessionId.take(8)}..., skipping")
                continue
            }
            AppLogger.i(TAG, "Pushing msg ${msg.id.take(8)}... → device ${recipientDeviceId.take(8)}...")
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
                    // STATUS_DELIVERED = successfully stored on relay server (relay = delivery point)
                    messageDao.updateStatus(msg.id, Message.STATUS_DELIVERED, body.serverMessageId)
                    sent++
                    AppLogger.i(TAG, "Push OK → serverMsgId=${body.serverMessageId.take(8)}...")
                } else {
                    AppLogger.w(TAG, "Push failed HTTP ${resp.code()} for msg ${msg.id.take(8)}...")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Push exception for ${msg.id.take(8)}...: ${e.message}", e)
            }
        }
        AppLogger.i(TAG, "syncOutgoing done: $sent/${queued.size} sent")
        sent
    }

    suspend fun syncIncoming(): Int = withContext(Dispatchers.IO) {
        val token = prefs.bearerToken ?: run {
            AppLogger.w(TAG, "syncIncoming: no bearer token — skipping")
            return@withContext 0
        }
        if (!CryptoManager.isReady()) {
            AppLogger.w(TAG, "syncIncoming: CryptoManager not ready (locked?) — skipping")
            return@withContext 0
        }

        try {
            AppLogger.i(TAG, "Pulling messages from server...")
            val api = RaazApiService.get(serverUrl)
            val resp = api.pullMessages("Bearer $token")
            if (!resp.isSuccessful) {
                AppLogger.w(TAG, "Pull failed HTTP ${resp.code()}")
                return@withContext 0
            }

            val messages = resp.body()?.messages ?: run {
                AppLogger.d(TAG, "Pull response: no messages")
                return@withContext 0
            }
            AppLogger.i(TAG, "Pull: ${messages.size} message(s) from server")
            var received = 0
            var unknownCount = 0

            for (serverMsg in messages) {
                AppLogger.d(TAG, "Processing serverMsg ${serverMsg.serverMessageId.take(8)}... from device ${serverMsg.senderDeviceId.take(8)}...")
                when (processIncoming(serverMsg, token)) {
                    ProcessResult.STORED -> received++
                    ProcessResult.UNKNOWN_SENDER -> unknownCount++
                    ProcessResult.FAILED -> AppLogger.w(TAG, "processIncoming FAILED for ${serverMsg.serverMessageId.take(8)}...")
                }
            }

            if (unknownCount > 0 && context != null) {
                AppLogger.w(TAG, "$unknownCount message(s) from unknown senders — showing notification")
                RaazNotificationManager.showUnknownSenderNotification(context, unknownCount)
            }

            AppLogger.i(TAG, "syncIncoming done: received=$received, unknown=$unknownCount")
            received
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "syncIncoming exception: ${e.message}", e)
            0
        }
    }

    private enum class ProcessResult { STORED, UNKNOWN_SENDER, FAILED }

    private suspend fun processIncoming(serverMsg: ServerMessage, token: String): ProcessResult {
        AppLogger.d(TAG, "Decrypting msg ${serverMsg.serverMessageId.take(8)}...")
        val plaintext = CryptoManager.decryptMessage(serverMsg.ciphertext) ?: run {
            AppLogger.e(TAG, "Decryption FAILED for ${serverMsg.serverMessageId.take(8)}...")
            return ProcessResult.FAILED
        }
        AppLogger.d(TAG, "Decryption OK — plaintext length=${plaintext.length}")

        val session = sessionDao.getByContactDeviceId(serverMsg.senderDeviceId)

        if (session == null) {
            AppLogger.w(TAG, "Unknown sender device ${serverMsg.senderDeviceId.take(8)}... — ACKing and discarding")
            ackMessage(token, serverMsg.serverMessageId)
            return ProcessResult.UNKNOWN_SENDER
        }

        AppLogger.i(TAG, "Storing msg in session ${session.id.take(8)}... (contact: ${session.contactDeviceId.take(8)}...)")
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
        return ProcessResult.STORED
    }

    private suspend fun ackMessage(token: String, serverMessageId: String) {
        try {
            RaazApiService.get(serverUrl).ackMessage("Bearer $token", serverMessageId)
            AppLogger.d(TAG, "ACK sent for ${serverMessageId.take(8)}...")
        } catch (e: Exception) {
            AppLogger.w(TAG, "ACK failed for ${serverMessageId.take(8)}...: ${e.message}")
        }
    }

    suspend fun syncReceipts(): Int = withContext(Dispatchers.IO) {
        val token = prefs.bearerToken ?: return@withContext 0
        try {
            val resp = RaazApiService.get(serverUrl).pullReceipts("Bearer $token")
            if (!resp.isSuccessful) return@withContext 0
            val receipts = resp.body()?.receipts ?: return@withContext 0
            var confirmed = 0
            for (r in receipts) {
                messageDao.markConfirmed(r.messageId)
                confirmed++
            }
            if (confirmed > 0) AppLogger.i(TAG, "syncReceipts: $confirmed message(s) confirmed")
            confirmed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "syncReceipts exception: ${e.message}")
            0
        }
    }

    fun deleteExpired() = messageDao.deleteExpired()
}
