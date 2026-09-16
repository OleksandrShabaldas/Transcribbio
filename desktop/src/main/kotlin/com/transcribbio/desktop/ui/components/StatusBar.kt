package com.transcribbio.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.transcribbio.desktop.sidecar.SidecarState

@Composable
fun SidecarStatusBar(state: SidecarState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (state) {
            is SidecarState.Ready -> {
                Dot(Color(0xFF16A34A))
                val gpu = if (state.health.cudaAvailable) "GPU (CUDA)" else "CPU"
                Text(
                    "Engine ready · $gpu · ${state.health.whisperModel}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is SidecarState.Provisioning -> {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    state.message,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.fraction?.let {
                    LinearProgressIndicator(
                        progress = { it.toFloat() },
                        modifier = Modifier.padding(start = 4.dp).size(width = 120.dp, height = 6.dp),
                    )
                }
            }
            is SidecarState.Starting -> {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(state.message, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is SidecarState.Failed -> {
                Dot(MaterialTheme.colorScheme.error)
                Text(
                    "Engine error: ${state.reason}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            SidecarState.Stopped -> {
                Dot(MaterialTheme.colorScheme.outline)
                Text("Engine stopped", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRetry) { Text("Start") }
            }
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(color))
}
