package io.raaz.messenger.util

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AppLogger {

    private const val MAX_IN_MEMORY = 500
    private val scope = CoroutineScope(Dispatchers.IO)

    // In-memory ring buffer for immediate display; AuthDatabase writes happen async
    private val buffer = ArrayDeque<LogEntry>(MAX_IN_MEMORY)

    // Set by RaazApplication after AuthDatabase is ready
    var dbWriter: ((LogEntry) -> Unit)? = null

    data class LogEntry(
        val id: Long = 0,
        val timestamp: Long = System.currentTimeMillis(),
        val level: String,
        val tag: String,
        val message: String,
        val stackTrace: String? = null
    )

    fun d(tag: String, msg: String) = log("DEBUG", tag, msg)
    fun i(tag: String, msg: String) = log("INFO", tag, msg)
    fun w(tag: String, msg: String) = log("WARN", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        log("ERROR", tag, msg, t?.stackTraceToString())

    private fun log(level: String, tag: String, msg: String, stackTrace: String? = null) {
        when (level) {
            "DEBUG" -> Log.d(tag, msg)
            "INFO"  -> Log.i(tag, msg)
            "WARN"  -> Log.w(tag, msg)
            "ERROR" -> Log.e(tag, msg)
        }
        val entry = LogEntry(
            level = level, tag = tag, message = msg, stackTrace = stackTrace
        )
        synchronized(buffer) {
            if (buffer.size >= MAX_IN_MEMORY) buffer.removeFirst()
            buffer.addLast(entry)
        }
        scope.launch { dbWriter?.invoke(entry) }
    }

    fun getBuffer(): List<LogEntry> = synchronized(buffer) { buffer.toList() }

    fun clearBuffer() = synchronized(buffer) { buffer.clear() }
}
