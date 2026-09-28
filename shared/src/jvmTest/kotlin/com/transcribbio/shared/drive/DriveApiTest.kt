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
}
