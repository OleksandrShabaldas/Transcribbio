package com.transcribbio.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.transcribbio.shared.update.UpdateStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearApp() }
    }

    override fun onStart() {
        super.onStart()
        WatchQueue.requestTransfer()
        WatchGraph.updater.checkOnLaunch()
    }
}

@Composable
private fun WearApp() {
    MaterialTheme {
        Scaffold(timeText = { TimeText() }) {
            val context = LocalContext.current
            val recState by WatchRecordingController.state.collectAsState()
            val elapsed by WatchRecordingController.elapsedMs.collectAsState()
            val pending by WatchQueue.pending.collectAsState()
            val status by WatchQueue.status.collectAsState()
            val updateStatus by WatchGraph.updater.status.collectAsState()
            val isRecording = recState is RecState.Recording

            val permLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                if (result[Manifest.permission.RECORD_AUDIO] == true) WatchRecordingController.start(context)
            }

            fun onClick() {
                if (isRecording) { WatchRecordingController.stop(context); return }
                val needed = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                val granted = needed.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                if (granted) WatchRecordingController.start(context) else permLauncher.launch(needed.toTypedArray())
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (isRecording) formatClock(elapsed) else "Record lecture",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.title3,
                )
                Button(
                    onClick = { onClick() },
                    modifier = Modifier.size(72.dp),
                    colors = ButtonDefaults.primaryButtonColors(),
                ) {
                    Box(
                        Modifier.size(26.dp)
                            .clip(if (isRecording) RoundedCornerShape(5.dp) else CircleShape)
                            .background(Color(0xFFEF4444)),
                    )
                }
                if (pending > 0) {
                    Text("$pending waiting to send", textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption2)
                }
                status?.let {
                    Text(it, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2)
                }

                // ── Updates ──
                when (val u = updateStatus) {
                    is UpdateStatus.Available -> CompactChip(
                        onClick = { WatchGraph.updater.startDownload() },
                        label = { Text("Update ${u.version}") },
                    )
                    is UpdateStatus.Downloading -> Text(
                        "Updating… ${(u.fraction * 100).toInt()}%",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2,
                    )
                    is UpdateStatus.Downloaded -> CompactChip(
                        onClick = { WatchGraph.updater.install() },
                        label = { Text("Install update") },
                    )
                    else -> {}
                }
                CompactChip(
                    onClick = { WatchGraph.updater.checkOnLaunch() },
                    label = { Text("Check for updates") },
                )
            }
        }
    }
}

private fun formatClock(ms: Long): String {
    val s = (ms / 1000).toInt(); val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
