package io.raaz.messenger.data.db.dao

import android.content.ContentValues
import io.raaz.messenger.data.model.Contact
import net.zetetic.database.sqlcipher.SQLiteDatabase

class ContactDao(private val db: SQLiteDatabase) {

    fun insert(contact: Contact) {
        val cv = contact.toContentValues()
        db.insertWithOnConflict("contacts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAll(): List<Contact> {
        val cursor = db.rawQuery(
            "SELECT id, display_name, public_key, device_id, server_url, added_at, last_seen, is_verified FROM contacts ORDER BY display_name ASC",
            null
        )
        return cursor.use { c ->
            val list = mutableListOf<Contact>()
            while (c.moveToNext()) list.add(c.toContact())
            list
        }
    }

    fun getById(id: String): Contact? {
        val cursor = db.rawQuery(
            "SELECT id, display_name, public_key, device_id, server_url, added_at, last_seen, is_verified FROM contacts WHERE id=?",
            arrayOf(id)
        )
        return cursor.use { if (it.moveToFirst()) it.toContact() else null }
    }

    fun delete(id: String) {
        db.delete("contacts", "id=?", arrayOf(id))
    }

    fun rename(id: String, newName: String) {
        val cv = ContentValues().apply { put("display_name", newName) }
        db.update("contacts", cv, "id=?", arrayOf(id))
    }

    fun updateLastSeen(id: String, timestamp: Long) {
        val cv = ContentValues().apply { put("last_seen", timestamp) }
        db.update("contacts", cv, "id=?", arrayOf(id))
    }

    fun updateKeyAndDevice(id: String, publicKey: String, deviceId: String, serverUrl: String) {
        val cv = ContentValues().apply {
            put("public_key", publicKey)
            put("device_id", deviceId)
            put("server_url", serverUrl)
            put("is_verified", 1)
        }
        db.update("contacts", cv, "id=?", arrayOf(id))
    }

    private fun Contact.toContentValues() = ContentValues().apply {
        put("id", id)
        put("display_name", displayName)
        put("public_key", publicKey)
        put("device_id", deviceId)
        put("server_url", serverUrl)
        put("added_at", addedAt)
        lastSeen?.let { put("last_seen", it) } ?: putNull("last_seen")
        put("is_verified", if (isVerified) 1 else 0)
    }

    private fun android.database.Cursor.toContact() = Contact(
        id = getString(0),
        displayName = getString(1),
        publicKey = getString(2),
        deviceId = getString(3),
        serverUrl = getString(4),
        addedAt = getLong(5),
        lastSeen = if (isNull(6)) null else getLong(6),
        isVerified = getInt(7) == 1
    )
}
