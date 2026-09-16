package com.transcribbio.phone.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transcribbio.phone.AppGraph
import com.transcribbio.phone.ui.components.StatusChip
import com.transcribbio.phone.ui.util.formatDate
import com.transcribbio.phone.ui.util.formatDuration
import com.transcribbio.shared.sync.LectureSummaryDto
import kotlinx.coroutines.launch

@Composable
fun ResultsScreen(onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var lectures by remember { mutableStateOf<List<LectureSummaryDto>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true; message = null
            val result = AppGraph.sync.fetchLectures()
            lectures = result
            loading = false
            if (result.isEmpty()) message =
                "No lectures found. Make sure your desktop app is open on the same Wi-Fi."
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Processed on desktop", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            lectures.isNotEmpty() -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(lectures, key = { it.id }) { LectureSummaryRow(it) { onOpen(it.id) } }
            }
            else -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                Text(message ?: "", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LectureSummaryRow(item: LectureSummaryDto, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                StatusChip(item.status)
            }
            Text(
                buildString {
                    append(formatDate(item.createdAtMillis))
                    if (item.durationS > 0) append("  ·  ${formatDuration(item.durationS)}")
                    if (item.hasMaterials) append("  ·  notes ready")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
