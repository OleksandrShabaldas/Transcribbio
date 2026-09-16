package com.transcribbio.desktop.ui.util

import com.transcribbio.shared.model.LectureStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

fun formatDuration(seconds: Double): String {
    val s = seconds.toInt().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatMillisClock(ms: Long): String = formatDuration(ms / 1000.0)

fun formatDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateFmt)

fun statusLabel(status: LectureStatus): String = when (status) {
    LectureStatus.RECORDED -> "Not processed"
    LectureStatus.UPLOADING -> "Uploading"
    LectureStatus.QUEUED -> "Queued"
    LectureStatus.PREPROCESSING -> "Preparing audio"
    LectureStatus.TRANSCRIBING -> "Transcribing"
    LectureStatus.CORRECTING -> "Correcting"
    LectureStatus.GENERATING -> "Generating notes"
    LectureStatus.DONE -> "Ready"
    LectureStatus.ERROR -> "Error"
}

fun languageLabel(code: String): String = when (code.lowercase()) {
    "sk" -> "Slovak"
    "en" -> "English"
    "cs" -> "Czech"
    "auto" -> "Auto-detect"
    else -> code
}
