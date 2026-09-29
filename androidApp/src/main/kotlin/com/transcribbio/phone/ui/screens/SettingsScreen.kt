package com.transcribbio.phone.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import com.transcribbio.phone.sync.SyncWorker
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalContext
import com.transcribbio.phone.sync.DriveRelay
import com.transcribbio.phone.ui.util.findActivity
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

        SettingsCard("Cloud relay (Google Drive)") {
            val account by AppGraph.prefs.driveAccount.collectAsState()
            val onMobile by AppGraph.prefs.cloudOnMobileData.collectAsState()
            val context = LocalContext.current
            val cloudScope = rememberCoroutineScope()
            var busy by remember { mutableStateOf(false) }
            var problem by remember { mutableStateOf<String?>(null) }
            val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
                cloudScope.launch {
                    problem = runCatching { AppGraph.drive.completeConnect(res.data) }.exceptionOrNull()
                        ?.let { DriveRelay.explain(it) }
                    if (problem == null) AppGraph.sync.requestSync(context)
                    busy = false
                }
            }
            Text("When your PC can't be reached directly (e.g. on university Wi-Fi), recordings go to a private, " +
                "hidden folder in your Google Drive and your PC picks them up and deletes them. " +
                "Set it up on the PC first: Transcribbio ▸ Settings ▸ Cloud relay.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (account.isBlank()) {
                Button(
                    onClick = {
                        val activity = context.findActivity() ?: return@Button
                        busy = true; problem = null
                        cloudScope.launch {
                            try {
                                val consentScreen = AppGraph.drive.beginConnect(activity)
                                if (consentScreen != null) {
                                    consent.launch(IntentSenderRequest.Builder(consentScreen.intentSender).build())
                                } else {
                                    busy = false
                                    AppGraph.sync.requestSync(context)
                                }
                            } catch (e: Exception) {
                                problem = DriveRelay.explain(e); busy = false
                            }
                        }
                    },
                    enabled = !busy,
                ) { Text(if (busy) "Connecting…" else "Connect Google Drive") }
            } else {
                Text("✓ Connected as $account", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = onMobile, onCheckedChange = {
                        AppGraph.prefs.setCloudOnMobileData(it)
                        SyncWorker.schedulePeriodic(context)
                        AppGraph.sync.requestSync(context)
                    })
                    Text("  Also upload over mobile data", style = MaterialTheme.typography.bodyMedium)
                }
                Text(if (onMobile) "Recordings upload as soon as the PC can't be reached."
                    else "Recordings wait for Wi-Fi before uploading (they can be large).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { cloudScope.launch { AppGraph.drive.disconnect() } }) { Text("Disconnect") }
            }
            problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }

        SettingsCard("Background uploads") {
            val context = LocalContext.current
            val power = remember { context.getSystemService(PowerManager::class.java) }
            fun exempt() = power?.isIgnoringBatteryOptimizations(context.packageName) == true
            var unrestricted by remember { mutableStateOf(exempt()) }
            val batteryDialog = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                unrestricted = exempt()
                if (unrestricted) AppGraph.sync.requestSync(context)
            }
            if (unrestricted) {
                Text("✓ Recordings keep uploading while the phone is asleep (with an “Uploading…” notification).",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            } else {
                Text("Android may pause uploads while the phone is asleep until you allow Transcribbio to run " +
                    "in the background. It only works while there's something to send.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = {
                    runCatching {
                        batteryDialog.launch(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")))
                    }.onFailure {
                        // Some phones hide that dialog: open the app's battery settings instead.
                        batteryDialog.launch(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")))
                    }
                }) { Text("Allow background uploads") }
                Text("On Samsung: if asked, choose “Allow” (battery usage becomes Unrestricted).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
