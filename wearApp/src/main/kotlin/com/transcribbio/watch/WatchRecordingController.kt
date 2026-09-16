package com.transcribbio.watch

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RecState {
    data object Idle : RecState
    data class Recording(val startedAt: Long) : RecState
}

object WatchRecordingController {
    private val _state = MutableStateFlow<RecState>(RecState.Idle)
    val state: StateFlow<RecState> = _state.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    val isRecording: Boolean get() = _state.value is RecState.Recording

    fun start(context: Context) {
        if (isRecording) return
        val file = WatchQueue.newRecordingFile()
        val intent = Intent(context, WatchRecordingService::class.java).apply {
            action = WatchRecordingService.ACTION_START
            putExtra(WatchRecordingService.EXTRA_FILE, file.absolutePath)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, WatchRecordingService::class.java).apply {
            action = WatchRecordingService.ACTION_STOP
        }
        context.startService(intent)
    }

    internal fun onStarted(startedAt: Long) { _state.value = RecState.Recording(startedAt); _elapsedMs.value = 0 }
    internal fun onTick(ms: Long) { _elapsedMs.value = ms }
    internal fun onStopped() { _state.value = RecState.Idle; _elapsedMs.value = 0 }
}
