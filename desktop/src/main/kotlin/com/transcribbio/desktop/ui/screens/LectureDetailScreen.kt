package com.transcribbio.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.transcribbio.desktop.ui.AppState
import com.transcribbio.desktop.ui.components.MarkdownText
import com.transcribbio.desktop.ui.util.formatDuration
import com.transcribbio.desktop.ui.util.languageLabel
import com.transcribbio.shared.model.Flashcard
import com.transcribbio.shared.model.Lecture
import com.transcribbio.shared.model.LectureStatus
import com.transcribbio.shared.model.StudyMaterialKind
import com.transcribbio.shared.model.Transcript
import java.nio.file.Files
import java.nio.file.Path

private val TABS = listOf("Transcript", "Summary", "Notes", "Takeaways", "Flashcards")

@Composable
fun LectureDetailScreen(
    state: AppState,
    lectureId: String,
    pickSaveFile: (String) -> Path?,
) {
    val lectures by state.repo.lectures.collectAsState()
    val busy by state.orchestrator.busyMaterials.collectAsState()
    val progressMap by state.orchestrator.progress.collectAsState()
    val lecture = lectures.firstOrNull { it.id == lectureId } ?: run {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Lecture not found") }
        return
    }
    var tab by remember { mutableStateOf(0) }
    val progress = progressMap[lectureId]

    Column(Modifier.fillMaxSize()) {
        DetailHeader(state, lecture)

        if (progress != null && lecture.status != LectureStatus.DONE) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                LinearProgressIndicator(
                    progress = { progress.fraction.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(progress.message, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
        }

        if (lecture.transcript == null) {
            NotProcessedYet(state, lecture)
            return@Column
        }

        TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
            TABS.forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }

        Box(Modifier.fillMaxSize().padding(24.dp)) {
            when (tab) {
                0 -> TranscriptTab(lecture.transcript!!)
                1 -> MaterialTab(state, lecture, StudyMaterialKind.SUMMARY, busy)
                2 -> MaterialTab(state, lecture, StudyMaterialKind.NOTES, busy)
                3 -> MaterialTab(state, lecture, StudyMaterialKind.TAKEAWAYS, busy)
                4 -> FlashcardsTab(state, lecture, busy, pickSaveFile)
            }
        }
    }
}

@Composable
private fun DetailHeader(state: AppState, lecture: Lecture) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(lecture.title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val t = lecture.transcript
            InfoChip(languageLabel(lecture.language))
            if (lecture.durationS > 0) InfoChip(formatDuration(lecture.durationS))
            if (t != null) {
                InfoChip(if (t.device == "cuda") "GPU" else t.device.uppercase())
                t.snrDb?.let { InfoChip("SNR ${it.toInt()} dB") }
                if (t.denoised == true) InfoChip("Denoised")
                t.correctionProvider?.let { InfoChip("Corrected: $it") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (lecture.transcript != null) {
                OutlinedButton(onClick = { state.reCorrect(lecture.id) }) {
                    Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp)); Text("  Re-correct")
                }
            }
            if (lecture.status != LectureStatus.DONE &&
                lecture.status != LectureStatus.TRANSCRIBING &&
                lecture.status != LectureStatus.CORRECTING
            ) {
                Button(onClick = { state.processLecture(lecture.id) }) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Text(if (lecture.status == LectureStatus.ERROR) "  Retry" else "  Process")
                }
            }
            TextButton(onClick = { state.deleteLecture(lecture.id) }) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error)
                Text("  Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    AssistChip(onClick = {}, label = { Text(text, style = MaterialTheme.typography.labelLarge) })
}

@Composable
private fun NotProcessedYet(state: AppState, lecture: Lecture) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (lecture.status == LectureStatus.ERROR) {
                Text("Processing failed", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error)
                lecture.errorMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("This lecture hasn't been transcribed yet.",
                    style = MaterialTheme.typography.titleMedium)
            }
            Button(onClick = { state.processLecture(lecture.id) }) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                Text(if (lecture.status == LectureStatus.ERROR) "  Retry" else "  Transcribe now")
            }
        }
    }
}

