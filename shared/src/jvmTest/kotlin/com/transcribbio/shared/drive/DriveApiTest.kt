package com.transcribbio.shared.drive

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip against a fake Drive server (set DRIVE_MOCK=http://127.0.0.1:PORT; skipped
 * otherwise). The fake fails the 2nd upload chunk after committing half of it, so this also
 * proves the client resumes from exactly the committed byte.
 */
class DriveApiTest {
    @Test
    fun uploadListDownloadDelete() = runBlocking {
        val base = System.getenv("DRIVE_MOCK") ?: return@runBlocking println("DRIVE_MOCK not set — skipped")
        val api = DriveApi(HttpClient(CIO), "$base/drive/v3", "$base/upload/drive/v3")
        val token = "testtoken"
        // 2.5 chunks, odd length, deterministic pattern
        val data = ByteArray(DriveApi.CHUNK * 2 + DriveApi.CHUNK / 2 + 123) { (it % 251).toByte() }
        val meta = RelayRecording("rec-42", "Bunkové dýchanie – 3. prednáška", "sk", 1_789_000_000_000, "phone", "m4a")

        assertNull(api.findByRecId(token, "rec-42"))
        val progress = mutableListOf<Float>()
        val id = api.upload(token, meta, data.size.toLong(), "audio/mp4",
            read = { off, len -> data.copyOfRange(off.toInt(), off.toInt() + len) },
            onProgress = { progress += it })

        assertEquals(id, api.findByRecId(token, "rec-42"), "dedupe lookup finds the upload")
        val items = api.list(token)
        assertEquals(1, items.size)
        assertEquals(meta, items[0].meta, "metadata survives the round trip")
        assertEquals(data.size.toLong(), items[0].size)

        val out = ByteArrayOutputStream()
        api.download(token, id) { buf, n -> out.write(buf, 0, n) }
        assertContentEquals(data, out.toByteArray(), "downloaded bytes identical (resume was exact)")

        assertEquals("test@example.com", api.accountEmail(token))
        api.delete(token, id)
        assertTrue(api.list(token).isEmpty(), "deleted from the mailbox")
        println("OK: ${data.size} bytes, progress=$progress")
    }

    /** The app is killed mid-upload; the next run resumes the saved session instead of restarting. */
    @Test
    fun resumesSavedSessionAfterAppKilled() = runBlocking {
        val base = System.getenv("DRIVE_MOCK") ?: return@runBlocking println("DRIVE_MOCK not set — skipped")
        val api = DriveApi(HttpClient(CIO), "$base/drive/v3", "$base/upload/drive/v3")
        val token = "testtoken"
        val data = ByteArray(DriveApi.CHUNK * 2 + 777) { (it * 13 % 256).toByte() }
        val meta = RelayRecording("rec-kill", "Killed mid-upload", "sk", 1L, "phone", "m4a")
        var saved: String? = null

        // First run: the process "dies" while reading the 2nd chunk (after chunk 1 was committed).
        val first = runCatching {
            api.upload(token, meta, data.size.toLong(), "audio/mp4",
                read = { off, len -> if (off > 0) throw IllegalStateException("process killed")
                    else data.copyOfRange(off.toInt(), off.toInt() + len) },
                onSession = { saved = it })
        }
        assertTrue(first.isFailure && saved != null, "first run dies but leaves a saved session")

        // Second run: resume from the saved session.
        val progress = mutableListOf<Float>()
        var finalSession: String? = "unset"
        val id = api.upload(token, meta, data.size.toLong(), "audio/mp4",
            read = { off, len -> data.copyOfRange(off.toInt(), off.toInt() + len) },
            onProgress = { progress += it }, session = saved, onSession = { finalSession = it })
        assertTrue(progress.first() >= 0.39f, "resumed after chunk 1 instead of from zero (first=${progress.first()})")
        assertEquals(null, finalSession, "session cleared once complete")
        val out = ByteArrayOutputStream()
        api.download(token, id) { b, n -> out.write(b, 0, n) }
        assertContentEquals(data, out.toByteArray(), "resumed upload is byte-identical")
        api.delete(token, id)
        println("OK resume: progress=$progress")
    }

    @Test
    fun renamesInMailboxAndViaNotes() = runBlocking {
        val base = System.getenv("DRIVE_MOCK") ?: return@runBlocking println("DRIVE_MOCK not set — skipped")
        val api = DriveApi(HttpClient(CIO), "$base/drive/v3", "$base/upload/drive/v3")
        val token = "testtoken"
        val bytes = ByteArray(1000) { it.toByte() }
        val id = api.upload(token, RelayRecording("rec-r", "Old name", "sk", 1L, "phone", "m4a"),
            bytes.size.toLong(), "audio/mp4", { o, l -> bytes.copyOfRange(o.toInt(), o.toInt() + l) })

        assertTrue(api.setTitle(token, id, "Biochémia – enzýmy"))
        assertEquals("Biochémia – enzýmy", api.list(token).single { it.fileId == id }.meta.title)

        // A rename note for the same recording must not be mistaken for the recording.
        api.postRename(token, "rec-r", "Final name")
        assertEquals(id, api.findByRecId(token, "rec-r"))
        val notes = api.listRenames(token)
        assertEquals(listOf("rec-r" to "Final name"), notes.map { it.recId to it.title })
        assertEquals(1, api.list(token).size, "notes don't show up as recordings")

        api.delete(token, id)
        notes.forEach { api.delete(token, it.fileId) }
        assertEquals(false, api.setTitle(token, id, "gone"), "retitle reports a picked-up recording")
        println("OK renames")
    }
}
