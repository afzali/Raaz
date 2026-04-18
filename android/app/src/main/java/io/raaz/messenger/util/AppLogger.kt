package io.raaz.messenger.util

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AppLogger {

    private const val MAX_IN_MEMORY = 1000
    private val scope = CoroutineScope(Dispatchers.IO)

    private val buffer = ArrayDeque<LogEntry>(MAX_IN_MEMORY)

    var dbWriter: ((LogEntry) -> Unit)? = null

    // ── Categories ────────────────────────────────────────────────────────────
    // Filter in logcat: adb logcat -s RAAZ_AUTH RAAZ_CRYPTO RAAZ_NET RAAZ_DB RAAZ_UI RAAZ_SYNC
    object Cat {
        const val AUTH   = "RAAZ_AUTH"   // login, unlock, setup, lock
        const val CRYPTO = "RAAZ_CRYPTO" // key gen, encrypt, decrypt
        const val NET    = "RAAZ_NET"    // all HTTP requests & responses
        const val DB     = "RAAZ_DB"     // database read/write
        const val UI     = "RAAZ_UI"     // fragment lifecycle, user actions
        const val SYNC   = "RAAZ_SYNC"   // background sync, polling
        const val QR     = "RAAZ_QR"     // QR / invite code encode/decode
        const val NOTIF  = "RAAZ_NOTIF"  // notifications
    }

    data class LogEntry(
        val id: Long = 0,
        val timestamp: Long = System.currentTimeMillis(),
        val level: String,
        val tag: String,
        val message: String,
        val stackTrace: String? = null
    )

    fun d(tag: String, msg: String) = log("DEBUG", tag, msg)
    fun i(tag: String, msg: String) = log("INFO",  tag, msg)
    fun w(tag: String, msg: String) = log("WARN",  tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        log("ERROR", tag, msg, t?.stackTraceToString())

    private fun log(level: String, tag: String, msg: String, stackTrace: String? = null) {
        val line = "[$tag] $msg"
        when (level) {
            "DEBUG" -> Log.d(tag, msg)
            "INFO"  -> Log.i(tag, msg)
            "WARN"  -> Log.w(tag, msg)
            "ERROR" -> Log.e(tag, msg, stackTrace?.let { Throwable(it) })
        }
        val entry = LogEntry(level = level, tag = tag, message = msg, stackTrace = stackTrace)
        synchronized(buffer) {
            if (buffer.size >= MAX_IN_MEMORY) buffer.removeFirst()
            buffer.addLast(entry)
        }
        scope.launch { dbWriter?.invoke(entry) }
    }

    fun getBuffer(): List<LogEntry> = synchronized(buffer) { buffer.toList() }
    fun clearBuffer() = synchronized(buffer) { buffer.clear() }
}
