package com.transcribbio.watch

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class WatchRecordingService : Service() {

    companion object {
        const val ACTION_START = "com.transcribbio.watch.START"
        const val ACTION_STOP = "com.transcribbio.watch.STOP"
        const val EXTRA_FILE = "file"
        const val NOTIF_ID = 5812
    }

    private var recorder: MediaRecorder? = null
    private var scope: CoroutineScope? = null
    private var currentFile: File? = null
    private var startedAt: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (recorder != null) return
        val filePath = intent.getStringExtra(EXTRA_FILE) ?: return
        currentFile = File(filePath)
        startedAt = System.currentTimeMillis()
        startForegroundCompat(buildNotification("0:00"))
        try {
            recorder = createRecorder(filePath).apply { start() }
        } catch (e: Exception) {
            currentFile?.delete()
            runCatching { recorder?.release() }
            recorder = null
            WatchRecordingController.onStopped()
            stopForegroundCompat(); stopSelf()
            return
        }
        WatchRecordingController.onStarted(startedAt)
        startTicker()
    }

    private fun stopRecording() {
        scope?.cancel(); scope = null
        val r = recorder; recorder = null
        runCatching { r?.stop() }
        runCatching { r?.release() }
        val file = currentFile
        if (file != null && file.exists() && file.length() > 0) {
            WatchQueue.onRecorded()
        } else {
            file?.delete()
        }
        WatchRecordingController.onStopped()
        stopForegroundCompat(); stopSelf()
    }

    private fun createRecorder(filePath: String): MediaRecorder {
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(16000)
        r.setAudioEncodingBitRate(48000)
        r.setOutputFile(filePath)
        r.prepare()
        return r
    }

    private fun startTicker() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            val nm = getSystemService(NotificationManager::class.java)
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startedAt
                WatchRecordingController.onTick(elapsed)
                nm.notify(NOTIF_ID, buildNotification(formatElapsed(elapsed)))
                delay(1000)
            }
        }
    }

    private fun buildNotification(elapsedText: String): Notification {
        val stopIntent = Intent(this, WatchRecordingService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, WATCH_RECORDING_CHANNEL_ID)
            .setContentTitle("Recording")
            .setContentText(elapsedText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun formatElapsed(ms: Long): String {
        val s = (ms / 1000).toInt(); val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
