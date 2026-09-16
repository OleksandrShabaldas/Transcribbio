package com.transcribbio.phone.recording

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
import com.transcribbio.phone.AppGraph
import com.transcribbio.phone.R
import com.transcribbio.phone.RECORDING_CHANNEL_ID
import com.transcribbio.phone.data.PendingRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.transcribbio.phone.START"
        const val ACTION_STOP = "com.transcribbio.phone.STOP"
        const val EXTRA_ID = "id"
        const val EXTRA_FILE = "file"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LANG = "lang"
        const val NOTIF_ID = 4711
    }

    private var recorder: MediaRecorder? = null
    private var scope: CoroutineScope? = null
    private var currentId: String? = null
    private var currentTitle: String = "Lecture"
    private var currentLang: String = "sk"
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
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val filePath = intent.getStringExtra(EXTRA_FILE) ?: return
        currentId = id
        currentTitle = intent.getStringExtra(EXTRA_TITLE)?.ifBlank { "Lecture" } ?: "Lecture"
        currentLang = intent.getStringExtra(EXTRA_LANG) ?: "sk"
        currentFile = File(filePath)
        startedAt = System.currentTimeMillis()

        startForegroundCompat(buildNotification("0:00"))

        try {
            recorder = createRecorder(filePath).apply { start() }
        } catch (e: Exception) {
            currentFile?.delete()
            recorder?.runCatching { release() }
            recorder = null
            RecordingController.onStopped()
            stopForegroundCompat()
            stopSelf()
            return
        }

        RecordingController.onStarted(id, currentTitle, currentLang, startedAt)
        startTicker()
    }

    private fun stopRecording() {
        scope?.cancel()
        scope = null
        val r = recorder
        recorder = null
        val duration = System.currentTimeMillis() - startedAt
        runCatching { r?.stop() }
        runCatching { r?.release() }

        val id = currentId
        val file = currentFile
        if (id != null && file != null && file.exists() && file.length() > 0) {
            AppGraph.store.add(
                PendingRecording(
                    id = id, title = currentTitle, language = currentLang,
                    fileName = file.name, createdAtMillis = startedAt, durationMs = duration,
                )
            )
            AppGraph.sync.requestSync(applicationContext)
        } else {
            file?.delete()
        }
        RecordingController.onStopped()
        stopForegroundCompat()
        stopSelf()
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
                val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                val level = (amp / 32768f).coerceIn(0f, 1f)
                RecordingController.onTick(elapsed, level)
                nm.notify(NOTIF_ID, buildNotification(formatElapsed(elapsed)))
                delay(1000)
            }
        }
    }

    private fun buildNotification(elapsedText: String): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, RECORDING_CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText("$currentTitle · $elapsedText")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun formatElapsed(ms: Long): String {
        val s = (ms / 1000).toInt()
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