@Composable
private fun TranscriptTab(transcript: Transcript) {
    var showRaw by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (showRaw) "Raw transcript (low-confidence highlighted)" else "Corrected transcript",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
            )
            if (transcript.cleanText != null) {
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(if (showRaw) "Show corrected" else "Show raw")
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        SelectionContainer(Modifier.verticalScroll(scroll)) {
            if (showRaw || transcript.cleanText == null) {
                Text(buildRawAnnotated(transcript), style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(transcript.cleanText!!, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun buildRawAnnotated(transcript: Transcript): AnnotatedString = buildAnnotatedString {
    val highlight = MaterialTheme.colorScheme.errorContainer
    if (transcript.segments.isEmpty()) {
        append(transcript.rawText); return@buildAnnotatedString
    }
    transcript.segments.forEachIndexed { i, seg ->
        if (seg.lowConfidence) {
            pushStyle(SpanStyle(background = highlight))
            append(seg.text)
            pop()
        } else append(seg.text)
        if (i != transcript.segments.lastIndex) append(" ")
    }
}

@Composable
private fun MaterialTab(state: AppState, lecture: Lecture, kind: StudyMaterialKind, busy: Set<String>) {
    val material = lecture.materials[kind]
    val isBusy = busy.contains("${lecture.id}:${kind.api}")
    val scroll = rememberScrollState()
    when {
        material?.markdown != null -> Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            MarkdownText(material.markdown!!)
            Spacer(Modifier.height(8.dp))
            ProviderNote(material.provider) { state.generateMaterial(lecture.id, kind) }
        }
        else -> GeneratePrompt(kind.name.lowercase(), isBusy) { state.generateMaterial(lecture.id, kind) }
    }
}

@Composable
private fun FlashcardsTab(state: AppState, lecture: Lecture, busy: Set<String>, pickSaveFile: (String) -> Path?) {
    val material = lecture.materials[StudyMaterialKind.FLASHCARDS]
    val cards = material?.flashcards
    val isBusy = busy.contains("${lecture.id}:flashcards")
    if (cards.isNullOrEmpty()) {
        GeneratePrompt("flashcards", isBusy) { state.generateMaterial(lecture.id, StudyMaterialKind.FLASHCARDS) }
        return
    }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${cards.size} flashcards", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                pickSaveFile("${lecture.title}-flashcards.csv")?.let { writeAnkiCsv(it, cards) }
            }) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Text("  Export for Anki")
            }
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = { state.generateMaterial(lecture.id, StudyMaterialKind.FLASHCARDS) }) {
                Text("Regenerate")
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxSize().verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            cards.forEach { FlashcardCard(it) }
        }
    }
}

@Composable
private fun FlashcardCard(card: Flashcard) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
        Column(Modifier.padding(14.dp)) {
            SelectionContainer {
                Column {
                    Text(card.question, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(card.answer, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun GeneratePrompt(name: String, busy: Boolean, onGenerate: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) {
                CircularProgressIndicator()
                Text("Generating $name…", style = MaterialTheme.typography.bodyLarge)
            } else {
                Text("No $name generated yet.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onGenerate) {
                    Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp))
                    Text("  Generate $name")
                }
            }
        }
    }
}

@Composable
private fun ProviderNote(provider: String, onRegenerate: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Generated via $provider", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = onRegenerate) { Text("Regenerate") }
    }
}

private fun writeAnkiCsv(path: Path, cards: List<Flashcard>) {
    val sb = StringBuilder()
    for (c in cards) {
        sb.append('"').append(c.question.replace("\"", "\"\"")).append('"').append(',')
        sb.append('"').append(c.answer.replace("\"", "\"\"")).append('"').append('\n')
    }
    Files.writeString(path, sb.toString())
}
