package io.raaz.messenger.data.repository

import android.content.Context
import io.raaz.messenger.crypto.CryptoManager
import io.raaz.messenger.data.db.dao.MessageDao
import io.raaz.messenger.data.db.dao.PendingMessageDao
import io.raaz.messenger.data.db.dao.SessionDao
import io.raaz.messenger.data.model.Message
import io.raaz.messenger.data.model.PendingMessage
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
    private val pendingDao: PendingMessageDao? = null,  // Optional for backward compat
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
                    messageDao.updateStatus(msg.id, Message.STATUS_FAILED)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Push exception for ${msg.id.take(8)}...: ${e.message}", e)
                messageDao.updateStatus(msg.id, Message.STATUS_FAILED)
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
            // Store message temporarily until contact is added
            AppLogger.w(TAG, "Unknown sender device ${serverMsg.senderDeviceId.take(8)}... — storing as PENDING")
            storePendingMessage(serverMsg, plaintext)
            ackMessage(token, serverMsg.serverMessageId)  // ACK to remove from server
            return ProcessResult.UNKNOWN_SENDER
        }

        AppLogger.i(TAG, "Storing msg in session ${session.id.take(8)}... (contact: ${session.contactDeviceId.take(8)}...)")

        // Detect file envelope — if plaintext is a JSON with "t" + "k" + "id", treat as media
        val envelope = tryParseFileEnvelope(plaintext, serverMsg.serverMessageId)
        val msg = if (envelope != null) {
            AppLogger.i(TAG, "Incoming message is a file envelope (type=${envelope.type}, size=${envelope.size}B)")
            Message(
                id = serverMsg.serverMessageId,
                sessionId = session.id,
                direction = Message.DIR_INCOMING,
                ciphertext = serverMsg.ciphertext,
                plaintextCache = plaintext,   // keep envelope JSON; UI uses other fields
                status = Message.STATUS_DELIVERED,
                createdAt = serverMsg.createdAt * 1000,
                expiresAt = serverMsg.expiresAt * 1000,
                serverMsgId = serverMsg.serverMessageId,
                mediaType = FileTransferRepository.mediaTypeFromEnvelopeType(envelope.type),
                fileId = envelope.fileId,
                fileName = envelope.name,
                fileSize = envelope.size,
                mimeType = envelope.mime,
                durationMs = envelope.durationMs
            )
        } else {
            Message(
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
        }
        messageDao.insert(msg)
        sessionDao.updateLastMessage(session.id, msg.createdAt)

        ackMessage(token, serverMsg.serverMessageId)
        return ProcessResult.STORED
    }

    private fun tryParseFileEnvelope(plaintext: String, serverMsgId: String? = null): FileTransferRepository.FileEnvelope? {
        if (!plaintext.startsWith("{")) return null
        return try {
            // Try new format first (t/k/id/n/m/s/c/d)
            val env = com.google.gson.Gson().fromJson(plaintext, FileTransferRepository.FileEnvelope::class.java)
            if (env != null && !env.fileId.isNullOrBlank() && !env.contentKeyB64.isNullOrBlank() && env.chunkCount > 0) {
                return env
            }
            // Fallback: old minified format (a/b/c/d/e/f/g/h) or minimal (a/b)
            tryParseOldEnvelope(plaintext, serverMsgId)
        } catch (_: Exception) {
            // Try old format as fallback
            tryParseOldEnvelope(plaintext, serverMsgId)
        }
    }

    /**
     * Parse legacy envelopes with various obfuscated formats.
     * Format A (a/b/c/d/e/f/g/h): a=type, b=key, c=fileId, d=name, e=mime, f=size, g=chunkCount, h=duration
     * Format B (t/k only): incomplete envelope, fileId must be provided externally
     */
    private fun tryParseOldEnvelope(plaintext: String, fallbackFileId: String? = null): FileTransferRepository.FileEnvelope? {
        return try {
            val tree = com.google.gson.Gson().fromJson(plaintext, com.google.gson.JsonObject::class.java)

            // Try to find type (t or a)
            val type = tree.get("t")?.asString ?: tree.get("a")?.asString ?: return null
            // Try to find key (k or b)
            val key = tree.get("k")?.asString ?: tree.get("b")?.asString ?: return null

            // Check if this is the minimal format (only a/b or t/k present)
            val hasOnlyMinimalFields = tree.size() <= 2 &&
                ((tree.has("a") || tree.has("t")) && (tree.has("b") || tree.has("k")))

            if (hasOnlyMinimalFields) {
                // Minimal format: need external fileId
                val fileId = fallbackFileId ?: return null
                // Assume single chunk for old minimal format or extract from message
                return FileTransferRepository.FileEnvelope(
                    type = type, contentKeyB64 = key, fileId = fileId,
                    name = "", mime = "audio/mp4", size = 0L,
                    chunkCount = 1, durationMs = null
                )
            }

            // Full format: try to find fileId (id, fileId, c-as-string, i)
            val fileId = tree.get("id")?.asString
                ?: tree.get("fileId")?.asString
                ?: tree.get("c")?.let { if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else null }
                ?: tree.get("i")?.asString
                ?: fallbackFileId
                ?: return null

            // Try to find chunkCount (g, c-as-int, chunkCount)
            val chunkCount = tree.get("g")?.asInt
                ?: tree.get("c")?.let { if (it.isJsonPrimitive && it.asJsonPrimitive.isNumber) it.asInt else null }
                ?: tree.get("chunkCount")?.asInt
                ?: 0
            if (chunkCount <= 0) return null

            // Optional fields with fallbacks
            val name = tree.get("n")?.asString
                ?: tree.get("name")?.asString
                ?: tree.get("d")?.asString
                ?: ""
            val mime = tree.get("m")?.asString
                ?: tree.get("mime")?.asString
                ?: tree.get("e")?.asString
                ?: "application/octet-stream"
            val size = tree.get("s")?.asLong
                ?: tree.get("size")?.asLong
                ?: tree.get("f")?.asLong
                ?: 0L
            val durationMs = tree.get("d")?.asLong
                ?: tree.get("durationMs")?.asLong
                ?: tree.get("h")?.asLong
                ?: tree.get("duration")?.asLong

            FileTransferRepository.FileEnvelope(
                type = type, contentKeyB64 = key, fileId = fileId,
                name = name, mime = mime, size = size,
                chunkCount = chunkCount, durationMs = durationMs
            )
        } catch (_: Exception) { null }
    }

    private fun storePendingMessage(serverMsg: ServerMessage, plaintext: String) {
        if (pendingDao == null) {
            AppLogger.w(TAG, "PendingMessageDao not available - message from ${serverMsg.senderDeviceId.take(8)}... discarded")
            return
        }
        try {
            val pending = PendingMessage(
                serverMsgId = serverMsg.serverMessageId,
                senderDeviceId = serverMsg.senderDeviceId,
                ciphertext = serverMsg.ciphertext,
                plaintextCache = plaintext,
                createdAt = serverMsg.createdAt * 1000,
                expiresAt = serverMsg.expiresAt * 1000
            )
            val id = pendingDao.insert(pending)
            AppLogger.i(TAG, "Stored pending message id=$id from ${serverMsg.senderDeviceId.take(8)}...")
            
            // Show notification with message preview
            if (context != null) {
                RaazNotificationManager.showUnknownSenderNotification(
                    context, 
                    1, 
                    plaintext.take(30),
                    serverMsg.senderDeviceId
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to store pending message: ${e.message}", e)
        }
    }

    // Move pending messages to regular messages when contact is added
    fun movePendingMessagesToSession(deviceId: String, sessionId: String): Int {
        if (pendingDao == null) return 0
        
        val pending = pendingDao.getBySender(deviceId)
        if (pending.isEmpty()) return 0
        
        var moved = 0
        for (p in pending) {
            try {
                // Detect file envelope — same logic as processIncoming
                val envelope = tryParseFileEnvelope(p.plaintextCache ?: "", p.serverMsgId)
                val msg = if (envelope != null) {
                    AppLogger.i(TAG, "Pending message is a file envelope (type=${envelope.type}, size=${envelope.size}B)")
                    Message(
                        id = p.serverMsgId,
                        sessionId = sessionId,
                        direction = Message.DIR_INCOMING,
                        ciphertext = p.ciphertext,
                        plaintextCache = p.plaintextCache,
                        status = Message.STATUS_DELIVERED,
                        createdAt = p.createdAt,
                        expiresAt = p.expiresAt,
                        serverMsgId = p.serverMsgId,
                        mediaType = FileTransferRepository.mediaTypeFromEnvelopeType(envelope.type),
                        fileId = envelope.fileId,
                        fileName = envelope.name,
                        fileSize = envelope.size,
                        mimeType = envelope.mime,
                        durationMs = envelope.durationMs
                    )
                } else {
                    Message(
                        id = p.serverMsgId,
                        sessionId = sessionId,
                        direction = Message.DIR_INCOMING,
                        ciphertext = p.ciphertext,
                        plaintextCache = p.plaintextCache,
                        status = Message.STATUS_DELIVERED,
                        createdAt = p.createdAt,
                        expiresAt = p.expiresAt,
                        serverMsgId = p.serverMsgId
                    )
                }
                messageDao.insert(msg)
                moved++
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to move pending message ${p.id}: ${e.message}")
            }
        }
        
        // Delete moved messages from pending
        if (moved > 0) {
            pendingDao.deleteBySender(deviceId)
            AppLogger.i(TAG, "Moved $moved pending messages to session ${sessionId.take(8)}...")
        }
        return moved
    }

    fun getPendingMessages(): List<PendingMessage> = pendingDao?.getAll() ?: emptyList()
    fun getPendingCount(): Int = pendingDao?.getCount() ?: 0

    fun markIncomingMessagesAsRead(sessionId: String): Int {
        val count = messageDao.markIncomingAsRead(sessionId)
        AppLogger.d(TAG, "Marked $count incoming messages as read for session ${sessionId.take(8)}...")
        return count
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

    fun deleteExpired() {
        messageDao.deleteExpired()
        pendingDao?.deleteExpired()
    }
}
