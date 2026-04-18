package io.raaz.messenger.data.db

import android.content.ContentValues
import android.content.Context
import io.raaz.messenger.util.AppLogger
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDB
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

class RaazDatabase private constructor(context: Context, key: ByteArray) :
    SQLiteOpenHelper(context, DB_NAME, key, null, DB_VERSION, 0, null, null, true) {

    val db: CipherDB get() = writableDatabase

    override fun onConfigure(db: CipherDB) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: CipherDB) {
        db.rawQuery("PRAGMA secure_delete = ON", null)?.close()
    }

    override fun onCreate(db: CipherDB) {

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS contacts (
                id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                public_key TEXT NOT NULL,
                device_id TEXT NOT NULL,
                server_url TEXT NOT NULL,
                added_at INTEGER NOT NULL,
                last_seen INTEGER,
                is_verified INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT PRIMARY KEY,
                contact_id TEXT NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL,
                last_message_at INTEGER,
                message_ttl_ms INTEGER NOT NULL DEFAULT 86400000,
                sensitivity INTEGER NOT NULL DEFAULT 0,
                notif_behavior INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_contact ON sessions(contact_id)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                direction INTEGER NOT NULL,
                ciphertext TEXT NOT NULL,
                plaintext_cache TEXT,
                status INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                expires_at INTEGER,
                server_msg_id TEXT,
                nonce TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_status ON messages(status)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS app_settings (
                id INTEGER PRIMARY KEY DEFAULT 1,
                lock_timeout_ms INTEGER NOT NULL DEFAULT 300000,
                language TEXT NOT NULL DEFAULT 'fa',
                theme INTEGER NOT NULL DEFAULT 0,
                server_url TEXT NOT NULL DEFAULT 'http://relay.rahejanan.ir',
                notif_enabled INTEGER NOT NULL DEFAULT 1,
                setup_complete INTEGER NOT NULL DEFAULT 0,
                user_id TEXT,
                device_id TEXT,
                public_key TEXT,
                private_key_encrypted TEXT
            )
        """.trimIndent())

        val cv = ContentValues().apply { put("id", 1) }
        db.insertWithOnConflict("app_settings", null, cv, CipherDB.CONFLICT_IGNORE)
        AppLogger.d(TAG, "RaazDatabase schema created")
    }

    override fun onUpgrade(db: CipherDB, oldVersion: Int, newVersion: Int) {
        AppLogger.i(TAG, "RaazDatabase upgrade $oldVersion -> $newVersion")
    }

    companion object {
        private const val TAG = "RaazDB"
        const val DB_NAME = "raaz_main.db"
        private const val DB_VERSION = 1

        @Volatile private var instance: RaazDatabase? = null

        // key is "x'hexstring'" format from PasswordManager — extract raw bytes
        fun getInstance(context: Context, key: String): RaazDatabase =
            instance ?: synchronized(this) {
                instance ?: RaazDatabase(context.applicationContext, keyToBytes(key))
                    .also { instance = it }
            }

        fun close() {
            instance?.writableDatabase?.close()
            instance = null
        }

        fun exists(context: Context): Boolean =
            context.getDatabasePath(DB_NAME).exists()

        private fun keyToBytes(key: String): ByteArray {
            // key format: "x'<hex>'" → decode hex to raw bytes
            return if (key.startsWith("x'") && key.endsWith("'")) {
                val hex = key.substring(2, key.length - 1)
                ByteArray(hex.length / 2) { i ->
                    hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } else {
                key.toByteArray(Charsets.UTF_8)
            }
        }
    }
}
