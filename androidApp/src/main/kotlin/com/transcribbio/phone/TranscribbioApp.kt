package com.transcribbio.phone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.transcribbio.phone.core.Prefs
import com.transcribbio.phone.data.RecordingStore
import com.transcribbio.phone.sync.SyncManager
import com.transcribbio.phone.sync.SyncWorker
import com.transcribbio.shared.update.AndroidUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

const val RECORDING_CHANNEL_ID = "recording"

/** Simple manual DI graph, initialised from the Application. */
object AppGraph {
    lateinit var prefs: Prefs
        private set
    lateinit var store: RecordingStore
        private set
    lateinit var sync: SyncManager
        private set
    lateinit var updater: AndroidUpdater
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        val app = context.applicationContext
        prefs = Prefs(app)
        store = RecordingStore(app)
        sync = SyncManager(app, prefs, store, appScope)
        updater = AndroidUpdater(app, BuildConfig.VERSION_NAME, "phone")
    }
}

class TranscribbioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        createRecordingChannel()
        SyncWorker.schedulePeriodic(this)
    }

    private fun createRecordingChannel() {
        val channel = NotificationChannel(
            RECORDING_CHANNEL_ID,
            getString(R.string.recording_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Ongoing lecture recording" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
