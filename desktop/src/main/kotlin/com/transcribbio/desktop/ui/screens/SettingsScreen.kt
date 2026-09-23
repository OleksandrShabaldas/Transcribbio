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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.transcribbio.desktop.core.AppConfig
import com.transcribbio.desktop.core.LlmPolicy
import com.transcribbio.desktop.sidecar.LlmModelsDto
import com.transcribbio.desktop.sidecar.LlmTestResultDto
import com.transcribbio.desktop.sidecar.SidecarState
import com.transcribbio.desktop.sync.SyncServerState
import com.transcribbio.desktop.ui.AppState
import com.transcribbio.desktop.ui.LlmTestUi
import com.transcribbio.desktop.ui.util.languageLabel
import com.transcribbio.shared.update.UpdateStatus

@Composable
fun SettingsScreen(state: AppState, onOpenUrl: (String) -> Unit) {
    val sidecarState by state.sidecar.state.collectAsState()
    val syncState by state.syncServer.state.collectAsState()
    val updateStatus by state.updater.status.collectAsState()
    val offlineMsg by state.offlineProvisionMsg.collectAsState()
    val llmModels by state.llmModels.collectAsState()
    val llmTest by state.llmTest.collectAsState()
    val saved by state.config.collectAsState()
    var draft by remember { mutableStateOf(state.config.value) }
    var showKey by remember { mutableStateOf(false) }
    var savedTick by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val engineReady = sidecarState is SidecarState.Ready

    // The whole AI section applies automatically (debounced): users kept pasting a key or
    // picking a model and never pressing Save, so the engine never received it. Only the AI
    // fields are applied here; other sections still use the Save button at the bottom.
    LaunchedEffect(draft.geminiApiKey, draft.geminiModels, draft.llmTimeoutS, draft.llmPolicy, draft.ollamaModel) {
        if (aiDiffers(draft, state.config.value)) {
            delay(700)
            val now = state.config.value
            if (aiDiffers(draft, now)) {
                state.saveSettings(now.copy(
                    geminiApiKey = draft.geminiApiKey, geminiModels = draft.geminiModels,
                    llmTimeoutS = draft.llmTimeoutS, llmPolicy = draft.llmPolicy, ollamaModel = draft.ollamaModel,
                ))
                savedTick = true
            }
        }
    }
    // Load the models this key can use whenever the engine (re)starts with a key.
    LaunchedEffect(engineReady, saved.geminiApiKey) {
        if (engineReady && saved.hasGeminiKey()) state.refreshLlmModels()
    }

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

            // ── Model chain ──
            Spacer(Modifier.height(4.dp))
            Text("Models", style = MaterialTheme.typography.titleMedium)
            Text("Tried in this order. If one is retired, busy, out of free quota or too slow, the next one takes over.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val options = modelOptions(llmModels, draft.geminiModels)
            val results = (llmTest as? LlmTestUi.Done)?.result?.results?.associateBy { it.model } ?: emptyMap()
            listOf("Primary", "Fallback 1", "Fallback 2").forEachIndexed { i, label ->
                val current = draft.geminiModels.getOrElse(i) { "" }
                ModelRow(label, current, options, allowNone = i > 0, result = results[current]) { chosen ->
                    draft = draft.copy(geminiModels = setModelSlot(draft.geminiModels, i, chosen))
                }
            }
            llmModels?.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val canQuery = engineReady && saved.hasGeminiKey()
                OutlinedButton(onClick = { state.refreshLlmModels() }, enabled = canQuery) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Text("  Refresh list")
                }
                OutlinedButton(onClick = { state.testLlmModels(draft.geminiModels) },
                    enabled = canQuery && llmTest !is LlmTestUi.Testing) {
                    if (llmTest is LlmTestUi.Testing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("  Testing…")
                    } else {
                        Icon(Icons.Default.NetworkCheck, null, Modifier.size(18.dp)); Text("  Test models")
                    }
                }
                when (val t = llmTest) {
                    is LlmTestUi.Done -> {
                        val ok = t.result.results.count { it.ok }
                        Text("$ok of ${t.result.results.size} working right now" +
                            if (t.result.ollamaReady) " · offline model ready" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ok > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
                    }
                    is LlmTestUi.Failed -> Text(t.message, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                    else -> if (!engineReady) Text("Engine starting…", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LabeledDropdown("Switch model if slower than",
                listOf("60" to "60 s", "90" to "90 s (recommended)", "120" to "2 min", "180" to "3 min", "300" to "5 min"),
                draft.llmTimeoutS.toString()) { draft = draft.copy(llmTimeoutS = it.toInt()) }

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
            Spacer(Modifier.height(4.dp))
            when {
                aiDiffers(draft, saved) -> Text("Applying & restarting the engine…",
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                draft.hasGeminiKey() -> Text("✓ Saved automatically — the engine uses the models above",
                    color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
                else -> Text("No key set — AI features need a Gemini key or the offline model",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
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
        SettingsCard("Offline model (last resort)") {
            Text("Used when Gemini is unreachable (no internet, or every model above failed). " +
                "Runs locally through Ollama.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val installed = llmModels?.ollama.orEmpty()
            if (installed.isNotEmpty()) {
                LabeledDropdown("Local model",
                    (installed + draft.ollamaModel).distinct().map { it to it },
                    draft.ollamaModel) { draft = draft.copy(ollamaModel = it) }
            } else {
                OutlinedTextField(
                    value = draft.ollamaModel,
                    onValueChange = { draft = draft.copy(ollamaModel = it.trim()) },
                    label = { Text("Local model (Ollama)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
            val clipboard = LocalClipboardManager.current
            var copied by remember { mutableStateOf(false) }
            LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
            when (val s = syncState) {
                is SyncServerState.Running -> {
                    if (s.host == "127.0.0.1") {
                        Text("Not connected to a network — connect this PC to Wi-Fi to receive recordings.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    } else {
                        val address = "${s.host}:${s.port}"
                        Text("This PC's address", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SelectionContainer {
                                Text(address, style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(address)); copied = true }) {
                                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                                Text(if (copied) "  Copied!" else "  Copy")
                            }
                        }
                        val others = s.addresses.filter { it != s.host }
                        if (others.isNotEmpty()) {
                            Text("Also reachable at: " + others.joinToString { "$it:${s.port}" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("On home Wi-Fi the phone finds this PC by itself. If it says it can't, enter the " +
                            "address above in the phone app: Settings ▸ Desktop connection.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!s.privateNetwork) {
                            Text("You're on a public or university network (such as eduroam). These usually stop " +
                                "phones and computers from reaching each other, so syncing may not work here — " +
                                "recordings wait safely on the phone and sync once you're both on home Wi-Fi " +
                                "(or connect this PC to your phone's hotspot).",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                        if (s.vpnActive) {
                            Text("A VPN is active. If the phone can't connect, allow LAN / local-network access " +
                                "in the VPN's settings or pause it while syncing.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                is SyncServerState.Failed -> Text("Error: ${s.reason}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
                SyncServerState.Stopped -> Text("Sync is off", style = MaterialTheme.typography.bodyMedium)
            }
            Text("Desktop name: ${draft.desktopName.ifBlank { "Transcribbio Desktop" }}",
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

private fun aiDiffers(a: AppConfig, b: AppConfig) =
    a.geminiApiKey != b.geminiApiKey || a.geminiModels != b.geminiModels ||
        a.llmTimeoutS != b.llmTimeoutS || a.llmPolicy != b.llmPolicy || a.ollamaModel != b.ollamaModel

/** Put [value] in chain slot [i] (0 = primary). Keeps three slots; a model can occupy only
 *  one slot, so choosing it again elsewhere clears its old slot. "" means "None". */
private fun setModelSlot(chain: List<String>, i: Int, value: String): List<String> {
    val slots = MutableList(3) { chain.getOrElse(it) { "" } }
    slots[i] = value
    if (value.isNotBlank()) for (j in slots.indices) if (j != i && slots[j] == value) slots[j] = ""
    return slots
}

/** Picker options: the models this key can use (live list), plus whatever is selected. */
private fun modelOptions(list: LlmModelsDto?, selected: List<String>): List<Pair<String, String>> {
    val live = list?.gemini?.map { it.name to it.displayName.ifBlank { it.name } }.orEmpty()
    val base = live.ifEmpty { AppConfig.DEFAULT_GEMINI_MODELS.map { it to it } }
    val extra = selected.filter { s -> s.isNotBlank() && base.none { it.first == s } }
        .map { it to if (live.isNotEmpty()) "not available for your key" else it }
    return base + extra
}

@Composable
private fun ModelRow(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    allowNone: Boolean,
    result: LlmTestResultDto?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(110.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selected.ifBlank { "None" })
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (allowNone) DropdownMenuItem(text = { Text("None") }, onClick = { onSelect(""); expanded = false })
                options.forEach { (name, display) ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(name)
                                if (display != name) Text(display, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = { onSelect(name); expanded = false },
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        result?.let {
            Text(if (it.ok) "✓ works · ${it.latencyS}s" else "✗ ${it.detail}",
                style = MaterialTheme.typography.labelLarge,
                color = if (it.ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
        }
    }
}
