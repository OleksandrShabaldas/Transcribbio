package com.transcribbio.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transcribbio.desktop.processing.ProcessingProgress
import com.transcribbio.desktop.ui.AppState
import com.transcribbio.desktop.ui.components.StatusChip
import com.transcribbio.desktop.ui.util.formatDate
import com.transcribbio.desktop.ui.util.formatDuration
import com.transcribbio.shared.model.Lecture
import com.transcribbio.shared.model.LectureStatus

@Composable
fun LibraryScreen(
    state: AppState,
    onOpen: (String) -> Unit,
    onRecord: () -> Unit,
    onImport: () -> Unit,
) {
    val lectures by state.repo.lectures.collectAsState()
    val progress by state.orchestrator.progress.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Your lectures",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onImport) {
                Icon(Icons.Default.UploadFile, null, Modifier.size(18.dp))
                Text("  Import audio")
            }
            Button(onClick = onRecord) {
                Icon(Icons.Default.Mic, null, Modifier.size(18.dp))
                Text("  Record")
            }
        }

        if (lectures.isEmpty()) {
            EmptyLibrary(onRecord, onImport)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(lectures, key = { it.id }) { lecture ->
                    LectureRow(lecture, progress[lecture.id]) { onOpen(lecture.id) }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onRecord: () -> Unit, onImport: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                Icons.Default.Description, null,
                Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            )
            Text("No lectures yet", style = MaterialTheme.typography.titleLarge)
            Text(
                "Record a lecture, or import an audio file to get a Slovak transcript and study notes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRecord) {
                    Icon(Icons.Default.Mic, null, Modifier.size(18.dp)); Text("  Record")
                }
                OutlinedButton(onClick = onImport) {
                    Icon(Icons.Default.UploadFile, null, Modifier.size(18.dp)); Text("  Import audio")
                }
            }
        }
    }
}

@Composable
private fun LectureRow(lecture: Lecture, progress: ProcessingProgress?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    lecture.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(lecture.status)
            }
            Text(
                buildString {
                    append(formatDate(lecture.createdAtMillis))
                    if (lecture.durationS > 0) append("  ·  ${formatDuration(lecture.durationS)}")
                    if (lecture.source != "desktop-mic") append("  ·  ${lecture.source}")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (progress != null && lecture.status != LectureStatus.DONE) {
                LinearProgressIndicator(
                    progress = { progress.fraction.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Text(
                    progress.message,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (lecture.status == LectureStatus.ERROR && lecture.errorMessage != null) {
                Text(
                    lecture.errorMessage!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
