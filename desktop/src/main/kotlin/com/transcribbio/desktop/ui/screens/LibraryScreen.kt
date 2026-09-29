package com.transcribbio.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transcribbio.desktop.processing.ProcessingProgress
import com.transcribbio.desktop.ui.AppState
import com.transcribbio.desktop.ui.components.ConfirmDialog
import com.transcribbio.desktop.ui.components.GroupDialog
import com.transcribbio.desktop.ui.components.StatusChip
import com.transcribbio.desktop.ui.components.TextPromptDialog
import com.transcribbio.desktop.ui.util.LectureSearch
import com.transcribbio.desktop.ui.util.formatDate
import com.transcribbio.desktop.ui.util.formatDuration
import com.transcribbio.shared.model.Lecture
import com.transcribbio.shared.model.LectureStatus
import kotlinx.coroutines.delay

/** Group filter value for "lectures without a group". */
private const val NO_GROUP = "\u0000no-group"

@Composable
fun LibraryScreen(
    state: AppState,
    onOpen: (String) -> Unit,
    onRecord: () -> Unit,
    onImport: () -> Unit,
) {
    val lectures by state.repo.lectures.collectAsState()
    val progress by state.orchestrator.progress.collectAsState()
    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    LaunchedEffect(query) { delay(if (query.isEmpty()) 0 else 150); debounced = query }
    var groupFilter by remember { mutableStateOf<String?>(null) } // null = all groups

    var renaming by remember { mutableStateOf<Lecture?>(null) }
    var grouping by remember { mutableStateOf<Lecture?>(null) }
    var deleting by remember { mutableStateOf<Lecture?>(null) }
    var renamingGroup by remember { mutableStateOf<String?>(null) }
    var removingGroup by remember { mutableStateOf<String?>(null) }

    val groups = remember(lectures) { state.repo.groups() }
    if (groupFilter != null && groupFilter != NO_GROUP && groupFilter !in groups) groupFilter = null
    val cache = remember { LectureSearch.Cache() }
    val indexed = remember(lectures) { cache.index(lectures) }
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    val hits = remember(indexed, debounced, groupFilter, highlight) {
        LectureSearch.search(indexed, debounced, highlight).filter { h ->
            when (groupFilter) {
                null -> true
                NO_GROUP -> h.lecture.group == null
                else -> h.lecture.group == groupFilter
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Your lectures", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
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
            return@Column
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search titles, transcripts and notes") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )

        if (groups.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(selected = groupFilter == null, onClick = { groupFilter = null },
                    label = { Text("All (${lectures.size})") })
                groups.forEach { g ->
                    FilterChip(selected = groupFilter == g, onClick = { groupFilter = g },
                        leadingIcon = { Icon(Icons.Default.Folder, null, Modifier.size(16.dp)) },
                        label = { Text("$g (${lectures.count { it.group == g }})") })
                }
                val ungrouped = lectures.count { it.group == null }
                if (ungrouped > 0) FilterChip(selected = groupFilter == NO_GROUP, onClick = { groupFilter = NO_GROUP },
                    label = { Text("No group ($ungrouped)") })
                val selected = groupFilter
                if (selected != null && selected != NO_GROUP) {
                    TextButton(onClick = { renamingGroup = selected }) { Text("Rename group") }
                    TextButton(onClick = { removingGroup = selected }) { Text("Remove group") }
                }
            }
        } else {
            Text("Tip: use ⋮ ▸ Move to group to organise lectures by subject.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp))
        }

        if (hits.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Text(if (debounced.isNotBlank()) "No lectures match “$debounced”." else "No lectures in this group.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(hits, key = { it.lecture.id }) { hit ->
                    LectureRow(
                        lecture = hit.lecture,
                        snippet = hit.snippet,
                        progress = progress[hit.lecture.id],
                        onClick = { onOpen(hit.lecture.id) },
                        onRename = { renaming = hit.lecture },
                        onGroup = { grouping = hit.lecture },
                        onDelete = { deleting = hit.lecture },
                    )
                }
            }
        }
    }

    renaming?.let { l ->
        TextPromptDialog("Rename lecture", "Title", l.title, onDismiss = { renaming = null }) {
            state.renameLecture(l.id, it); renaming = null
        }
    }
    grouping?.let { l ->
        GroupDialog(l.group, groups, onDismiss = { grouping = null }) {
            state.setLectureGroup(l.id, it); grouping = null
        }
    }
    deleting?.let { l ->
        ConfirmDialog("Delete lecture?",
            "“${l.title}” — its recording, transcript and notes — will be deleted from this PC.", "Delete",
            onDismiss = { deleting = null }) { state.deleteLecture(l.id); deleting = null }
    }
    renamingGroup?.let { g ->
        TextPromptDialog("Rename group", "Group name", g, onDismiss = { renamingGroup = null }) {
            state.renameGroup(g, it); groupFilter = it; renamingGroup = null
        }
    }
    removingGroup?.let { g ->
        ConfirmDialog("Remove group “$g”?", "Its lectures stay in your library, just without a group.", "Remove group",
            onDismiss = { removingGroup = null }) { state.removeGroup(g); groupFilter = null; removingGroup = null }
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
private fun LectureRow(
    lecture: Lecture,
    snippet: AnnotatedString?,
    progress: ProcessingProgress?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onGroup: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 14.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    lecture.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                lecture.group?.let { g ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Default.Folder, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(" $g", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                StatusChip(lecture.status)
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More options") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename…") },
                            leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                            onClick = { menuOpen = false; onRename() })
                        DropdownMenuItem(text = { Text(if (lecture.group == null) "Move to group…" else "Change group…") },
                            leadingIcon = { Icon(Icons.Default.Folder, null) },
                            onClick = { menuOpen = false; onGroup() })
                        DropdownMenuItem(text = { Text("Delete…", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
            Text(
                buildString {
                    append(formatDate(lecture.createdAtMillis))
                    if (lecture.durationS > 0) append("  ·  ${formatDuration(lecture.durationS)}")
                    if (lecture.source != "desktop-mic") append("  ·  ${lecture.source}")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            snippet?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, end = 12.dp))
            }
            if (progress != null && lecture.status != LectureStatus.DONE) {
                LinearProgressIndicator(
                    progress = { progress.fraction.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, end = 12.dp),
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
                    modifier = Modifier.padding(top = 6.dp, end = 12.dp),
                )
            }
        }
    }
}
