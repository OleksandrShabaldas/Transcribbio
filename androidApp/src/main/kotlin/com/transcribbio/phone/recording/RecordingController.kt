package com.transcribbio.phone.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.transcribbio.phone.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RecState {
    data object Idle : RecState
    data class Recording(val id: String, val title: String, val language: String, val startedAt: Long) : RecState
}

/** Bridges the recording UI and the foreground [RecordingService]. */
object RecordingController {
    private val _state = MutableStateFlow<RecState>(RecState.Idle)
    val state: StateFlow<RecState> = _state.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    val isRecording: Boolean get() = _state.value is RecState.Recording

    fun start(context: Context, title: String, language: String) {
        if (isRecording) return
        val (id, file) = AppGraph.store.newRecording()
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_ID, id)
            putExtra(RecordingService.EXTRA_FILE, file.absolutePath)
            putExtra(RecordingService.EXTRA_TITLE, title)
            putExtra(RecordingService.EXTRA_LANG, language)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)
    }

    // ── called by the service ──
    internal fun onStarted(id: String, title: String, language: String, startedAt: Long) {
        _state.value = RecState.Recording(id, title, language, startedAt)
        _elapsedMs.value = 0L
    }

    internal fun onTick(elapsedMs: Long, level: Float) {
        _elapsedMs.value = elapsedMs
        _level.value = level
    }

    internal fun onStopped() {
        _state.value = RecState.Idle
        _elapsedMs.value = 0L
        _level.value = 0f
    }
}
