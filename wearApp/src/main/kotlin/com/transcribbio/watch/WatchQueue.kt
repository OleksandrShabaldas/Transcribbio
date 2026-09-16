package com.transcribbio.watch

import android.content.Context
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Store-and-forward for watch recordings: keep files until they reach the phone. */
object WatchQueue {
    private lateinit var appContext: Context
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        dir = File(appContext.filesDir, "pending").apply { mkdirs() }
        refreshCount()
    }

    fun newRecordingFile(): File {
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + (1000..9999).random()
        return File(dir, "$id.m4a")
    }

    /** Called after a recording is finalised into the queue dir. */
    fun onRecorded() {
        refreshCount()
        scope.launch { transferAll() }
    }

    fun requestTransfer() {
        scope.launch { transferAll() }
    }

    private suspend fun transferAll() {
        val files = dir.listFiles { f -> f.extension == "m4a" && f.length() > 0 }
            ?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) { refreshCount(); return }

        val node = pickNode()
        if (node == null) {
            _status.value = "Waiting for phone…"
            refreshCount()
            return
        }
        for (f in files) {
            _status.value = "Sending to phone…"
            val ok = runCatching { WearTransfer.send(appContext, node.id, f) }.getOrDefault(false)
            if (ok) f.delete()
        }
        refreshCount()
        _status.value = if (currentCount() == 0) "Sent to phone" else "Some still waiting for phone"
    }

    private suspend fun pickNode(): Node? {
        val nodes = runCatching { Wearable.getNodeClient(appContext).connectedNodes.await() }.getOrNull() ?: return null
        return nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
    }

    private fun refreshCount() { _pending.value = currentCount() }
    private fun currentCount(): Int = dir.listFiles { f -> f.extension == "m4a" }?.size ?: 0
}
