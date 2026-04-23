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
import io.raaz.messenger.util.AppLogger
import java.util.concurrent.TimeUnit

class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val TAG = "CleanupWorker"

    override suspend fun doWork(): Result {
        if (!CryptoManager.isReady()) return Result.success()

        return try {
            val dbKey = inputData.getString(SyncWorker.KEY_DB_KEY) ?: return Result.success()
            val db = RaazDatabase.getInstance(applicationContext, dbKey)
            val settings = SettingsDao(db.db).get()
            val prefs = RaazPreferences(applicationContext)

            val repo = MessageRepository(
                MessageDao(db.db), SessionDao(db.db), PendingMessageDao(db.db), prefs, settings.serverUrl
            )
            repo.deleteExpired()
            AppLogger.d(TAG, "Expired messages cleaned up")
            Result.success()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Cleanup failed: ${e.message}", e)
            Result.failure()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CleanupWorker>(6, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "raaz_cleanup",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
