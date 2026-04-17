package io.raaz.messenger.worker

import android.content.Context
import androidx.work.*
import io.raaz.messenger.crypto.CryptoManager
import io.raaz.messenger.data.db.RaazDatabase
import io.raaz.messenger.data.db.dao.MessageDao
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
        if (!CryptoManager.isReady()) {
            AppLogger.d(TAG, "App is locked — skipping sync")
            return Result.success()
        }

        AppLogger.d(TAG, "Starting background sync")

        return try {
            // DB key must be available (app unlocked)
            val dbKey = inputData.getString(KEY_DB_KEY) ?: return Result.success()
            val db = RaazDatabase.getInstance(applicationContext, dbKey)
            val settings = SettingsDao(db.db).get()
            val prefs = RaazPreferences(applicationContext)

            val repo = MessageRepository(
                MessageDao(db.db), SessionDao(db.db), prefs, settings.serverUrl, applicationContext
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

    companion object {
        const val KEY_DB_KEY = "db_key"
        private const val WORK_NAME = "raaz_sync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
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
