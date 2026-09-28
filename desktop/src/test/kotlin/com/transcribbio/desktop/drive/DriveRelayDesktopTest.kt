package com.transcribbio.desktop.drive

import com.transcribbio.desktop.core.AppConfig
import com.transcribbio.desktop.core.AppEnvironment
import com.transcribbio.desktop.data.LibraryRepository
import com.transcribbio.shared.drive.DriveApi
import com.transcribbio.shared.drive.RelayRecording
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Desktop half of the Drive relay against the fake server (DRIVE_MOCK=http://127.0.0.1:PORT;
 * skipped otherwise): the browser sign-in round trip, and a full inbox pass that downloads a
 * phone recording, imports it into a real library and deletes it from Drive.
 */
class DriveRelayDesktopTest {
    private val base: String? = System.getenv("DRIVE_MOCK")

    @Test
    fun parsesClientFiles() {
        val c = GoogleDesktopAuth.parseClientFile("""{"installed":{"client_id":"id.apps.googleusercontent.com","client_secret":"sec"}}""")
        assertEquals("id.apps.googleusercontent.com", c.clientId)
        assertFailsWith<IllegalArgumentException> { GoogleDesktopAuth.parseClientFile("""{"web":{"client_id":"a","client_secret":"b"}}""") }
        assertFailsWith<Exception> { GoogleDesktopAuth.parseClientFile("""{"foo":1}""") }
    }

    @Test
    fun browserSignInRoundTrip() = runBlocking {
        val b = base ?: return@runBlocking println("DRIVE_MOCK not set — skipped")
        val auth = GoogleDesktopAuth(HttpClient(CIO), tokenUrl = "$b/token", revokeUrl = "$b/revoke")
        var pageText = ""
        var authUrl = ""
        // Play the browser: follow Google's redirect back to our loopback server with a code.
        val refresh = auth.signIn(DesktopClient("id", "sec")) { url ->
            authUrl = url
            val q = URI(url).rawQuery.split('&').associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            Thread {
                (URI("${q["redirect_uri"]}/?state=${q["state"]}&code=abc&scope=x").toURL().openConnection() as HttpURLConnection)
                    .run { pageText = inputStream.bufferedReader().readText(); disconnect() }
            }.start()
        }
        assertEquals("rt-1", refresh)
        assertTrue("code_challenge_method=S256" in authUrl && "access_type=offline" in authUrl)
        assertTrue("drive.appdata" in URLDecoder.decode(authUrl, "UTF-8"))
        Thread.sleep(200)
        assertTrue("connected to Google Drive" in pageText, "browser shows success page")
        assertEquals("testtoken", auth.accessToken(DesktopClient("id", "sec"), "rt-1"))
    }

    @Test
    fun inboxImportsAndDeletes() = runBlocking {
        val b = base ?: return@runBlocking println("DRIVE_MOCK not set — skipped")
        // The phone's side: drop a recording into the mailbox.
        val api = DriveApi(HttpClient(CIO), "$b/drive/v3", "$b/upload/drive/v3")
        val audio = ByteArray(300_000) { (it * 7 % 256).toByte() }
        api.upload("testtoken", RelayRecording("rec-7", "Genetika – úvod", "sk", 1_789_100_000_000, "phone", "m4a"),
            audio.size.toLong(), "audio/mp4", { off, len -> audio.copyOfRange(off.toInt(), off.toInt() + len) })

        val dir = Files.createTempDirectory("tb-inbox")
        val env = AppEnvironment(dir).also { it.ensureDirs() }
        val repo = LibraryRepository(env).also { it.loadAll() }
        var cfg = AppConfig(dataDir = dir.toString(), driveClientId = "id", driveClientSecret = "sec",
            driveRefreshToken = "rt-1", driveAccount = "test@example.com")
        val imported = mutableListOf<String>()
        val inbox = DriveInbox(CoroutineScope(SupervisorJob() + Dispatchers.IO), dir, repo, { cfg },
            { t -> cfg = t(cfg) }, { imported += it }, driveApiBase = "$b/drive/v3", tokenUrl = "$b/token")

        inbox.checkOnce()

        assertEquals(1, imported.size, "one lecture imported")
        val lecture = repo.get(imported[0])!!
        assertEquals("Genetika – úvod", lecture.title)
        assertEquals("sk", lecture.language)
        assertContentEquals(audio, Files.readAllBytes(repo.audioPath(lecture)), "audio intact")
        assertTrue(api.list("testtoken").isEmpty(), "removed from Drive after import")
        val st = inbox.state.value as DriveState.Connected
        assertEquals(1, st.pickedUp); assertEquals(null, st.lastError)

        inbox.checkOnce() // nothing new → no duplicate import
        assertEquals(1, imported.size)
        println("OK: imported '${lecture.title}' (${audio.size} bytes), Drive emptied")
    }
}
