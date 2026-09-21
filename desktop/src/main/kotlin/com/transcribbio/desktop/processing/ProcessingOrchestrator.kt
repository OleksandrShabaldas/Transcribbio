package com.transcribbio.desktop.processing

import com.transcribbio.desktop.data.LibraryRepository
import com.transcribbio.desktop.sidecar.CorrectRequestDto
import com.transcribbio.desktop.sidecar.MaterialRequestDto
import com.transcribbio.desktop.sidecar.MaterialResponseDto
import com.transcribbio.desktop.sidecar.ProcessRequestDto
import com.transcribbio.desktop.sidecar.ProcessResultDto
import com.transcribbio.desktop.sidecar.SidecarManager
import com.transcribbio.shared.model.Flashcard
import com.transcribbio.shared.model.Lecture
import com.transcribbio.shared.model.LectureStatus
import com.transcribbio.shared.model.StudyMaterial
import com.transcribbio.shared.model.StudyMaterialKind
import com.transcribbio.shared.model.Transcript
import com.transcribbio.shared.model.TranscriptSegment
import com.transcribbio.shared.model.Word
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProcessingProgress(
    val lectureId: String,
    val status: LectureStatus,
    val fraction: Double,
    val message: String,
)

class ProcessingOrchestrator(
    private val sidecar: SidecarManager,
    private val repo: LibraryRepository,
    private val configProvider: () -> com.transcribbio.desktop.core.AppConfig,
) {
    private val _progress = MutableStateFlow<Map<String, ProcessingProgress>>(emptyMap())
    val progress: StateFlow<Map<String, ProcessingProgress>> = _progress.asStateFlow()

    private val _busyMaterials = MutableStateFlow<Set<String>>(emptySet()) // "lectureId:kind"
    val busyMaterials: StateFlow<Set<String>> = _busyMaterials.asStateFlow()

    // Per-action error messages, keyed "lectureId:kind" (kind "correct" for re-correction),
    // so the UI can tell the user *why* a step did nothing instead of failing silently.
    private val _materialErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val materialErrors: StateFlow<Map<String, String>> = _materialErrors.asStateFlow()

    private fun setError(key: String, msg: String) { _materialErrors.value = _materialErrors.value + (key to msg) }
    private fun clearError(key: String) { _materialErrors.value = _materialErrors.value - key }

    /** Turn a raw sidecar/provider error into something actionable for the user. */
    private fun friendlyLlmError(raw: String?): String {
        val msg = raw?.takeIf { it.isNotBlank() } ?: "Unknown error"
        val noProvider = listOf(
            "no llm provider", "unavailable", "api key", "provider", "503", "service unavailable",
        ).any { msg.contains(it, ignoreCase = true) }
        return if (noProvider)
            "No AI provider is set up. Open Settings, paste your Gemini API key and press " +
                "\"Save & apply\" — or download the offline model to use it without internet."
        else "Generation failed: $msg"
    }

    private fun setProgress(p: ProcessingProgress) {
        _progress.value = _progress.value + (p.lectureId to p)
    }

    private fun clearProgress(id: String) {
        _progress.value = _progress.value - id
    }

    private fun stageToStatus(stage: String): LectureStatus = when {
        stage.startsWith("preprocess") -> LectureStatus.PREPROCESSING
        stage.startsWith("transcribe") -> LectureStatus.TRANSCRIBING
        stage.startsWith("correct") -> LectureStatus.CORRECTING
        stage.startsWith("materials") -> LectureStatus.GENERATING
        stage == "done" -> LectureStatus.DONE
        else -> LectureStatus.QUEUED
    }

    /** Run the full pipeline (transcribe + correct + auto-materials) for a lecture. */
    suspend fun process(lectureId: String) {
        val client = sidecar.client ?: run {
            failLecture(lectureId, "Engine is not ready")
            return
        }
        var lecture = repo.get(lectureId) ?: return
        val config = configProvider()

        lecture = lecture.copy(status = LectureStatus.QUEUED, errorMessage = null)
        repo.save(lecture)
        setProgress(ProcessingProgress(lectureId, LectureStatus.QUEUED, 0.0, "Queued"))

        val req = ProcessRequestDto(
            path = repo.audioPath(lecture).toAbsolutePath().toString(),
            language = lecture.language,
            materials = config.autoGenerateMaterials,
            correct = true,
        )

        try {
            val jobId = client.submitJob(req).jobId
            while (true) {
                val st = client.jobStatus(jobId)
                val status = stageToStatus(st.stage)
                setProgress(ProcessingProgress(lectureId, status, st.fraction, st.message))
                if (repo.get(lectureId)?.status != status && st.state == "running") {
                    repo.get(lectureId)?.let { repo.save(it.copy(status = status)) }
                }
                when (st.state) {
                    "done" -> {
                        applyResult(lectureId, st.result!!)
                        clearProgress(lectureId)
                        return
                    }
                    "error" -> {
                        failLecture(lectureId, st.error ?: "Processing failed")
                        clearProgress(lectureId)
                        return
                    }
                }
                delay(500)
            }
        } catch (e: Exception) {
            failLecture(lectureId, e.message ?: "Processing failed")
            clearProgress(lectureId)
        }
    }

    private fun applyResult(lectureId: String, result: ProcessResultDto) {
        val lecture = repo.get(lectureId) ?: return
        val transcript = toTranscript(result, lecture.language)
        val materials = result.materials.mapNotNull { (k, v) ->
            runCatching { StudyMaterialKind.fromApi(k) }.getOrNull()?.let { it to toMaterial(it, v) }
        }.toMap()
        repo.save(
            lecture.copy(
                status = LectureStatus.DONE,
                transcript = transcript,
                durationS = transcript.durationS.takeIf { it > 0 } ?: lecture.durationS,
                language = transcript.language.ifBlank { lecture.language },
                materials = lecture.materials + materials,
                errorMessage = null,
            )
        )
    }

    private fun failLecture(lectureId: String, message: String) {
        repo.get(lectureId)?.let {
            repo.save(it.copy(status = LectureStatus.ERROR, errorMessage = message))
        }
    }

    /** Generate a single study material on demand (lazy tabs). */
    suspend fun generateMaterial(lectureId: String, kind: StudyMaterialKind) {
        val key = "$lectureId:${kind.api}"
        val client = sidecar.client ?: run {
            setError(key, "The engine isn't ready yet — give it a moment to start (see Settings › Engine), then try again.")
            return
        }
        val lecture = repo.get(lectureId) ?: return
        val text = lecture.transcript?.bestText ?: run {
            setError(key, "Transcribe this lecture first, then generate study materials.")
            return
        }
        clearError(key)
        _busyMaterials.value = _busyMaterials.value + key
        try {
            val resp = client.material(MaterialRequestDto(kind.api, text, lecture.language))
            val updated = repo.get(lectureId) ?: lecture
            repo.save(updated.copy(materials = updated.materials + (kind to toMaterial(kind, resp))))
            clearError(key)
        } catch (e: Exception) {
            setError(key, friendlyLlmError(e.message))
        } finally {
            _busyMaterials.value = _busyMaterials.value - key
        }
    }

    /** Re-run the LLM correction pass over the raw transcript. */
    suspend fun reCorrect(lectureId: String) {
        val key = "$lectureId:correct"
        val client = sidecar.client ?: run {
            setError(key, "The engine isn't ready yet — give it a moment to start (see Settings › Engine), then try again.")
            return
        }
        val lecture = repo.get(lectureId) ?: return
        val t = lecture.transcript ?: return
        clearError(key)
        _busyMaterials.value = _busyMaterials.value + key
        try {
            val resp = client.correct(CorrectRequestDto(t.rawText, lecture.language))
            val updated = repo.get(lectureId) ?: lecture
            val nt = (updated.transcript ?: t).copy(cleanText = resp.text, correctionProvider = resp.provider)
            repo.save(updated.copy(transcript = nt))
            clearError(key)
        } catch (e: Exception) {
            setError(key, friendlyLlmError(e.message))
        } finally {
            _busyMaterials.value = _busyMaterials.value - key
        }
    }

    // ── DTO → model mapping ──
    private fun toTranscript(result: ProcessResultDto, fallbackLang: String): Transcript {
        val tr = result.transcription
        return Transcript(
            language = tr.language.ifBlank { fallbackLang },
            languageProbability = tr.languageProbability,
            durationS = tr.durationS,
            device = tr.device,
            computeType = tr.computeType,
            snrDb = tr.snrDb,
            denoised = tr.denoised,
            rawText = tr.text,
            cleanText = result.correctedText,
            correctionProvider = result.correctionProvider,
            segments = tr.segments.map { s ->
                TranscriptSegment(
                    id = s.id, start = s.start, end = s.end, text = s.text,
                    lowConfidence = s.lowConfidence,
                    words = s.words.map { Word(it.start, it.end, it.word, it.probability) },
                )
            },
        )
    }

    private fun toMaterial(kind: StudyMaterialKind, dto: MaterialResponseDto): StudyMaterial =
        StudyMaterial(
            kind = kind,
            provider = dto.provider,
            markdown = dto.markdown,
            flashcards = dto.flashcards?.map { Flashcard(it.question, it.answer) },
            generatedAtMillis = System.currentTimeMillis(),
        )
}
