package com.transcribbio.desktop.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Minimal Markdown renderer: headings, bullets, and **bold** inline spans.
 *  Sufficient for the study materials this app generates. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val lines = markdown.replace("\r\n", "\n").split("\n")
    Column(modifier) {
        for (raw in lines) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(8.dp))
                line.startsWith("### ") -> Text(
                    inline(line.removePrefix("### ")),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                line.startsWith("## ") -> Text(
                    inline(line.removePrefix("## ")),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 3.dp),
                )
                line.startsWith("# ") -> Text(
                    inline(line.removePrefix("# ")),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                line.startsWith("- ") || line.startsWith("* ") -> BulletLine(line.drop(2), indent = 0)
                line.startsWith("  - ") || line.startsWith("  * ") -> BulletLine(line.drop(4), indent = 1)
                line.startsWith("    - ") || line.startsWith("    * ") -> BulletLine(line.drop(6), indent = 2)
                else -> Text(
                    inline(line),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun BulletLine(text: String, indent: Int) {
    Row(Modifier.padding(start = (12 + indent * 16).dp, top = 1.dp, bottom = 1.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Text(inline(text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Parse **bold** spans (and strip stray backticks) into an AnnotatedString. */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val s = text.replace("`", "")
    while (i < s.length) {
        val start = s.indexOf("**", i)
        if (start == -1) {
            append(s.substring(i)); break
        }
        append(s.substring(i, start))
        val end = s.indexOf("**", start + 2)
        if (end == -1) {
            append(s.substring(start)); break
        }
        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        append(s.substring(start + 2, end))
        pop()
        i = end + 2
    }
}
