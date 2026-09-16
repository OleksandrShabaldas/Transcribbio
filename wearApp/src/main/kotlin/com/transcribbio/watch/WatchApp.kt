package com.transcribbio.watch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.transcribbio.shared.update.AndroidUpdater

const val WATCH_RECORDING_CHANNEL_ID = "watch-recording"

object WatchGraph {
    lateinit var updater: AndroidUpdater
        private set

    fun init(context: Context) {
        updater = AndroidUpdater(context.applicationContext, BuildConfig.VERSION_NAME, "watch")
    }
}

class WatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WatchQueue.init(this)
        WatchGraph.init(this)
        val channel = NotificationChannel(
            WATCH_RECORDING_CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Ongoing lecture recording" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
