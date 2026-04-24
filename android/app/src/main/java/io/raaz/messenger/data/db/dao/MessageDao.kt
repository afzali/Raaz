package io.raaz.messenger.data.db.dao

import android.content.ContentValues
import io.raaz.messenger.data.model.Message
import net.zetetic.database.sqlcipher.SQLiteDatabase

class MessageDao(private val db: SQLiteDatabase) {

    fun insert(message: Message) {
        val cv = message.toContentValues()
        db.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private val ALL_COLS = "id, session_id, direction, ciphertext, plaintext_cache, status, created_at, expires_at, server_msg_id, nonce, media_type, file_id, file_name, file_size, mime_type, local_path, upload_progress, download_progress, duration_ms"

    fun getBySession(sessionId: String, limit: Int = 100): List<Message> {
        val cursor = db.rawQuery(
            "SELECT $ALL_COLS FROM messages WHERE session_id=? ORDER BY created_at ASC LIMIT ?",
            arrayOf(sessionId, limit.toString())
        )
        return cursor.use { c ->
            val list = mutableListOf<Message>()
            while (c.moveToNext()) list.add(c.toMessage())
            list
        }
    }

    fun getById(id: String): Message? {
        val cursor = db.rawQuery(
            "SELECT $ALL_COLS FROM messages WHERE id=?",
            arrayOf(id)
        )
        return cursor.use { if (it.moveToFirst()) it.toMessage() else null }
    }

    fun getQueuedOutgoing(): List<Message> {
        val cursor = db.rawQuery(
            "SELECT $ALL_COLS FROM messages WHERE direction=0 AND status=0 AND media_type=0 ORDER BY created_at ASC",
            null
        )
        return cursor.use { c ->
            val list = mutableListOf<Message>()
            while (c.moveToNext()) list.add(c.toMessage())
            list
        }
    }

    fun updateUploadProgress(id: String, progress: Int) {
        val cv = ContentValues().apply { put("upload_progress", progress) }
        db.update("messages", cv, "id=?", arrayOf(id))
    }

    fun updateDownloadProgress(id: String, progress: Int) {
        val cv = ContentValues().apply { put("download_progress", progress) }
        db.update("messages", cv, "id=?", arrayOf(id))
    }

    fun updateLocalPath(id: String, localPath: String) {
        val cv = ContentValues().apply { put("local_path", localPath) }
        db.update("messages", cv, "id=?", arrayOf(id))
    }

    fun updateFileId(id: String, fileId: String) {
        val cv = ContentValues().apply { put("file_id", fileId) }
        db.update("messages", cv, "id=?", arrayOf(id))
    }

    fun markConfirmed(serverMsgId: String) {
        val cv = ContentValues().apply { put("status", Message.STATUS_CONFIRMED) }
        // match by server_msg_id, only outgoing, only if currently DELIVERED (don't downgrade)
        db.update("messages", cv, "server_msg_id=? AND direction=0 AND status=?",
            arrayOf(serverMsgId, Message.STATUS_DELIVERED.toString()))
    }

    fun updateStatus(id: String, status: Int, serverMsgId: String? = null) {
        val cv = ContentValues().apply {
            put("status", status)
            serverMsgId?.let { put("server_msg_id", it) }
        }
        db.update("messages", cv, "id=?", arrayOf(id))
    }

    fun updatePlaintextCache(id: String, plaintext: String) {
        val cv = ContentValues().apply { put("plaintext_cache", plaintext) }
        db.update("messages", cv, "id=?", arrayOf(id))
    }

    fun deleteExpired() {
        val now = System.currentTimeMillis()
        db.delete("messages", "expires_at IS NOT NULL AND expires_at < ?", arrayOf(now.toString()))
    }

    fun deleteBySession(sessionId: String) {
        db.delete("messages", "session_id=?", arrayOf(sessionId))
    }

    // Mark all delivered incoming messages as confirmed (read) for a session
    fun markIncomingAsRead(sessionId: String): Int {
        val cv = ContentValues().apply { put("status", Message.STATUS_CONFIRMED) }
        return db.update("messages", cv, "session_id=? AND direction=? AND status=?",
            arrayOf(sessionId, Message.DIR_INCOMING.toString(), Message.STATUS_DELIVERED.toString()))
    }

    fun getPendingIncoming(): List<Message> {
        val cursor = db.rawQuery(
            "SELECT id, session_id, direction, ciphertext, plaintext_cache, status, created_at, expires_at, server_msg_id, nonce FROM messages WHERE direction=1 AND status < 3",
            null
        )
        return cursor.use { c ->
            val list = mutableListOf<Message>()
            while (c.moveToNext()) list.add(c.toMessage())
            list
        }
    }

    private fun Message.toContentValues() = ContentValues().apply {
        put("id", id)
        put("session_id", sessionId)
        put("direction", direction)
        put("ciphertext", ciphertext)
        plaintextCache?.let { put("plaintext_cache", it) } ?: putNull("plaintext_cache")
        put("status", status)
        put("created_at", createdAt)
        expiresAt?.let { put("expires_at", it) } ?: putNull("expires_at")
        serverMsgId?.let { put("server_msg_id", it) } ?: putNull("server_msg_id")
        put("nonce", nonce)
        put("media_type", mediaType)
        fileId?.let { put("file_id", it) } ?: putNull("file_id")
        fileName?.let { put("file_name", it) } ?: putNull("file_name")
        fileSize?.let { put("file_size", it) } ?: putNull("file_size")
        mimeType?.let { put("mime_type", it) } ?: putNull("mime_type")
        localPath?.let { put("local_path", it) } ?: putNull("local_path")
        put("upload_progress", uploadProgress)
        put("download_progress", downloadProgress)
        durationMs?.let { put("duration_ms", it) } ?: putNull("duration_ms")
    }

    private fun android.database.Cursor.toMessage() = Message(
        id = getString(0),
        sessionId = getString(1),
        direction = getInt(2),
        ciphertext = getString(3),
        plaintextCache = if (isNull(4)) null else getString(4),
        status = getInt(5),
        createdAt = getLong(6),
        expiresAt = if (isNull(7)) null else getLong(7),
        serverMsgId = if (isNull(8)) null else getString(8),
        nonce = getString(9),
        mediaType = if (columnCount > 10) getInt(10) else Message.MEDIA_TEXT,
        fileId = if (columnCount > 11 && !isNull(11)) getString(11) else null,
        fileName = if (columnCount > 12 && !isNull(12)) getString(12) else null,
        fileSize = if (columnCount > 13 && !isNull(13)) getLong(13) else null,
        mimeType = if (columnCount > 14 && !isNull(14)) getString(14) else null,
        localPath = if (columnCount > 15 && !isNull(15)) getString(15) else null,
        uploadProgress = if (columnCount > 16) getInt(16) else 0,
        downloadProgress = if (columnCount > 17) getInt(17) else 0,
        durationMs = if (columnCount > 18 && !isNull(18)) getLong(18) else null
    )
}
