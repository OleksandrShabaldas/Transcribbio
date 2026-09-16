package com.transcribbio.phone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.transcribbio.phone.ui.util.statusLabel
import com.transcribbio.shared.model.LectureStatus

@Composable
fun StatusChip(status: LectureStatus) {
    val (bg, fg) = when (status) {
        LectureStatus.DONE -> Color(0xFFDCFCE7) to Color(0xFF166534)
        LectureStatus.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        LectureStatus.RECORDED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Text(
        statusLabel(status),
        style = MaterialTheme.typography.labelLarge,
        color = fg,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
