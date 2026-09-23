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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.transcribbio.shared.update.UpdateStatus
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
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
                    "open — it pairs automatically.",
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("Paired with ${desktopName.ifBlank { "your desktop" }}" +
                    (endpoint?.let { " (${it.host}:${it.port})" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { AppGraph.prefs.clearPairing() }) { Text("Forget desktop") }
            }

            // Manual address: auto-discovery is blocked on many networks (e.g. eduroam).
            val manual by AppGraph.prefs.manualAddress.collectAsState()
            var address by remember(manual) { mutableStateOf(manual) }
            var testing by remember { mutableStateOf(false) }
            var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
            val scope = rememberCoroutineScope()
            OutlinedTextField(
                value = address,
                onValueChange = { address = it; result = null },
                label = { Text("Desktop address (optional)") },
                placeholder = { Text("e.g. 192.168.1.20:47815") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Only needed if the phone can't find your PC by itself. The address is shown in the " +
                "desktop app under Settings ▸ Phone & watch sync.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        AppGraph.prefs.setManualAddress(address)
                        scope.launch {
                            testing = true
                            val ep = AppGraph.sync.testAddress(address)
                            testing = false
                            result = if (ep != null) true to "Connected to ${ep.name.ifBlank { "your desktop" }}"
                            else false to "Saved, but no Transcribbio desktop answered there right now. Check the " +
                                "address, that the desktop app is open, and that both are on the same network."
                        }
                    },
                    enabled = !testing && address.isNotBlank(),
                ) { Text(if (testing) "Testing…" else "Save & test") }
                if (manual.isNotBlank()) TextButton(onClick = {
                    AppGraph.prefs.setManualAddress(""); address = ""; result = null
                }) { Text("Clear") }
            }
            result?.let { (ok, msg) ->
                Text((if (ok) "✓ " else "✗ ") + msg, style = MaterialTheme.typography.bodyMedium,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
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
            Text("Transcribbio phone ${com.transcribbio.phone.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
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
