package com.transcribbio.desktop.sidecar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// These mirror the Python sidecar's pydantic models (snake_case JSON keys).

@Serializable
data class WordDto(
    val start: Double,
    val end: Double,
    val word: String,
    val probability: Double,
)

@Serializable
data class SegmentDto(
    val id: Int,
    val start: Double,
    val end: Double,
    val text: String,
    @SerialName("avg_logprob") val avgLogprob: Double = 0.0,
    @SerialName("no_speech_prob") val noSpeechProb: Double = 0.0,
    @SerialName("compression_ratio") val compressionRatio: Double = 0.0,
    @SerialName("low_confidence") val lowConfidence: Boolean = false,
    val words: List<WordDto> = emptyList(),
)

@Serializable
data class TranscriptionResponseDto(
    val text: String,
    val language: String,
    @SerialName("language_probability") val languageProbability: Double = 0.0,
    @SerialName("duration_s") val durationS: Double = 0.0,
    val device: String = "",
    @SerialName("compute_type") val computeType: String = "",
    @SerialName("snr_db") val snrDb: Double? = null,
    val denoised: Boolean? = null,
    val segments: List<SegmentDto> = emptyList(),
)

@Serializable
data class CorrectRequestDto(val text: String, val language: String = "sk")

@Serializable
data class CorrectResponseDto(val text: String, val provider: String)

@Serializable
data class MaterialRequestDto(val kind: String, val text: String, val language: String = "sk")

@Serializable
data class FlashcardDto(val question: String, val answer: String)

@Serializable
data class MaterialResponseDto(
    val kind: String,
    val provider: String,
    val markdown: String? = null,
    val flashcards: List<FlashcardDto>? = null,
)

@Serializable
data class ProcessRequestDto(
    val path: String,
    val language: String? = null,
    val denoise: Boolean? = null,
    val materials: List<String> = emptyList(),
    val correct: Boolean = true,
)

@Serializable
data class ProcessResultDto(
    val transcription: TranscriptionResponseDto,
    @SerialName("corrected_text") val correctedText: String? = null,
    @SerialName("correction_provider") val correctionProvider: String? = null,
    val materials: Map<String, MaterialResponseDto> = emptyMap(),
)

@Serializable
data class JobRefDto(@SerialName("job_id") val jobId: String)

@Serializable
data class JobStatusDto(
    @SerialName("job_id") val jobId: String,
    val state: String,
    val stage: String = "",
    val fraction: Double = 0.0,
    val message: String = "",
    val result: ProcessResultDto? = null,
    val error: String? = null,
)

@Serializable
data class HealthDto(
    val status: String,
    val version: String = "",
    @SerialName("whisper_loaded") val whisperLoaded: Boolean = false,
    @SerialName("whisper_model") val whisperModel: String = "",
    val device: String = "",
    @SerialName("cuda_available") val cudaAvailable: Boolean = false,
)

@Serializable
data class TranscribeRequestDto(
    val path: String,
    val language: String? = null,
    val denoise: Boolean? = null,
    @SerialName("word_timestamps") val wordTimestamps: Boolean = true,
)

// ── LLM model chain: discovery + health check (Settings ▸ AI) ──
@Serializable
data class LlmModelDto(
    val name: String,
    @SerialName("display_name") val displayName: String = "",
)

@Serializable
data class LlmModelsDto(
    val gemini: List<LlmModelDto> = emptyList(),
    val ollama: List<String> = emptyList(),
    val error: String? = null,
)

@Serializable
data class LlmTestRequestDto(val models: List<String> = emptyList())

@Serializable
data class LlmTestResultDto(
    val model: String,
    val ok: Boolean,
    @SerialName("latency_s") val latencyS: Double = 0.0,
    val detail: String = "",
)

@Serializable
data class LlmTestResponseDto(
    val results: List<LlmTestResultDto> = emptyList(),
    @SerialName("ollama_ready") val ollamaReady: Boolean = false,
)
