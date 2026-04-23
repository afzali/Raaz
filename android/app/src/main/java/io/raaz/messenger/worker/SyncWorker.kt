package io.raaz.messenger.worker

import android.content.Context
import androidx.work.*
import io.raaz.messenger.crypto.CryptoManager
import io.raaz.messenger.data.db.RaazDatabase
import io.raaz.messenger.data.db.dao.MessageDao
import io.raaz.messenger.data.db.dao.PendingMessageDao
import io.raaz.messenger.data.db.dao.SessionDao
import io.raaz.messenger.data.db.dao.SettingsDao
import io.raaz.messenger.data.preferences.RaazPreferences
import io.raaz.messenger.data.repository.MessageRepository
import io.raaz.messenger.notification.RaazNotificationManager
import io.raaz.messenger.util.AppLogger
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val TAG = "SyncWorker"

    override suspend fun doWork(): Result {
        val prefs = RaazPreferences(applicationContext)

        // When app is locked, we can't decrypt the DB. But we can still check the server
        // for pending messages using the bearer token (stored in EncryptedSharedPreferences)
        // and show a generic notification.
        if (!CryptoManager.isReady()) {
            return runLockedCheck(prefs)
        }

        AppLogger.d(TAG, "Starting background sync")

        return try {
            // DB key from input data (runOnce) or fallback to skipping full sync
            val dbKey = inputData.getString(KEY_DB_KEY)
            if (dbKey == null) {
                AppLogger.d(TAG, "No dbKey in input — doing lightweight locked check")
                return runLockedCheck(prefs)
            }

            val db = RaazDatabase.getInstance(applicationContext, dbKey)
            val settings = SettingsDao(db.db).get()

            val repo = MessageRepository(
                MessageDao(db.db), SessionDao(db.db), PendingMessageDao(db.db), prefs, settings.serverUrl, applicationContext
            )

            val sent = repo.syncOutgoing()
            val received = repo.syncIncoming()

            AppLogger.i(TAG, "Sync complete: sent=$sent, received=$received")

            if (received > 0) {
                RaazNotificationManager.showNewMessageNotification(applicationContext, received)
            }

            repo.deleteExpired()
            Result.success()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Sync failed: ${e.message}", e)
            Result.retry()
        }
    }

    /**
     * Lightweight check while app is locked: hit /messages endpoint and
     * if there are pending messages, show a generic notification.
     * Does NOT ack or process messages (they're decrypted on next unlock).
     */
    private suspend fun runLockedCheck(prefs: RaazPreferences): Result {
        val token = prefs.bearerToken
        val serverUrl = prefs.serverUrl
        if (token == null || serverUrl.isNullOrBlank()) {
            AppLogger.d(TAG, "Locked check skipped: token/serverUrl missing")
            return Result.success()
        }
        return try {
            AppLogger.d(TAG, "Locked — doing lightweight message count check")
            val api = io.raaz.messenger.data.network.RaazApiService.get(serverUrl)
            val resp = api.pullMessages("Bearer $token")
            if (resp.isSuccessful) {
                val count = resp.body()?.messages?.size ?: 0
                if (count > 0) {
                    AppLogger.i(TAG, "Locked check: $count pending message(s) — showing notification")
                    RaazNotificationManager.showNewMessageNotification(applicationContext, count)
                } else {
                    AppLogger.d(TAG, "Locked check: no pending messages")
                }
            } else {
                AppLogger.w(TAG, "Locked check HTTP ${resp.code()}")
            }
            Result.success()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Locked check failed: ${e.message}")
            Result.success()  // don't retry too aggressively
        }
    }

    companion object {
        const val KEY_DB_KEY = "db_key"
        private const val WORK_NAME = "raaz_sync"

        fun schedule(context: Context, intervalMinutes: Int = 15) {
            // Cancel existing work if interval is 0 (disabled)
            if (intervalMinutes <= 0) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                AppLogger.i("SyncWorker", "Background sync disabled")
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
            AppLogger.i("SyncWorker", "Scheduled background sync every $intervalMinutes minutes")
        }

        fun runOnce(context: Context, dbKey: String) {
            val data = workDataOf(KEY_DB_KEY to dbKey)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
