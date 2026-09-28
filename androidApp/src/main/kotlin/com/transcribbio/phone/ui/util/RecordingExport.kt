package com.transcribbio.phone.ui.util

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.transcribbio.phone.data.PendingRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Export a recording off the phone: the share sheet (Drive, Telegram, email, …) or a file the user picks. */
object RecordingExport {
    /** "Genetika – úvod.m4a" — the lecture title as a safe file name. */
    fun fileName(rec: PendingRecording, file: File): String {
        val base = rec.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { "Recording" }
        return "$base.${file.extension.ifBlank { "m4a" }}"
    }

    /** Open the Android share sheet with the recording under its lecture title. */
    suspend fun share(context: Context, rec: PendingRecording, file: File) {
        val copy = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            File(dir, fileName(rec, file)).also { file.copyTo(it, overwrite = true) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, rec.title)
            putExtra(Intent.EXTRA_TITLE, rec.title)
            clipData = ClipData.newRawUri(rec.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Export recording")
        if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /** Copy the recording to a location picked with the system "Save" dialog. */
    suspend fun saveTo(context: Context, file: File, target: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val out = context.contentResolver.openOutputStream(target) ?: error("couldn't open the destination")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            Unit
        }
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
