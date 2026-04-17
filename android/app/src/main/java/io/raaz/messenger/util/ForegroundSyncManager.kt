package io.raaz.messenger.util

import android.content.Context
import io.raaz.messenger.crypto.CryptoManager
import io.raaz.messenger.data.db.RaazDatabase
import io.raaz.messenger.data.db.dao.MessageDao
import io.raaz.messenger.data.db.dao.SessionDao
import io.raaz.messenger.data.db.dao.SettingsDao
import io.raaz.messenger.data.preferences.RaazPreferences
import io.raaz.messenger.data.repository.MessageRepository
import io.raaz.messenger.notification.RaazNotificationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ForegroundSyncManager {

    private const val TAG = "FgSync"
    private const val POLL_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private var dbKey: String? = null
    private var appContext: Context? = null

    fun init(context: Context, dbKey: String) {
        this.appContext = context.applicationContext
        this.dbKey = dbKey
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                sync()
                delay(POLL_INTERVAL_MS)
            }
        }
        AppLogger.d(TAG, "Foreground polling started (every ${POLL_INTERVAL_MS / 1000}s)")
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        AppLogger.d(TAG, "Foreground polling stopped")
    }

    suspend fun syncNow(): Int {
        return sync()
    }

    private suspend fun sync(): Int = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext 0
        val key = dbKey ?: return@withContext 0
        if (!CryptoManager.isReady()) return@withContext 0

        _isSyncing.value = true
        try {
            val db = RaazDatabase.getInstance(ctx, key)
            val settings = SettingsDao(db.db).get()
            val prefs = RaazPreferences(ctx)
            val repo = MessageRepository(
                MessageDao(db.db), SessionDao(db.db), prefs, settings.serverUrl, ctx
            )
            val sent = repo.syncOutgoing()
            val received = repo.syncIncoming()
            if (received > 0) {
                RaazNotificationManager.showNewMessageNotification(ctx, received)
                AppLogger.i(TAG, "Sync: sent=$sent received=$received")
            }
            received
        } catch (e: Exception) {
            AppLogger.e(TAG, "Sync error: ${e.message}", e)
            0
        } finally {
            _isSyncing.value = false
        }
    }
}
