package io.raaz.messenger.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.raaz.messenger.util.AppLogger
import io.raaz.messenger.worker.SyncWorker
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AppLogger.i("BootReceiver", "Boot complete — scheduling sync worker")
            SyncWorker.schedule(context)
        }
    }
}
