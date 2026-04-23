package io.raaz.messenger.data.db.dao

import android.content.ContentValues
import io.raaz.messenger.data.model.Message
import net.zetetic.database.sqlcipher.SQLiteDatabase

class MessageDao(private val db: SQLiteDatabase) {

    fun insert(message: Message) {
        val cv = message.toContentValues()
        db.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getBySession(sessionId: String, limit: Int = 100): List<Message> {
        val cursor = db.rawQuery(
            "SELECT id, session_id, direction, ciphertext, plaintext_cache, status, created_at, expires_at, server_msg_id, nonce FROM messages WHERE session_id=? ORDER BY created_at ASC LIMIT ?",
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
            "SELECT id, session_id, direction, ciphertext, plaintext_cache, status, created_at, expires_at, server_msg_id, nonce FROM messages WHERE id=?",
            arrayOf(id)
        )
        return cursor.use { if (it.moveToFirst()) it.toMessage() else null }
    }

    fun getQueuedOutgoing(): List<Message> {
        val cursor = db.rawQuery(
            "SELECT id, session_id, direction, ciphertext, plaintext_cache, status, created_at, expires_at, server_msg_id, nonce FROM messages WHERE direction=0 AND status=0 ORDER BY created_at ASC",
            null
        )
        return cursor.use { c ->
            val list = mutableListOf<Message>()
            while (c.moveToNext()) list.add(c.toMessage())
            list
        }
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
        nonce = getString(9)
    )
}
