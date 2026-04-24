package io.raaz.messenger.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.raaz.messenger.crypto.CryptoManager
import io.raaz.messenger.crypto.FileCrypto
import io.raaz.messenger.data.db.dao.MessageDao
import io.raaz.messenger.data.db.dao.SessionDao
import io.raaz.messenger.data.model.Message
import io.raaz.messenger.data.network.PendingFile
import io.raaz.messenger.data.network.PushMessageRequest
import io.raaz.messenger.data.network.RaazApiService
import io.raaz.messenger.data.network.UploadFileChunkRequest
import io.raaz.messenger.data.preferences.RaazPreferences
import io.raaz.messenger.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Handles upload/download of encrypted file attachments.
 *
 * Protocol:
 *  - Sender generates a random content key, encrypts file chunks with it,
 *    uploads encrypted chunks to server (/files/upload).
 *  - Sender then sends a regular message (encrypted for recipient's pubkey)
 *    whose plaintext is a JSON envelope containing the content key + metadata.
 *  - Recipient receives the message → parses envelope → downloads chunks →
 *    decrypts them with the content key → reassembles the file locally.
 */
class FileTransferRepository(
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    private val prefs: RaazPreferences,
    private val serverUrl: String,
    private val context: Context
) {
    private val TAG = AppLogger.Cat.SYNC
    private val gson = Gson()

    // ─── Envelope wrapped into the text-ciphertext message ──────────────────

    data class FileEnvelope(
        @SerializedName("t") val type: String,           // "file" / "audio" / "image"
        @SerializedName("k") val contentKeyB64: String,  // base64 content key
        @SerializedName("id") val fileId: String,
        @SerializedName("n") val name: String,
        @SerializedName("m") val mime: String,
        @SerializedName("s") val size: Long,
        @SerializedName("c") val chunkCount: Int,
        @SerializedName("d") val durationMs: Long? = null
    )

    companion object {
        const val FILES_DIR = "raaz_files"

        fun mediaTypeFromEnvelopeType(t: String): Int = when (t) {
            "audio" -> Message.MEDIA_AUDIO
            "image" -> Message.MEDIA_IMAGE
            else -> Message.MEDIA_FILE
        }

        fun envelopeTypeFromMedia(mediaType: Int): String = when (mediaType) {
            Message.MEDIA_AUDIO -> "audio"
            Message.MEDIA_IMAGE -> "image"
            else -> "file"
        }
    }

    private fun filesDir(): File {
        val dir = File(context.filesDir, FILES_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ─── Upload ─────────────────────────────────────────────────────────────

    /**
     * Upload the file referenced by [msg] (msg.localPath must be set).
     * Progress is written to messages.upload_progress on each chunk.
     * Returns true if upload (and subsequent message send) succeeded.
     */
    suspend fun uploadAttachment(msg: Message): Boolean = withContext(Dispatchers.IO) {
        val token = prefs.bearerToken ?: return@withContext false
        val myDeviceId = prefs.deviceId ?: return@withContext false

        val session = sessionDao.getById(msg.sessionId) ?: return@withContext false
        val recipientDeviceId = session.contactDeviceId
        val recipientPubKey = session.contactPublicKey
        if (recipientDeviceId.isBlank() || recipientPubKey.isBlank()) return@withContext false

        val localPath = msg.localPath ?: return@withContext false
        val file = File(localPath)
        if (!file.exists()) {
            AppLogger.w(TAG, "uploadAttachment: file not found at $localPath")
            return@withContext false
        }

        val contentKey = FileCrypto.generateContentKey()
        val fileName = msg.fileName ?: file.name
        val mime = msg.mimeType ?: "application/octet-stream"
        val size = file.length()
        val chunkCount = ((size + FileCrypto.CHUNK_SIZE - 1) / FileCrypto.CHUNK_SIZE).toInt().coerceAtLeast(1)
        val fileIdLocal = msg.fileId ?: java.util.UUID.randomUUID().toString().replace("-", "")

        messageDao.updateFileId(msg.id, fileIdLocal)
        AppLogger.i(TAG, "Uploading file ${msg.id.take(8)}... chunks=$chunkCount size=${size}B")

        val fileNameEnc = FileCrypto.encryptString(fileName, contentKey)
        val mimeEnc = FileCrypto.encryptString(mime, contentKey)

        val api = RaazApiService.get(serverUrl)

        try {
            file.inputStream().use { input ->
                val buf = ByteArray(FileCrypto.CHUNK_SIZE)
                var idx = 0
                while (true) {
                    val read = input.readChunk(buf)
                    if (read <= 0) break
                    val plainChunk = if (read == buf.size) buf else buf.copyOf(read)
                    val encChunk = FileCrypto.encryptChunk(plainChunk, contentKey)
                    val encB64 = Base64.encodeToString(encChunk, Base64.NO_WRAP)

                    val req = UploadFileChunkRequest(
                        recipientDeviceId = recipientDeviceId,
                        fileId = fileIdLocal,
                        fileNameEnc = fileNameEnc,
                        mimeTypeEnc = mimeEnc,
                        sizeBytes = size,
                        chunkIndex = idx,
                        chunkCount = chunkCount,
                        chunkDataB64 = encB64
                    )
                    val resp = api.uploadFileChunk("Bearer $token", req)
                    if (!resp.isSuccessful) {
                        AppLogger.w(TAG, "Chunk upload HTTP ${resp.code()} (idx=$idx)")
                        return@withContext false
                    }
                    val pct = (((idx + 1).toLong() * 100L) / chunkCount).toInt()
                    messageDao.updateUploadProgress(msg.id, pct)
                    idx++
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Upload failed: ${e.message}", e)
            return@withContext false
        }

        // Now send a message containing the file envelope
        val envelope = FileEnvelope(
            type = envelopeTypeFromMedia(msg.mediaType),
            contentKeyB64 = Base64.encodeToString(contentKey, Base64.NO_WRAP),
            fileId = fileIdLocal,
            name = fileName,
            mime = mime,
            size = size,
            chunkCount = chunkCount,
            durationMs = msg.durationMs
        )
        val envelopeJson = gson.toJson(envelope)
        val ciphertext = CryptoManager.encryptMessage(envelopeJson, recipientPubKey)

        val pushReq = PushMessageRequest(
            messageId = msg.id,
            recipientDeviceId = recipientDeviceId,
            senderDeviceId = myDeviceId,
            ciphertext = ciphertext,
            ttlSeconds = session.messageTtlMs / 1000
        )
        val pushResp = api.pushMessage("Bearer $token", pushReq)
        if (!pushResp.isSuccessful) {
            AppLogger.w(TAG, "File envelope push failed HTTP ${pushResp.code()}")
            return@withContext false
        }
        val serverMsgId = pushResp.body()!!.serverMessageId
        messageDao.updateStatus(msg.id, Message.STATUS_DELIVERED, serverMsgId)
        messageDao.updateUploadProgress(msg.id, 100)
        AppLogger.i(TAG, "File upload complete ${msg.id.take(8)}...")
        true
    }

    // ─── Download ───────────────────────────────────────────────────────────

    /**
     * Download and decrypt chunks for a message that was received with a FileEnvelope.
     * Updates messages.local_path and download_progress.
     * Returns the local file path or null on failure.
     */
    suspend fun downloadAttachment(msg: Message): String? = withContext(Dispatchers.IO) {
        val token = prefs.bearerToken ?: return@withContext null
        val fileId = msg.fileId ?: return@withContext null

        // Content key is in plaintext_cache (the decrypted envelope JSON)
        val envelopeJson = msg.plaintextCache ?: return@withContext null
        val envelope = try {
            gson.fromJson(envelopeJson, FileEnvelope::class.java)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Bad envelope JSON: ${e.message}")
            return@withContext null
        }
        val contentKey = Base64.decode(envelope.contentKeyB64, Base64.NO_WRAP)

        val outFile = File(filesDir(), "${fileId}_${sanitize(envelope.name)}")
        val api = RaazApiService.get(serverUrl)

        try {
            FileOutputStream(outFile).use { out ->
                for (idx in 0 until envelope.chunkCount) {
                    val resp = api.downloadFileChunk("Bearer $token", fileId, idx)
                    if (!resp.isSuccessful) {
                        AppLogger.w(TAG, "Chunk download HTTP ${resp.code()} (idx=$idx)")
                        return@withContext null
                    }
                    val encBytes = resp.body()?.bytes() ?: return@withContext null
                    val plain = FileCrypto.decryptChunk(encBytes, contentKey) ?: run {
                        AppLogger.e(TAG, "Decryption failed for chunk $idx")
                        return@withContext null
                    }
                    out.write(plain)
                    val pct = (((idx + 1).toLong() * 100L) / envelope.chunkCount).toInt()
                    messageDao.updateDownloadProgress(msg.id, pct)
                }
            }
            messageDao.updateLocalPath(msg.id, outFile.absolutePath)
            messageDao.updateDownloadProgress(msg.id, 100)
            // Ack so server can delete the chunks
            try {
                api.ackFile("Bearer $token", fileId)
            } catch (_: Exception) { }
            AppLogger.i(TAG, "File download complete → ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (e: Exception) {
            AppLogger.e(TAG, "Download failed: ${e.message}", e)
            if (outFile.exists()) outFile.delete()
            null
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)

    private fun InputStream.readChunk(buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val read = read(buf, total, buf.size - total)
            if (read == -1) break
            total += read
        }
        return total
    }
}
