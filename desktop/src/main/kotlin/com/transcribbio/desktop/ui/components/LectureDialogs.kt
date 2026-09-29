package com.transcribbio.desktop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.transcribbio.shared.model.Lecture
import com.transcribbio.shared.model.StudyMaterialKind

/** Single-line text prompt (rename lecture / rename group). Enter saves, Esc cancels. */
@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initial: String,
    confirmText: String = "Save",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = { if (value.text.isNotBlank()) onConfirm(value.text.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onKeyEvent {
                    when (it.key) {
                        Key.Enter, Key.NumPadEnter -> { submit(); true }
                        Key.Escape -> { onDismiss(); true }
                        else -> false
                    }
                },
            )
        },
        confirmButton = { TextButton(onClick = submit, enabled = value.text.isNotBlank()) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick an existing group, create a new one, or remove the lecture from its group. */
@Composable
fun GroupDialog(
    current: String?,
    groups: List<String>,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (groups.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.forEach { g ->
                            FilterChip(selected = g == current, onClick = { onPick(g) }, label = { Text(g) })
                        }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(if (groups.isEmpty()) "Group name (e.g. a subject)" else "…or a new group") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().onKeyEvent {
                        if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && newName.isNotBlank()) {
                            onPick(newName.trim()); true
                        } else false
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(newName.trim()) }, enabled = newName.isNotBlank()) { Text("Create & move") }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onPick(null) }) { Text("Remove from group") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmText: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Choose which AI steps to run for a lecture. Nothing runs until the user picks it here. */
@Composable
fun GenerateDialog(
    lecture: Lecture,
    onDismiss: () -> Unit,
    onGenerate: (correct: Boolean, kinds: List<StudyMaterialKind>) -> Unit,
) {
    val corrected = lecture.transcript?.cleanText != null
    var correct by remember { mutableStateOf(!corrected) }
    val picked = remember { mutableStateOf(emptySet<StudyMaterialKind>()) }
    val labels = listOf(
        StudyMaterialKind.SUMMARY to "Summary",
        StudyMaterialKind.NOTES to "Notes",
        StudyMaterialKind.TAKEAWAYS to "Key takeaways",
        StudyMaterialKind.FLASHCARDS to "Flashcards",
    )
    @Composable
    fun option(checked: Boolean, label: String, hint: String?, onToggle: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text("  $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate with AI") },
        text = {
            Column {
                Text("Pick what to create for “${lecture.title}”.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                option(correct, "Correct the transcript", if (corrected) "(again)" else null) { correct = !correct }
                labels.forEach { (kind, label) ->
                    val has = lecture.materials[kind] != null
                    option(kind in picked.value, label, if (has) "(regenerate)" else null) {
                        picked.value = if (kind in picked.value) picked.value - kind else picked.value + kind
                    }
                }
                if (correct && picked.value.isNotEmpty()) {
                    Text("The transcript is corrected first, so the materials use the cleaned-up text.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onGenerate(correct, labels.map { it.first }.filter { it in picked.value }) },
                enabled = correct || picked.value.isNotEmpty(),
            ) { Text("Generate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
