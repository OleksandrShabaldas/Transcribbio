package com.transcribbio.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.transcribbio.desktop.ui.components.RecordDialog
import com.transcribbio.desktop.ui.components.SidecarStatusBar
import com.transcribbio.desktop.ui.screens.LectureDetailScreen
import com.transcribbio.desktop.ui.screens.LibraryScreen
import com.transcribbio.desktop.ui.screens.SettingsScreen
import java.nio.file.Path

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    state: AppState,
    pickAudioFile: () -> Path?,
    pickSaveFile: (String) -> Path?,
    onOpenUrl: (String) -> Unit,
) {
    val screen by state.screen.collectAsState()
    val sidecarState by state.sidecar.state.collectAsState()
    val config by state.config.collectAsState()
    var showRecord by remember { mutableStateOf(false) }
    val micAvailable = remember { state.micAvailable() }

    val recording by state.recorder.recording.collectAsState()
    val elapsed by state.recorder.elapsedMs.collectAsState()
    val level by state.recorder.level.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (screen) {
                            is Screen.Settings -> "Settings"
                            is Screen.Detail -> "Lecture"
                            else -> "Transcribbio"
                        }
                    )
                },
                navigationIcon = {
                    if (screen !is Screen.Library) {
                        IconButton(onClick = { state.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    if (screen is Screen.Library) {
                        IconButton(onClick = { state.navigate(Screen.Settings) }) {
                            Icon(Icons.Default.Settings, "Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = { SidecarStatusBar(sidecarState, onRetry = { state.retrySidecar() }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = screen) {
                is Screen.Library -> LibraryScreen(
                    state = state,
                    onOpen = { state.navigate(Screen.Detail(it)) },
                    onRecord = { showRecord = true },
                    onImport = { pickAudioFile()?.let { state.importAudio(it) } },
                )
                is Screen.Detail -> LectureDetailScreen(state, s.lectureId, pickSaveFile)
                is Screen.Settings -> SettingsScreen(state, onOpenUrl)
            }
        }
    }

    if (showRecord) {
        RecordDialog(
            recording = recording,
            elapsedMs = elapsed,
            level = level,
            micAvailable = micAvailable,
            onStart = { state.startRecording(it) },
            onStop = { state.stopRecordingAndProcess(); showRecord = false },
            onCancel = { state.cancelRecording(); showRecord = false },
        )
    }

    if (!config.firstRunComplete) {
        FirstRunDialog(
            onOpenSettings = { state.markFirstRunComplete(); state.navigate(Screen.Settings) },
            onDismiss = { state.markFirstRunComplete() },
        )
    }
}

@Composable
private fun FirstRunDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Welcome to Transcribbio") },
        text = {
            Text(
                "Record or import a lecture and it will be transcribed in Slovak on your GPU, " +
                    "then cleaned up and turned into notes and flashcards.\n\n" +
                    "For the best correction quality, add a free Gemini API key in Settings. " +
                    "Without it, a local model is used (works fully offline).",
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text("Open Settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Start using") } },
    )
}
