package com.transcribbio.shared.model

import kotlinx.serialization.Serializable

/** Lifecycle of a lecture from capture to finished study materials. */
@Serializable
enum class LectureStatus {
    RECORDED,      // audio captured, not yet processed
    UPLOADING,     // transferring from a device to the desktop (phase 2)
    QUEUED,        // waiting for the processing pipeline
    PREPROCESSING, // decode / denoise / normalize
    TRANSCRIBING,  // Whisper
    CORRECTING,    // LLM cleanup pass
    GENERATING,    // study materials
    DONE,
    ERROR,
}

@Serializable
enum class StudyMaterialKind {
    SUMMARY, NOTES, TAKEAWAYS, FLASHCARDS;

    companion object {
        fun fromApi(s: String): StudyMaterialKind = valueOf(s.uppercase())
    }

    val api: String get() = name.lowercase()
}

@Serializable
enum class LlmProvider { GEMINI, OLLAMA, NONE }

@Serializable
data class Word(
    val start: Double,
    val end: Double,
    val word: String,
    val probability: Double,
)

@Serializable
data class TranscriptSegment(
    val id: Int,
    val start: Double,
    val end: Double,
    val text: String,
    val lowConfidence: Boolean = false,
    val words: List<Word> = emptyList(),
)

@Serializable
data class Transcript(
    val language: String,
    val languageProbability: Double = 0.0,
    val durationS: Double = 0.0,
    val device: String = "",
    val computeType: String = "",
    val snrDb: Double? = null,
    val denoised: Boolean? = null,
    val rawText: String = "",
    val cleanText: String? = null,
    val correctionProvider: String? = null,
    val segments: List<TranscriptSegment> = emptyList(),
) {
    /** Preferred text for display and downstream generation. */
    val bestText: String get() = cleanText?.takeIf { it.isNotBlank() } ?: rawText
}

@Serializable
data class Flashcard(
    val question: String,
    val answer: String,
)

@Serializable
data class StudyMaterial(
    val kind: StudyMaterialKind,
    val provider: String = "",
    val markdown: String? = null,
    val flashcards: List<Flashcard>? = null,
    val generatedAtMillis: Long = 0L,
)

/** The persisted aggregate for one lecture. */
@Serializable
data class Lecture(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val source: String = "desktop",
    val audioFileName: String = "audio.wav",
    val durationS: Double = 0.0,
    val language: String = "sk",
    val status: LectureStatus = LectureStatus.RECORDED,
    val transcript: Transcript? = null,
    val materials: Map<StudyMaterialKind, StudyMaterial> = emptyMap(),
    val errorMessage: String? = null,
) {
    fun hasMaterial(kind: StudyMaterialKind): Boolean = materials.containsKey(kind)
}

/** Identifies a capture device (used by the phone/watch sync in later phases). */
@Serializable
enum class DeviceKind { DESKTOP, PHONE, WATCH }

@Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    val kind: DeviceKind,
)
