package io.raaz.messenger

import android.app.Application
import android.content.Context
import io.raaz.messenger.crypto.PasswordManager
import io.raaz.messenger.data.db.AuthDatabase
import io.raaz.messenger.notification.RaazNotificationManager
import io.raaz.messenger.util.AppLogger
import io.raaz.messenger.util.LocaleManager
import io.raaz.messenger.util.SessionLockManager
import io.raaz.messenger.worker.CleanupWorker
import io.raaz.messenger.worker.SyncWorker

class RaazApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(base))
    }

    override fun onCreate() {
        super.onCreate()

        // Init AuthDatabase and wire log writer
        val authDbKey = PasswordManager.generateAuthDbKey(this)
        val authDb = AuthDatabase.getInstance(this, authDbKey)
        AppLogger.dbWriter = { entry -> authDb.insertLog(entry) }

        AppLogger.i("RaazApp", "Application started")

        RaazNotificationManager.createChannel(this)
        SessionLockManager.init(timeoutMs = 5 * 60 * 1000L)

        // Schedule background workers
        SyncWorker.schedule(this)
        CleanupWorker.schedule(this)
    }
}
