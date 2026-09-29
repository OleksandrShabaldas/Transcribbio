package com.transcribbio.desktop

import androidx.compose.ui.graphics.Color
import com.transcribbio.desktop.core.AppConfig
import com.transcribbio.desktop.core.AppEnvironment
import com.transcribbio.desktop.data.LibraryRepository
import com.transcribbio.desktop.drive.DriveInbox
import com.transcribbio.desktop.ui.util.LectureSearch
import com.transcribbio.shared.drive.DriveApi
import com.transcribbio.shared.drive.RelayRecording
import com.transcribbio.shared.model.Lecture
import com.transcribbio.shared.model.Transcript
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryFeaturesTest {
    private fun lecture(id: String, title: String, text: String, group: String? = null) = Lecture(
        id = id, title = title, createdAtMillis = 1L, group = group,
        transcript = Transcript(language = "sk", languageProbability = 1.0, durationS = 1.0, device = "cpu",
            computeType = "int8", rawText = text),
    )

    @Test
    fun searchIsAccentAndCaseInsensitive() {
        val cache = LectureSearch.Cache()
        val items = cache.index(listOf(
            lecture("a", "Bunkové dýchanie", "Dnes sa budeme venovať téme bunkového dýchania v mitochondriách.", "Biológia"),
            lecture("b", "Genetika – úvod", "DNA sa replikuje semikonzervatívne.", "Genetika"),
        ))
        fun ids(q: String) = LectureSearch.search(items, q, Color.Yellow).map { it.lecture.id }
        assertEquals(listOf("a"), ids("dychanie"), "no accents needed")
        assertEquals(listOf("a"), ids("MITOCHONDRIACH"), "case-insensitive, in transcript")
        assertEquals(listOf("b"), ids("genetika uvod"), "all words must match (title)")
        assertEquals(listOf("a"), ids("biologia"), "matches the group name")
        assertEquals(emptyList(), ids("dychanie dna"), "words from different lectures don't combine")
        assertEquals(listOf("a", "b"), ids(""), "empty query shows everything")
        val snip = LectureSearch.search(items, "mitochondriach", Color.Yellow).single().snippet!!
        val hl = snip.spanStyles.single()
        assertEquals("mitochondriách", snip.text.substring(hl.start, hl.end), "highlights the original (accented) text")
    }

    @Test
    fun renameAndGroups() {
        val repo = LibraryRepository(AppEnvironment(Files.createTempDirectory("tb-lib")).also { it.ensureDirs() })
        repo.loadAll()
        listOf("a" to "Enzýmy", "b" to "Genetika", "c" to "Bunka").forEach { (id, t) -> repo.save(lecture(id, t, "x")) }
        repo.rename("a", "  Enzýmy – kinetika  ")
        assertEquals("Enzýmy – kinetika", repo.get("a")!!.title, "trimmed")
        repo.rename("a", "   ")
        assertEquals("Enzýmy – kinetika", repo.get("a")!!.title, "blank names ignored")
        repo.setGroup("a", "Biochémia"); repo.setGroup("b", "Genetika"); repo.setGroup("c", "Biochémia")
        assertEquals(listOf("Biochémia", "Genetika"), repo.groups())
        repo.renameGroup("Biochémia", "Biochémia I")
        assertEquals(listOf("Biochémia I", "Biochémia I"), listOf(repo.get("a")!!.group, repo.get("c")!!.group))
        repo.renameGroup("Biochémia I", null)
        assertNull(repo.get("a")!!.group); assertEquals(listOf("Genetika"), repo.groups())
        // survives a reload from disk
        val reloaded = LibraryRepository(AppEnvironment(repo.lectureDir("a").parent.parent)).also { it.loadAll() }
        assertEquals("Enzýmy – kinetika", reloaded.get("a")!!.title)
        assertEquals("Genetika", reloaded.get("b")!!.group)
    }

    @Test
    fun driveInboxAppliesPhoneRenames() = runBlocking {
        val b = System.getenv("DRIVE_MOCK") ?: return@runBlocking println("DRIVE_MOCK not set — skipped")
        val api = DriveApi(HttpClient(CIO), "$b/drive/v3", "$b/upload/drive/v3")
        val audio = ByteArray(5_000) { it.toByte() }
        api.upload("testtoken", RelayRecording("rec-99", "Lecture 29 Sep", "sk", 1L, "phone", "m4a"),
            audio.size.toLong(), "audio/mp4", { o, l -> audio.copyOfRange(o.toInt(), o.toInt() + l) })

        val dir = Files.createTempDirectory("tb-rn")
        val repo = LibraryRepository(AppEnvironment(dir).also { it.ensureDirs() }).also { it.loadAll() }
        var cfg = AppConfig(dataDir = dir.toString(), driveClientId = "id", driveClientSecret = "sec",
            driveRefreshToken = "rt-1", driveAccount = "t@x")
        val inbox = DriveInbox(CoroutineScope(SupervisorJob() + Dispatchers.IO), dir, repo, { cfg },
            { t -> cfg = t(cfg) }, onImported = {}, driveApiBase = "$b/drive/v3", tokenUrl = "$b/token")

        inbox.checkOnce()
        val imported = assertNotNull(repo.findBySourceRecording("rec-99"), "remembers the phone recording id")
        assertEquals("Lecture 29 Sep", imported.title)

        api.postRename("testtoken", "rec-99", "Genetika – mutácie") // renamed on the phone later
        inbox.checkOnce()
        assertEquals("Genetika – mutácie", repo.get(imported.id)!!.title, "phone rename applied")
        assertTrue(api.listRenames("testtoken").isEmpty(), "rename note cleaned up")
        println("OK phone rename via Drive")
    }
}
