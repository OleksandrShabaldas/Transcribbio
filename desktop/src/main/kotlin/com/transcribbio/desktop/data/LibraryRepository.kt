package com.transcribbio.desktop.data

import com.transcribbio.desktop.core.AppEnvironment
import com.transcribbio.shared.model.Lecture
import com.transcribbio.shared.model.LectureStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.name

/** Local-first library: one directory per lecture holding lecture.json + audio. */
class LibraryRepository(private val env: AppEnvironment) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private val _lectures = MutableStateFlow<List<Lecture>>(emptyList())
    val lectures: StateFlow<List<Lecture>> = _lectures.asStateFlow()

    fun lectureDir(id: String): Path = env.libraryDir.resolve(id)
    fun audioPath(lecture: Lecture): Path = lectureDir(lecture.id).resolve(lecture.audioFileName)

    fun loadAll() {
        env.ensureDirs()
        val list = mutableListOf<Lecture>()
        Files.list(env.libraryDir).use { stream ->
            stream.filter { Files.isDirectory(it) }.forEach { dir ->
                val f = dir.resolve("lecture.json")
                if (Files.exists(f)) {
                    runCatching { json.decodeFromString(Lecture.serializer(), Files.readString(f)) }
                        .onSuccess { list.add(it) }
                }
            }
        }
        _lectures.value = list.sortedByDescending { it.createdAtMillis }
    }

    fun save(lecture: Lecture) {
        val dir = lectureDir(lecture.id)
        Files.createDirectories(dir)
        val tmp = dir.resolve("lecture.json.tmp")
        Files.writeString(tmp, json.encodeToString(Lecture.serializer(), lecture))
        Files.move(tmp, dir.resolve("lecture.json"),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        upsertInFlow(lecture)
    }

    fun delete(id: String) {
        val dir = lectureDir(id)
        if (Files.exists(dir)) {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        _lectures.value = _lectures.value.filterNot { it.id == id }
    }

    private fun upsertInFlow(lecture: Lecture) {
        val others = _lectures.value.filterNot { it.id == lecture.id }
        _lectures.value = (others + lecture).sortedByDescending { it.createdAtMillis }
    }

    fun get(id: String): Lecture? = _lectures.value.firstOrNull { it.id == id }

    /** The lecture that came from a given phone recording (for renames made on the phone). */
    fun findBySourceRecording(recordingId: String): Lecture? =
        _lectures.value.firstOrNull { it.sourceRecordingId == recordingId }

    fun rename(id: String, title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        get(id)?.let { if (it.title != t) save(it.copy(title = t)) }
    }

    /** Put a lecture in a group (null / blank = no group). */
    fun setGroup(id: String, group: String?) {
        val g = group?.trim()?.ifBlank { null }
        get(id)?.let { if (it.group != g) save(it.copy(group = g)) }
    }

    /** Rename a group (or pass null to dissolve it) across all its lectures. */
    fun renameGroup(from: String, to: String?) {
        val target = to?.trim()?.ifBlank { null }
        _lectures.value.filter { it.group == from }.forEach { save(it.copy(group = target)) }
    }

    /** Existing groups, alphabetical (case- and accent-insensitive). */
    fun groups(): List<String> = _lectures.value.mapNotNull { it.group }.distinct()
        .sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale("sk"))) { it })

    fun newId(): String {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val rand = (1000..9999).random()
        return "$ts-$rand"
    }

    /** Create a new lecture directory and return the audio target path to record into. */
    fun createForRecording(title: String, language: String): Pair<Lecture, Path> {
        val id = newId()
        val dir = lectureDir(id)
        Files.createDirectories(dir)
        val lecture = Lecture(
            id = id,
            title = title,
            createdAtMillis = System.currentTimeMillis(),
            source = "desktop-mic",
            audioFileName = "audio.wav",
            language = language,
            status = LectureStatus.RECORDED,
        )
        return lecture to audioPath(lecture)
    }

    /** Import an external audio file into a new lecture. */
    fun importAudio(src: Path, title: String, language: String): Lecture {
        val id = newId()
        val dir = lectureDir(id)
        Files.createDirectories(dir)
        val ext = src.name.substringAfterLast('.', "wav").lowercase()
        val audioName = "audio.$ext"
        Files.copy(src, dir.resolve(audioName), StandardCopyOption.REPLACE_EXISTING)
        val lecture = Lecture(
            id = id,
            title = title,
            createdAtMillis = System.currentTimeMillis(),
            source = "import",
            audioFileName = audioName,
            language = language,
            status = LectureStatus.RECORDED,
        )
        save(lecture)
        return lecture
    }

    fun exportPath(id: String, fileName: String): Path = lectureDir(id).resolve(fileName)

    /** Persist an uploaded recording (from phone/watch) as a new lecture. */
    fun saveUpload(
        input: java.io.InputStream,
        originalFileName: String,
        title: String,
        language: String,
        source: String,
        createdAtMillis: Long,
        sourceRecordingId: String? = null,
    ): Lecture {
        val id = newId()
        val dir = lectureDir(id)
        Files.createDirectories(dir)
        val ext = originalFileName.substringAfterLast('.', "m4a").lowercase().ifBlank { "m4a" }
        val audioName = "audio.$ext"
        Files.newOutputStream(dir.resolve(audioName)).use { out -> input.copyTo(out) }
        val lecture = Lecture(
            id = id,
            title = title.ifBlank { "Lecture" },
            createdAtMillis = if (createdAtMillis > 0) createdAtMillis else System.currentTimeMillis(),
            source = source.ifBlank { "phone" },
            audioFileName = audioName,
            language = language.ifBlank { "sk" },
            status = LectureStatus.RECORDED,
            sourceRecordingId = sourceRecordingId?.ifBlank { null },
        )
        save(lecture)
        return lecture
    }
}
