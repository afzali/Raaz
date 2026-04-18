package io.raaz.messenger.data.db.dao

import android.content.ContentValues
import io.raaz.messenger.data.model.Session
import net.zetetic.database.sqlcipher.SQLiteDatabase

class SessionDao(private val db: SQLiteDatabase) {

    fun insert(session: Session) {
        val cv = session.toContentValues()
        db.insertWithOnConflict("sessions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllWithPreview(): List<Session> {
        val cursor = db.rawQuery("""
            SELECT s.id, s.contact_id, s.created_at, s.last_message_at,
                   s.message_ttl_ms, s.sensitivity, s.notif_behavior,
                   c.display_name, c.public_key, c.device_id,
                   (SELECT plaintext_cache FROM messages WHERE session_id=s.id ORDER BY created_at DESC LIMIT 1) as preview,
                   (SELECT COUNT(*) FROM messages WHERE session_id=s.id AND direction=1 AND status < 3) as unread
            FROM sessions s
            LEFT JOIN contacts c ON s.contact_id = c.id
            ORDER BY COALESCE(s.last_message_at, s.created_at) DESC
        """.trimIndent(), null)

        return cursor.use { c ->
            val list = mutableListOf<Session>()
            while (c.moveToNext()) {
                list.add(Session(
                    id = c.getString(0),
                    contactId = c.getString(1),
                    createdAt = c.getLong(2),
                    lastMessageAt = if (c.isNull(3)) null else c.getLong(3),
                    messageTtlMs = c.getLong(4),
                    sensitivity = c.getInt(5),
                    notifBehavior = c.getInt(6),
                    contactName = c.getString(7) ?: "",
                    contactPublicKey = c.getString(8) ?: "",
                    contactDeviceId = c.getString(9) ?: "",
                    lastMessagePreview = c.getString(10) ?: "",
                    unreadCount = c.getInt(11)
                ))
            }
            list
        }
    }

    fun getById(id: String): Session? {
        val cursor = db.rawQuery(
            """SELECT s.id, s.contact_id, s.created_at, s.last_message_at,
               s.message_ttl_ms, s.sensitivity, s.notif_behavior,
               c.display_name, c.public_key, c.device_id
               FROM sessions s LEFT JOIN contacts c ON s.contact_id=c.id
               WHERE s.id=?""",
            arrayOf(id)
        )
        return cursor.use { c ->
            if (c.moveToFirst()) c.toSession() else null
        }
    }

    fun getByContactId(contactId: String): Session? {
        val cursor = db.rawQuery(
            """SELECT s.id, s.contact_id, s.created_at, s.last_message_at,
               s.message_ttl_ms, s.sensitivity, s.notif_behavior,
               c.display_name, c.public_key, c.device_id
               FROM sessions s LEFT JOIN contacts c ON s.contact_id=c.id
               WHERE s.contact_id=? LIMIT 1""",
            arrayOf(contactId)
        )
        return cursor.use { c ->
            if (c.moveToFirst()) c.toSession() else null
        }
    }

    fun getByContactDeviceId(deviceId: String): Session? {
        val cursor = db.rawQuery(
            """SELECT s.id, s.contact_id, s.created_at, s.last_message_at,
               s.message_ttl_ms, s.sensitivity, s.notif_behavior,
               c.display_name, c.public_key, c.device_id
               FROM sessions s
               LEFT JOIN contacts c ON s.contact_id=c.id
               WHERE c.device_id=? LIMIT 1""",
            arrayOf(deviceId)
        )
        return cursor.use { c ->
            if (c.moveToFirst()) c.toSession() else null
        }
    }

    fun updateLastMessage(sessionId: String, timestamp: Long) {
        val cv = ContentValues().apply { put("last_message_at", timestamp) }
        db.update("sessions", cv, "id=?", arrayOf(sessionId))
    }

    fun rename(sessionId: String, newName: String) {
        val cv = ContentValues().apply { put("display_name", newName) }
        db.update("sessions", cv, "id=?", arrayOf(sessionId))
    }

    fun delete(id: String) {
        db.delete("sessions", "id=?", arrayOf(id))
    }

    private fun android.database.Cursor.toSession() = Session(
        id = getString(0),
        contactId = getString(1),
        createdAt = getLong(2),
        lastMessageAt = if (isNull(3)) null else getLong(3),
        messageTtlMs = getLong(4),
        sensitivity = getInt(5),
        notifBehavior = getInt(6),
        contactName = getString(7) ?: "",
        contactPublicKey = getString(8) ?: "",
        contactDeviceId = getString(9) ?: ""
    )

    private fun Session.toContentValues() = ContentValues().apply {
        put("id", id)
        put("contact_id", contactId)
        put("created_at", createdAt)
        lastMessageAt?.let { put("last_message_at", it) } ?: putNull("last_message_at")
        put("message_ttl_ms", messageTtlMs)
        put("sensitivity", sensitivity)
        put("notif_behavior", notifBehavior)
    }
}
