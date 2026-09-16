package com.transcribbio.phone.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.transcribbio.phone.AppGraph
import com.transcribbio.phone.ui.components.MarkdownText
import com.transcribbio.phone.ui.util.formatDuration
import com.transcribbio.phone.ui.util.languageLabel
import com.transcribbio.shared.model.Lecture
import com.transcribbio.shared.model.StudyMaterialKind

private val TABS = listOf("Transcript", "Summary", "Notes", "Takeaways", "Flashcards")

@Composable
fun LectureDetailScreen(id: String) {
    var lecture by remember { mutableStateOf<Lecture?>(null) }
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(0) }

    LaunchedEffect(id) {
        loading = true
        lecture = AppGraph.sync.fetchLecture(id)
        loading = false
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        lecture == null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
            Text("Couldn't load this lecture. Make sure your desktop is reachable.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            val l = lecture!!
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(l.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        buildString {
                            append(languageLabel(l.language))
                            if (l.durationS > 0) append("  ·  ${formatDuration(l.durationS)}")
                            l.transcript?.correctionProvider?.let { append("  ·  corrected: $it") }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (l.transcript == null) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("Still processing on your desktop…", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    return@Column
                }

                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
                    TABS.forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                    }
                }

                val scroll = rememberScrollState()
                Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp).verticalScroll(scroll)) {
                    when (tab) {
                        0 -> Text(l.transcript!!.bestText, style = MaterialTheme.typography.bodyLarge)
                        1 -> MaterialContent(l, StudyMaterialKind.SUMMARY)
                        2 -> MaterialContent(l, StudyMaterialKind.NOTES)
                        3 -> MaterialContent(l, StudyMaterialKind.TAKEAWAYS)
                        4 -> FlashcardsContent(l)
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialContent(lecture: Lecture, kind: StudyMaterialKind) {
    val md = lecture.materials[kind]?.markdown
    if (md != null) MarkdownText(md)
    else Text("Not generated yet. Open this lecture on your desktop to create it.",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FlashcardsContent(lecture: Lecture) {
    val cards = lecture.materials[StudyMaterialKind.FLASHCARDS]?.flashcards
    if (cards.isNullOrEmpty()) {
        Text("No flashcards yet. Generate them on your desktop.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cards.forEach { c ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                Column(Modifier.padding(14.dp)) {
                    Text(c.question, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(c.answer, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
