package com.transcribbio.desktop.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.transcribbio.desktop.core.AppConfig
import com.transcribbio.desktop.core.LlmPolicy
import com.transcribbio.desktop.sidecar.SidecarState
import com.transcribbio.desktop.sync.SyncServerState
import com.transcribbio.desktop.ui.AppState
import com.transcribbio.desktop.ui.util.languageLabel
import com.transcribbio.shared.update.UpdateStatus

@Composable
fun SettingsScreen(state: AppState, onOpenUrl: (String) -> Unit) {
    val sidecarState by state.sidecar.state.collectAsState()
    val syncState by state.syncServer.state.collectAsState()
    val updateStatus by state.updater.status.collectAsState()
    val offlineMsg by state.offlineProvisionMsg.collectAsState()
    var draft by remember { mutableStateOf(state.config.value) }
    var showKey by remember { mutableStateOf(false) }
    var savedTick by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        // ── AI provider ──
        SettingsCard("AI for correction & notes") {
            OutlinedTextField(
                value = draft.geminiApiKey,
                onValueChange = { draft = draft.copy(geminiApiKey = it.trim()) },
                label = { Text("Gemini API key (free)") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Get a free key at Google AI Studio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onOpenUrl("https://aistudio.google.com/apikey") }) { Text("Open") }
            }
            Spacer(Modifier.height(4.dp))
            Text("Provider strategy", style = MaterialTheme.typography.titleMedium)
            PolicyOption("Gemini first, local model when offline", LlmPolicy.GEMINI_THEN_OLLAMA, draft.llmPolicy) {
                draft = draft.copy(llmPolicy = it)
            }
            PolicyOption("Local model only (fully private / offline)", LlmPolicy.OLLAMA_ONLY, draft.llmPolicy) {
                draft = draft.copy(llmPolicy = it)
            }
            PolicyOption("Gemini only (always cloud)", LlmPolicy.GEMINI_ONLY, draft.llmPolicy) {
                draft = draft.copy(llmPolicy = it)
            }
        }

        // ── Transcription ──
        SettingsCard("Transcription") {
            LabeledDropdown("Default language",
                listOf("sk" to "Slovak", "en" to "English", "cs" to "Czech", "auto" to "Auto-detect"),
                draft.language) { draft = draft.copy(language = it) }
            LabeledDropdown("Whisper model",
                listOf("large-v3" to "large-v3 (best, recommended)", "medium" to "medium (faster)", "small" to "small (fastest)"),
                draft.whisperModel) { draft = draft.copy(whisperModel = it) }
            LabeledDropdown("Compute device",
                listOf("auto" to "Auto (GPU if available)", "cuda" to "GPU (CUDA)", "cpu" to "CPU"),
                draft.device) { draft = draft.copy(device = it) }
        }

        // ── Auto materials ──
        SettingsCard("Generate automatically after transcription") {
            listOf("summary" to "Summary", "notes" to "Notes", "takeaways" to "Key takeaways", "flashcards" to "Flashcards")
                .forEach { (key, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.autoGenerateMaterials.contains(key),
                            onCheckedChange = { on ->
                                draft = draft.copy(
                                    autoGenerateMaterials =
                                        if (on) draft.autoGenerateMaterials + key
                                        else draft.autoGenerateMaterials - key,
                                )
                            },
                        )
                        Text(label)
                    }
                }
            Text("Others can be generated on demand from each lecture.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ── Offline model ──
        SettingsCard("Offline model") {
            Text("Download the local model (${draft.ollamaModel}) so correction & notes work without internet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { state.provisionOfflineModel() }) {
                    Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                    Text("  Download offline model")
                }
                offlineMsg?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        // ── Diagnostics ──
        SettingsCard("Engine") {
            val line = when (val s = sidecarState) {
                is SidecarState.Ready -> "Ready · ${if (s.health.cudaAvailable) "GPU (CUDA)" else "CPU"} · model ${s.health.whisperModel} · port ${s.port}"
                is SidecarState.Provisioning -> "Setting up: ${s.message}"
                is SidecarState.Starting -> s.message
                is SidecarState.Failed -> "Error: ${s.reason}"
                SidecarState.Stopped -> "Stopped"
            }
            Text(line, style = MaterialTheme.typography.bodyMedium)
            Text("Data folder: ${draft.dataDir}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { state.retrySidecar() }) { Text("Restart engine") }
        }

        // ── Phone & watch sync ──
        SettingsCard("Phone & watch sync") {
            val line = when (val s = syncState) {
                is SyncServerState.Running -> "Ready to receive on ${s.host}:${s.port}"
                is SyncServerState.Failed -> "Error: ${s.reason}"
                SyncServerState.Stopped -> "Sync is off"
            }
            Text(line, style = MaterialTheme.typography.bodyMedium)
            Text("Desktop name: ${draft.desktopName.ifBlank { "Transcribbio Desktop" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Open the Transcribbio app on your phone on the same Wi-Fi — it will find this desktop " +
                "automatically and its recordings will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ── Updates ──
        SettingsCard("Updates") {
            val u = updateStatus
            Text("Current version ${state.appVersion}", style = MaterialTheme.typography.bodyMedium)
            val line = when (u) {
                is UpdateStatus.Idle -> "Checks GitHub automatically on launch."
                is UpdateStatus.Checking -> "Checking for updates…"
                is UpdateStatus.UpToDate -> "You're on the latest version."
                is UpdateStatus.Available -> "Version ${u.version} is available."
                is UpdateStatus.Downloading -> "Downloading ${u.version}… ${(u.fraction * 100).toInt()}%"
                is UpdateStatus.Downloaded -> "Version ${u.version} downloaded — ready to install."
                is UpdateStatus.Error -> "Update check failed: ${u.message}"
            }
            Text(line, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (u is UpdateStatus.Downloading) {
                LinearProgressIndicator(progress = { u.fraction }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { state.updater.checkManually() }) { Text("Check for updates") }
                when (u) {
                    is UpdateStatus.Available -> Button(onClick = { state.updater.startDownload() }) {
                        Text("Download ${u.version}")
                    }
                    is UpdateStatus.Downloaded -> Button(onClick = { state.updater.installAndRestart() }) {
                        Text("Install & restart")
                    }
                    else -> {}
                }
                TextButton(onClick = { state.updater.openReleasesPage() }) { Text("View on GitHub") }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { state.saveSettings(draft); savedTick = true }) { Text("Save settings") }
            if (savedTick) Text("Saved. Engine restarts if needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun PolicyOption(label: String, value: LlmPolicy, selected: LlmPolicy, onSelect: (LlmPolicy) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Text(label)
    }
}

@Composable
private fun LabeledDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: selected
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(180.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(current)
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, display) ->
                    DropdownMenuItem(text = { Text(display) }, onClick = {
                        onSelect(value); expanded = false
                    })
                }
            }
        }
    }
}
