package io.raaz.messenger.data.db

import android.content.ContentValues
import android.content.Context
import io.raaz.messenger.util.AppLogger
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDB
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

class AuthDatabase private constructor(context: Context, key: ByteArray) :
    SQLiteOpenHelper(context, DB_NAME, key, null, DB_VERSION, 0, null, null, true) {

    val db: CipherDB get() = writableDatabase

    override fun onOpen(db: CipherDB) {
        db.rawQuery("PRAGMA secure_delete = ON", null)?.close()
    }

    override fun onCreate(db: CipherDB) {

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS lock_state (
                id INTEGER PRIMARY KEY DEFAULT 1,
                is_locked INTEGER NOT NULL DEFAULT 1,
                locked_at INTEGER,
                lockout_until INTEGER,
                fail_streak INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS login_attempts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                attempted_at INTEGER NOT NULL,
                success INTEGER NOT NULL DEFAULT 0,
                attempt_hash TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_attempts_time ON login_attempts(attempted_at)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS app_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                level TEXT NOT NULL,
                tag TEXT NOT NULL,
                message TEXT NOT NULL,
                stack_trace TEXT
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_ts ON app_logs(timestamp)")
        val cv = ContentValues().apply { put("id", 1) }
        db.insertWithOnConflict("lock_state", null, cv, CipherDB.CONFLICT_IGNORE)
        AppLogger.d(TAG, "AuthDatabase schema created")
    }

    override fun onUpgrade(db: CipherDB, oldVersion: Int, newVersion: Int) {
        AppLogger.i(TAG, "AuthDatabase upgrade $oldVersion -> $newVersion")
    }

    // --- Lock state ---

    data class LockState(
        val isLocked: Boolean,
        val lockedAt: Long?,
        val lockoutUntil: Long?,
        val failStreak: Int
    )

    fun getLockState(): LockState {
        val c = db.rawQuery("SELECT is_locked, locked_at, lockout_until, fail_streak FROM lock_state WHERE id=1", null)
        return c.use {
            if (it.moveToFirst()) LockState(
                isLocked = it.getInt(0) == 1,
                lockedAt = if (it.isNull(1)) null else it.getLong(1),
                lockoutUntil = if (it.isNull(2)) null else it.getLong(2),
                failStreak = it.getInt(3)
            ) else LockState(true, null, null, 0)
        }
    }

    fun recordFailedAttempt(): LockState {
        val now = System.currentTimeMillis()
        val current = getLockState()
        val newStreak = current.failStreak + 1
        val lockoutUntil: Long? = if (newStreak in 3..8) now + 30 * 60 * 1000L else null

        val cv = ContentValues().apply {
            put("is_locked", 1)
            put("locked_at", now)
            put("fail_streak", newStreak)
            if (lockoutUntil != null) put("lockout_until", lockoutUntil) else putNull("lockout_until")
        }
        db.update("lock_state", cv, "id=1", null)
        insertAttemptRecord(now, success = false, streak = newStreak)
        return getLockState()
    }

    fun recordSuccessfulUnlock() {
        val cv = ContentValues().apply {
            put("is_locked", 0)
            put("locked_at", System.currentTimeMillis())
            put("fail_streak", 0)
            putNull("lockout_until")
        }
        db.update("lock_state", cv, "id=1", null)
        insertAttemptRecord(System.currentTimeMillis(), success = true, streak = 0)
    }

    fun setLocked(locked: Boolean) {
        val cv = ContentValues().apply {
            put("is_locked", if (locked) 1 else 0)
            if (locked) put("locked_at", System.currentTimeMillis())
        }
        db.update("lock_state", cv, "id=1", null)
    }

    private fun insertAttemptRecord(at: Long, success: Boolean, streak: Int) {
        val hash = "attempt_${streak}_${at}".hashCode().toString()
        val cv = ContentValues().apply {
            put("attempted_at", at)
            put("success", if (success) 1 else 0)
            put("attempt_hash", hash)
        }
        db.insert("login_attempts", null, cv)
    }

    fun insertLog(entry: AppLogger.LogEntry) {
        try {
            val cv = ContentValues().apply {
                put("timestamp", entry.timestamp)
                put("level", entry.level)
                put("tag", entry.tag)
                put("message", entry.message)
                entry.stackTrace?.let { put("stack_trace", it) }
            }
            db.insert("app_logs", null, cv)
            db.delete("app_logs", "id NOT IN (SELECT id FROM app_logs ORDER BY timestamp DESC LIMIT 2000)", null)
        } catch (_: Exception) { }
    }

    fun getLogs(level: String? = null, query: String? = null, limit: Int = 500): List<AppLogger.LogEntry> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (level != null && level != "ALL") { conditions.add("level = ?"); args.add(level) }
        if (!query.isNullOrBlank()) {
            conditions.add("(message LIKE ? OR tag LIKE ?)")
            args.add("%$query%"); args.add("%$query%")
        }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        val sql = "SELECT id, timestamp, level, tag, message, stack_trace FROM app_logs $where ORDER BY timestamp DESC LIMIT $limit"
        val c = db.rawQuery(sql, args.toTypedArray())
        return c.use {
            val list = mutableListOf<AppLogger.LogEntry>()
            while (it.moveToNext()) {
                list.add(AppLogger.LogEntry(
                    id = it.getLong(0), timestamp = it.getLong(1),
                    level = it.getString(2), tag = it.getString(3),
                    message = it.getString(4),
                    stackTrace = if (it.isNull(5)) null else it.getString(5)
                ))
            }
            list
        }
    }

    fun clearLogs() { db.delete("app_logs", null, null) }

    fun shouldWipe(): Boolean = getLockState().failStreak >= 9

    companion object {
        private const val TAG = "AuthDB"
        private const val DB_NAME = "raaz_auth.db"
        private const val DB_VERSION = 1

        @Volatile private var instance: AuthDatabase? = null

        fun getInstance(context: Context, key: String): AuthDatabase =
            instance ?: synchronized(this) {
                instance ?: AuthDatabase(context.applicationContext, keyToBytes(key))
                    .also { instance = it }
            }

        private fun keyToBytes(key: String): ByteArray =
            if (key.startsWith("x'") && key.endsWith("'")) {
                val hex = key.substring(2, key.length - 1)
                ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            } else key.toByteArray(Charsets.UTF_8)
    }
}
