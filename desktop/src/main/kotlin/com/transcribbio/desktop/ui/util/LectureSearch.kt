package com.transcribbio.desktop.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.transcribbio.shared.model.Lecture
import java.text.Normalizer

/**
 * Library search that suits Slovak: case- and accent-insensitive ("dychanie" finds "dýchanie"),
 * across title, group, transcript and study materials. Every word of the query must match.
 */
object LectureSearch {
    private val MARKS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")

    fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

    class Indexed(val lecture: Lecture, val head: String, val body: String, val foldedBody: String)

    data class Hit(val lecture: Lecture, val snippet: AnnotatedString?)

    /** Remembers each lecture's folded text so typing doesn't re-normalise long transcripts. */
    class Cache {
        private val entries = HashMap<String, Pair<Int, Indexed>>()

        fun index(lectures: List<Lecture>): List<Indexed> {
            val live = lectures.mapTo(HashSet()) { it.id }
            entries.keys.retainAll(live)
            return lectures.map { l ->
                val key = listOf(l.title, l.group, l.transcript?.rawText?.length, l.transcript?.cleanText?.length,
                    l.materials.values.sumOf { (it.markdown?.length ?: 0) + (it.flashcards?.size ?: 0) }).hashCode()
                entries[l.id]?.takeIf { it.first == key }?.second ?: build(l).also { entries[l.id] = key to it }
            }
        }

        private fun build(l: Lecture): Indexed {
            val body = buildString {
                append(l.transcript?.cleanText ?: l.transcript?.rawText ?: "")
                l.materials.values.forEach { m ->
                    m.markdown?.let { append('\n').append(it) }
                    m.flashcards?.forEach { append('\n').append(it.question).append(' ').append(it.answer) }
                }
            }.let { Normalizer.normalize(it, Normalizer.Form.NFC).replace(SPACES, " ") }
            return Indexed(l, fold(l.title + " " + (l.group ?: "")), body, fold(body))
        }
    }

    fun search(items: List<Indexed>, query: String, highlight: Color): List<Hit> {
        val terms = fold(query).split(SPACES).filter { it.isNotBlank() }
        if (terms.isEmpty()) return items.map { Hit(it.lecture, null) }
        return items.mapNotNull { ix ->
            if (terms.all { it in ix.head || it in ix.foldedBody }) Hit(ix.lecture, snippet(ix, terms, highlight)) else null
        }
    }

    /** "…text around the first match…" with the match highlighted; null if only the title matched. */
    private fun snippet(ix: Indexed, terms: List<String>, highlight: Color): AnnotatedString? {
        val (term, at) = terms.firstNotNullOfOrNull { t -> ix.foldedBody.indexOf(t).takeIf { it >= 0 }?.let { t to it } }
            ?: return null
        // Folding keeps lengths for precomposed (NFC) Slovak text; if not, show the folded text instead.
        val text = if (ix.foldedBody.length == ix.body.length) ix.body else ix.foldedBody
        val start = (at - 70).coerceAtLeast(0)
        val end = (at + term.length + 90).coerceAtMost(text.length)
        return buildAnnotatedString {
            if (start > 0) append("…")
            append(text.substring(start, at))
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, background = highlight))
            append(text.substring(at, at + term.length))
            pop()
            append(text.substring(at + term.length, end))
            if (end < text.length) append("…")
        }
    }
}
