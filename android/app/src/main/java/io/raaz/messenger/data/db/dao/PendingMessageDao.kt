package io.raaz.messenger.data.db.dao

import android.content.ContentValues
import io.raaz.messenger.data.model.PendingMessage
import net.zetetic.database.sqlcipher.SQLiteDatabase

class PendingMessageDao(private val db: SQLiteDatabase) {

    fun insert(pending: PendingMessage): Long {
        val cv = ContentValues().apply {
            put("server_msg_id", pending.serverMsgId)
            put("sender_device_id", pending.senderDeviceId)
            put("ciphertext", pending.ciphertext)
            put("plaintext_cache", pending.plaintextCache)
            put("created_at", pending.createdAt)
            put("expires_at", pending.expiresAt)
            put("received_at", pending.receivedAt)
        }
        return db.insertWithOnConflict("pending_messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAll(): List<PendingMessage> {
        val cursor = db.rawQuery(
            """SELECT id, server_msg_id, sender_device_id, ciphertext, plaintext_cache, 
                created_at, expires_at, received_at 
                FROM pending_messages 
                ORDER BY received_at ASC""",
            null
        )
        return cursor.use { c ->
            val list = mutableListOf<PendingMessage>()
            while (c.moveToNext()) {
                list.add(PendingMessage(
                    id = c.getLong(0),
                    serverMsgId = c.getString(1),
                    senderDeviceId = c.getString(2),
                    ciphertext = c.getString(3),
                    plaintextCache = c.getString(4),
                    createdAt = c.getLong(5),
                    expiresAt = if (c.isNull(6)) null else c.getLong(6),
                    receivedAt = c.getLong(7)
                ))
            }
            list
        }
    }

    fun getBySender(deviceId: String): List<PendingMessage> {
        val cursor = db.rawQuery(
            """SELECT id, server_msg_id, sender_device_id, ciphertext, plaintext_cache, 
                created_at, expires_at, received_at 
                FROM pending_messages 
                WHERE sender_device_id=? 
                ORDER BY received_at ASC""",
            arrayOf(deviceId)
        )
        return cursor.use { c ->
            val list = mutableListOf<PendingMessage>()
            while (c.moveToNext()) {
                list.add(PendingMessage(
                    id = c.getLong(0),
                    serverMsgId = c.getString(1),
                    senderDeviceId = c.getString(2),
                    ciphertext = c.getString(3),
                    plaintextCache = c.getString(4),
                    createdAt = c.getLong(5),
                    expiresAt = if (c.isNull(6)) null else c.getLong(6),
                    receivedAt = c.getLong(7)
                ))
            }
            list
        }
    }

    fun getCount(): Int {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM pending_messages", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun getCountBySender(deviceId: String): Int {
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM pending_messages WHERE sender_device_id=?",
            arrayOf(deviceId)
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun deleteById(id: Long) {
        db.delete("pending_messages", "id=?", arrayOf(id.toString()))
    }

    fun deleteBySender(deviceId: String) {
        db.delete("pending_messages", "sender_device_id=?", arrayOf(deviceId))
    }

    fun deleteAll() {
        db.delete("pending_messages", null, null)
    }

    fun deleteExpired() {
        val now = System.currentTimeMillis()
        db.delete("pending_messages", "expires_at IS NOT NULL AND expires_at < ?", arrayOf(now.toString()))
    }
}
