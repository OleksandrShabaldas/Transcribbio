package com.transcribbio.phone.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class PendingRecording(
    val id: String,
    val title: String,
    val language: String,
    val fileName: String,
    val createdAtMillis: Long,
    val durationMs: Long = 0,
    val uploaded: Boolean = false,
    val lectureId: String? = null,
    val error: String? = null,
    /** Uploaded to the Google Drive relay; the desktop will pick it up from there. */
    val cloudFileId: String? = null,
    /** Resumable Drive upload in progress (lets an interrupted upload continue, not restart). */
    val driveSession: String? = null,
    /** Renamed after the desktop already had it (or while a Drive upload was underway):
     *  the new title still has to reach the desktop. */
    val renamePending: Boolean = false,
) {
    val inCloud: Boolean get() = !uploaded && cloudFileId != null
}

/** JSON-backed queue of recordings awaiting (or completed) upload to the desktop. */
class RecordingStore(context: Context) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(context.filesDir, "recordings").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "recordings.json")

    private val _items = MutableStateFlow<List<PendingRecording>>(emptyList())
    val items: StateFlow<List<PendingRecording>> = _items.asStateFlow()

    init {
        load()
    }

    private fun load() {
        if (indexFile.exists()) {
            runCatching { json.decodeFromString<List<PendingRecording>>(indexFile.readText()) }
                .onSuccess { list -> _items.value = list.sortedByDescending { it.createdAtMillis } }
        }
    }

    private fun persist() {
        runCatching {
            val tmp = File(indexFile.parentFile, "recordings.json.tmp")
            tmp.writeText(json.encodeToString(_items.value))
            tmp.renameTo(indexFile)
        }
    }

    fun audioFile(rec: PendingRecording): File = File(dir, rec.fileName)

    /** Allocate a new recording id + destination file (m4a). */
    fun newRecording(): Pair<String, File> {
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + (1000..9999).random()
        return id to File(dir, "$id.m4a")
    }

    fun add(rec: PendingRecording) {
        _items.value = (_items.value + rec).sortedByDescending { it.createdAtMillis }
        persist()
    }

    fun update(rec: PendingRecording) {
        _items.value = _items.value.map { if (it.id == rec.id) rec else it }
        persist()
    }

    fun markUploaded(id: String, lectureId: String) {
        _items.value = _items.value.map {
            if (it.id == id) it.copy(uploaded = true, lectureId = lectureId, error = null, driveSession = null) else it
        }
        persist()
    }

    fun get(id: String): PendingRecording? = _items.value.firstOrNull { it.id == id }

    /** Rename locally; if the desktop may already have it under the old name, flag it for sync. */
    fun rename(id: String, title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        _items.value = _items.value.map {
            if (it.id != id || it.title == t) it
            else it.copy(title = t, renamePending = it.renamePending || it.uploaded || it.cloudFileId != null || it.driveSession != null)
        }
        persist()
    }

    fun clearRenamePending(id: String) {
        _items.value = _items.value.map { if (it.id == id) it.copy(renamePending = false) else it }
        persist()
    }

    fun setDriveSession(id: String, session: String?) {
        _items.value = _items.value.map { if (it.id == id) it.copy(driveSession = session) else it }
        persist()
    }

    fun markUploadedToCloud(id: String, fileId: String) {
        _items.value = _items.value.map { if (it.id == id) it.copy(cloudFileId = fileId, error = null, driveSession = null) else it }
        persist()
    }

    fun setError(id: String, message: String) {
        _items.value = _items.value.map { if (it.id == id) it.copy(error = message) else it }
        persist()
    }

    fun remove(id: String) {
        _items.value.firstOrNull { it.id == id }?.let { audioFile(it).delete() }
        _items.value = _items.value.filterNot { it.id == id }
        persist()
    }

    /** Anything the background job still has to do: deliver recordings, push renames, or
     *  confirm Drive pickups. */
    fun hasPendingWork(): Boolean = _items.value.any { (!it.uploaded && it.cloudFileId == null) || it.renamePending || it.inCloud }

    /** Work that moves real data (worth running as a foreground job with a notification). */
    fun hasUploadWork(): Boolean = _items.value.any { (!it.uploaded && it.cloudFileId == null) || it.renamePending }

    /** Not yet delivered anywhere (neither to the desktop directly nor to the Drive relay). */
    fun pendingUploads(): List<PendingRecording> = _items.value.filter { !it.uploaded && it.cloudFileId == null }
}
