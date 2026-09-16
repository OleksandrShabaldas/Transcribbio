package com.transcribbio.phone.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.transcribbio.phone.AppGraph
import com.transcribbio.phone.data.PendingRecording
import com.transcribbio.phone.recording.RecState
import com.transcribbio.phone.recording.RecordingController
import com.transcribbio.phone.sync.SyncUiState
import com.transcribbio.phone.ui.util.formatDate
import com.transcribbio.phone.ui.util.formatDuration
import com.transcribbio.phone.ui.util.formatMillisClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val recState by RecordingController.state.collectAsState()
    val elapsed by RecordingController.elapsedMs.collectAsState()
    val level by RecordingController.level.collectAsState()
    val syncState by AppGraph.sync.state.collectAsState()
    val recordings by AppGraph.store.items.collectAsState()
    val language by AppGraph.prefs.language.collectAsState()

    fun startRec() {
        val title = "Lecture " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())
        RecordingController.start(context, title, language)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) startRec()
    }

    fun onRecordClick() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val granted = needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) startRec() else permLauncher.launch(needed.toTypedArray())
    }

    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val isRecording = recState is RecState.Recording

        Box(Modifier.padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
            FilledIconButton(
                onClick = { if (isRecording) RecordingController.stop(context) else onRecordClick() },
                modifier = Modifier.size(120.dp),
                shape = CircleShape,
                colors = if (isRecording)
                    IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
                else IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(
                    if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isRecording) "Stop" else "Record",
                    modifier = Modifier.size(52.dp),
                )
            }
        }

        if (isRecording) {
            Text(formatMillisClock(elapsed), fontSize = 34.sp, fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(
                progress = { level.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).height(6.dp),
                color = MaterialTheme.colorScheme.secondary,
            )
            Text("Recording — you can lock your screen.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Tap to record a lecture", style = MaterialTheme.typography.titleMedium)
            Text("Recordings sync to your desktop automatically on your home Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(16.dp))
        SyncStatusRow(syncState) { AppGraph.sync.requestSync(context) }
        Spacer(Modifier.height(8.dp))

        if (recordings.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("On this phone", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recordings, key = { it.id }) { rec -> RecordingRow(rec) }
            }
        }
    }
}

@Composable
private fun SyncStatusRow(state: SyncUiState, onSync: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val (icon, text) = when (state) {
                is SyncUiState.Idle -> Icons.Default.Cloud to "Ready to sync"
                is SyncUiState.Discovering -> Icons.Default.Sync to "Looking for your desktop…"
                is SyncUiState.Uploading -> Icons.Default.Sync to "Uploading ${state.current}/${state.total}…"
                is SyncUiState.Error -> Icons.Default.Cloud to state.message
                is SyncUiState.Done -> Icons.Default.CloudDone to "Synced to ${state.desktopName}"
            }
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSync) { Text("Sync now") }
        }
    }
}

@Composable
private fun RecordingRow(rec: PendingRecording) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rec.title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(
                    formatDate(rec.createdAtMillis) + "  ·  " + formatDuration(rec.durationMs / 1000.0),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    rec.uploaded -> Text("Synced", style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF16A34A))
                    rec.error != null -> Text("Waiting to sync — ${rec.error}",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    else -> Text("Waiting to sync", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            if (rec.uploaded) {
                IconButton(onClick = { AppGraph.store.remove(rec.id) }) {
                    Icon(Icons.Default.CloudDone, "Synced", tint = Color(0xFF16A34A))
                }
            }
        }
    }
}
