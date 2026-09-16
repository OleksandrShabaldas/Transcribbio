package com.transcribbio.phone.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.transcribbio.shared.update.UpdateStatus
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.transcribbio.phone.AppGraph

@Composable
fun SettingsScreen() {
    val language by AppGraph.prefs.language.collectAsState()
    val desktopName by AppGraph.prefs.desktopName.collectAsState()
    val token by AppGraph.prefs.token.collectAsState()
    val endpoint by AppGraph.sync.endpoint.collectAsState()
    val updateStatus by AppGraph.updater.status.collectAsState()
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {

        SettingsCard("Transcription language") {
            LabeledDropdown(
                options = listOf("sk" to "Slovak", "en" to "English", "cs" to "Czech", "auto" to "Auto-detect"),
                selected = language,
                onSelect = { AppGraph.prefs.setLanguage(it) },
            )
            Text("New recordings default to this language.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsCard("Desktop connection") {
            if (token.isBlank()) {
                Text("Not connected yet. Record something (or tap Sync now) while your desktop app is " +
                    "open on the same Wi-Fi — it pairs automatically.",
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("Paired with ${desktopName.ifBlank { "your desktop" }}" +
                    (endpoint?.let { " (${it.host})" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { AppGraph.prefs.clearPairing() }) { Text("Forget desktop") }
            }
        }

        SettingsCard("App updates") {
            val u = updateStatus
            val line = when (u) {
                is UpdateStatus.Idle -> "Checks GitHub automatically when you open the app."
                is UpdateStatus.Checking -> "Checking for updates…"
                is UpdateStatus.UpToDate -> "You're on the latest version."
                is UpdateStatus.Available -> "Version ${u.version} is available."
                is UpdateStatus.Downloading -> "Downloading ${u.version}… ${(u.fraction * 100).toInt()}%"
                is UpdateStatus.Downloaded -> "Version ${u.version} downloaded — tap install."
                is UpdateStatus.Error -> "Couldn't check: ${u.message}"
            }
            Text(line, style = MaterialTheme.typography.bodyMedium)
            if (u is UpdateStatus.Downloading) {
                LinearProgressIndicator(progress = { u.fraction }, modifier = Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { AppGraph.updater.checkOnLaunch() }) { Text("Check now") }
                when (u) {
                    is UpdateStatus.Available -> Button(onClick = { AppGraph.updater.startDownload() }) {
                        Text("Download")
                    }
                    is UpdateStatus.Downloaded -> Button(onClick = { AppGraph.updater.install() }) {
                        Text("Install")
                    }
                    else -> {}
                }
            }
        }

        SettingsCard("About") {
            Text("Device: ${Build.MANUFACTURER} ${Build.MODEL}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Transcribbio phone 1.0.0", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Recordings are transcribed and turned into study notes on your desktop.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun LabeledDropdown(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: selected
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(current)
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, display) ->
                DropdownMenuItem(text = { Text(display) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}
