package com.transcribbio.phone.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.transcribbio.phone.AppGraph
import com.transcribbio.phone.R
import com.transcribbio.phone.UPLOAD_CHANNEL_ID
import java.util.concurrent.TimeUnit

/**
 * Delivers queued recordings (and renames) to the desktop — directly over Wi-Fi, or through the
 * Google Drive relay — in the background, even with the screen off.
 *
 * Why it survives sleep: when there is data to move, the job promotes itself to a foreground
 * "data sync" service (with an "Uploading…" notification), which may run past the 10-minute job
 * limit and keeps network access while the phone dozes. If Android refuses that (background-start
 * limits, when the app isn't exempt from battery optimisation), it still runs as a normal job, and
 * Drive uploads resume from their saved session, so each run makes progress rather than restarting.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = AppGraph.store
        if (!store.hasPendingWork()) return Result.success() // nothing to do: don't touch the network
        if (store.hasUploadWork()) runCatching { setForeground(foregroundInfo()) }
        return try {
            if (AppGraph.sync.syncNow()) Result.success() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    /** Needed for expedited work on Android 11 and older, where it runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, UPLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Sending lectures to your PC")
            .setContentText("Uploading recordings in the background…")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val PERIODIC = "transcribbio-sync-periodic"
        private const val ONESHOT = "transcribbio-sync-now"
        private const val NOTIFICATION_ID = 4711

        /** Wi-Fi only unless the user allowed mobile data for uploads (or asked for a sync now). */
        private fun constraints(anyNetwork: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (anyNetwork) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .build()

        private fun anyNetwork(userAsked: Boolean) =
            userAsked || runCatching { AppGraph.prefs.cloudOnMobileData.value }.getOrDefault(false)

        /** Start delivering now (e.g. right after a recording). Queued behind a running sync,
         *  never cancelling it — an upload in progress keeps going. */
        fun kick(context: Context, userAsked: Boolean = false) {
            val wm = WorkManager.getInstance(context)
            fun request(expedited: Boolean) = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints(anyNetwork(userAsked)))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
                .build()
            // This runs right after a recording stops — it must never crash: if WorkManager ever
            // rejects the expedited request, fall back to a normal one.
            runCatching { wm.enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.APPEND_OR_REPLACE, request(true)) }
                .onFailure { runCatching { wm.enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.APPEND_OR_REPLACE, request(false)) } }
        }

        /** Safety net every 15 min; UPDATE so a changed mobile-data setting takes effect. */
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints(anyNetwork(false)))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
