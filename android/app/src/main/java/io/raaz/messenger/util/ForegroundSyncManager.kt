package io.raaz.messenger.util

import android.content.Context
import io.raaz.messenger.crypto.CryptoManager
import io.raaz.messenger.data.db.RaazDatabase
import io.raaz.messenger.data.db.dao.MessageDao
import io.raaz.messenger.data.db.dao.PendingMessageDao
import io.raaz.messenger.data.db.dao.SessionDao
import io.raaz.messenger.data.db.dao.SettingsDao
import io.raaz.messenger.data.network.PocketBaseRealtimeClient
import io.raaz.messenger.data.preferences.RaazPreferences
import io.raaz.messenger.data.repository.MessageRepository
import io.raaz.messenger.notification.RaazNotificationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ForegroundSyncManager {

    private val TAG = AppLogger.Cat.SYNC
    private const val POLL_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null
    private var sseJob: Job? = null
    private val syncMutex = kotlinx.coroutines.sync.Mutex()

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

        // SSE realtime — triggers immediate sync on new message event
        startSse()

        // Fallback polling — first tick delayed so it doesn't race with syncNow()
        pollJob = scope.launch {
            delay(POLL_INTERVAL_MS)
            while (isActive) {
                sync()
                delay(POLL_INTERVAL_MS)
            }
        }
        AppLogger.i(TAG, "=== FOREGROUND POLLING STARTED (every ${POLL_INTERVAL_MS / 1000}s) ===")
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        sseJob?.cancel()
        sseJob = null
        PocketBaseRealtimeClient.disconnect()
        AppLogger.i(TAG, "=== FOREGROUND POLLING STOPPED ===")
    }

    private fun startSse() {
        val ctx = appContext ?: return
        val prefs = RaazPreferences(ctx)
        val token = prefs.bearerToken ?: return
        val deviceId = prefs.deviceId ?: return

        val db = try {
            val key = dbKey ?: return
            RaazDatabase.getInstance(ctx, key)
        } catch (e: Exception) { return }
        val serverUrl = try { SettingsDao(db.db).get().serverUrl } catch (e: Exception) { return }

        PocketBaseRealtimeClient.connect(serverUrl, token, deviceId)

        sseJob = scope.launch {
            PocketBaseRealtimeClient.newMessageEvents.collect {
                AppLogger.i(TAG, "SSE event received — syncing incoming")
                sync()
            }
        }
        AppLogger.i(TAG, "SSE realtime listener started")
    }

    suspend fun syncNow(): Int {
        AppLogger.i(TAG, "Manual sync triggered")
        return sync()
    }

    private suspend fun sync(): Int {
        if (!syncMutex.tryLock()) {
            AppLogger.d(TAG, "sync: already running — skipping")
            return 0
        }
        return try {
            doSync()
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun doSync(): Int = withContext(Dispatchers.IO) {
        val ctx = appContext ?: run {
            AppLogger.w(TAG, "sync: no context — skipping (init() not called?)")
            return@withContext 0
        }
        val key = dbKey ?: run {
            AppLogger.w(TAG, "sync: no dbKey — skipping (init() not called?)")
            return@withContext 0
        }
        if (!CryptoManager.isReady()) {
            AppLogger.d(TAG, "sync: CryptoManager not ready (app locked) — skipping")
            return@withContext 0
        }

        _isSyncing.value = true
        try {
            AppLogger.d(TAG, "--- sync tick ---")
            val db = RaazDatabase.getInstance(ctx, key)
            val settings = SettingsDao(db.db).get()
            val prefs = RaazPreferences(ctx)
            val repo = MessageRepository(
                MessageDao(db.db), SessionDao(db.db), PendingMessageDao(db.db), prefs, settings.serverUrl, ctx
            )
            val sent = repo.syncOutgoing()
            val received = repo.syncIncoming()
            val confirmed = repo.syncReceipts()
            if (sent > 0 || received > 0 || confirmed > 0) {
                AppLogger.i(TAG, "Sync result: sent=$sent received=$received confirmed=$confirmed")
                if (received > 0) RaazNotificationManager.showNewMessageNotification(ctx, received)
            }
            received
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Sync exception: ${e.message}", e)
            0
        } finally {
            _isSyncing.value = false
        }
    }
}
