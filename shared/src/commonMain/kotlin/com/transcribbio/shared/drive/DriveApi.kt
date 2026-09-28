package com.transcribbio.shared.drive

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What the phone knows about a recording, carried with it through Google Drive. */
@Serializable
data class RelayRecording(
    val recId: String,
    val title: String,
    val language: String,
    val recordedAtMillis: Long,
    val source: String,
    val fileExt: String,
)

/** A recording waiting in the Drive mailbox. */
data class DriveItem(val fileId: String, val size: Long, val meta: RelayRecording)

class DriveException(message: String, val status: Int = 0) : Exception(message)

/**
 * Minimal Google Drive v3 client for Transcribbio's cloud relay: the phone drops recordings
 * into the app's hidden `appDataFolder`, the desktop downloads them and deletes them.
 * The `drive.appdata` scope only reaches that private folder, never the user's own files.
 * Access tokens are obtained per platform (Android AuthorizationClient / desktop OAuth)
 * and passed into each call.
 */
class DriveApi(
    private val http: HttpClient,
    private val apiBase: String = "https://www.googleapis.com/drive/v3",
    private val uploadBase: String = "https://www.googleapis.com/upload/drive/v3",
) {
    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val KIND = "transcribbio-recording"
        /** Upload chunk size; Drive requires multiples of 256 KiB. */
        const val CHUNK = 8 * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** Email of the connected Google account (for "Connected as …"), or null if unavailable. */
    suspend fun accountEmail(token: String): String? = runCatching {
        val r = http.get("$apiBase/about") { auth(token); parameter("fields", "user(emailAddress)") }
        if (!r.status.isSuccess()) return null
        json.parseToJsonElement(r.bodyAsText()).jsonObject["user"]?.jsonObject
            ?.get("emailAddress")?.jsonPrimitive?.content
    }.getOrNull()

    /** Recordings waiting in the mailbox, oldest first. */
    suspend fun list(token: String): List<DriveItem> {
        val items = mutableListOf<DriveItem>()
        var pageToken: String? = null
        do {
            val r = http.get("$apiBase/files") {
                auth(token)
                parameter("spaces", "appDataFolder")
                parameter("q", "appProperties has { key='kind' and value='$KIND' } and trashed = false")
                parameter("fields", "nextPageToken,files(id,size,description,appProperties)")
                parameter("orderBy", "createdTime")
                parameter("pageSize", "100")
                pageToken?.let { parameter("pageToken", it) }
            }
            val body = r.ok("list")
            val root = json.parseToJsonElement(body).jsonObject
            root["files"]?.jsonArray?.forEach { el -> parseItem(el.jsonObject)?.let(items::add) }
            pageToken = root["nextPageToken"]?.jsonPrimitive?.content
        } while (pageToken != null)
        return items
    }

    /** Drive file id of an already-uploaded recording (so a retry never uploads twice). */
    suspend fun findByRecId(token: String, recId: String): String? {
        val r = http.get("$apiBase/files") {
            auth(token)
            parameter("spaces", "appDataFolder")
            parameter("q", "appProperties has { key='recId' and value='${recId.replace("'", "")}' } and trashed = false")
            parameter("fields", "files(id)")
        }
        val files = json.parseToJsonElement(r.ok("find")).jsonObject["files"]?.jsonArray
        return files?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
    }

    /**
     * Resumable, chunked upload into the hidden app folder. Survives dropped connections:
     * after a failure it asks Drive how much arrived and continues from there.
     * [read] returns [len] bytes starting at [offset]. Returns the Drive file id.
     */
    suspend fun upload(
        token: String,
        meta: RelayRecording,
        size: Long,
        mimeType: String,
        read: (offset: Long, len: Int) -> ByteArray,
        onProgress: (Float) -> Unit = {},
    ): String {
        val metadata = buildJsonObject {
            put("name", "${meta.recId}.${meta.fileExt}")
            putJsonArray("parents") { add("appDataFolder") }
            put("mimeType", mimeType)
            put("description", meta.title)
            putJsonObject("appProperties") {
                put("kind", KIND)
                put("recId", meta.recId)
                put("lang", meta.language)
                put("at", meta.recordedAtMillis.toString())
                put("src", meta.source.take(40))
                put("ext", meta.fileExt)
            }
        }
        val start = http.post("$uploadBase/files") {
            auth(token)
            parameter("uploadType", "resumable")
            parameter("fields", "id")
            header("X-Upload-Content-Type", mimeType)
            header("X-Upload-Content-Length", size.toString())
            setBody(TextContent(metadata.toString(), ContentType.Application.Json))
        }
        start.ok("start upload")
        val session = start.headers[HttpHeaders.Location] ?: throw DriveException("Drive didn't return an upload session")

        var offset = 0L
        var failures = 0
        while (true) {
            val len = minOf(CHUNK.toLong(), size - offset).toInt()
            val resp: HttpResponse = try {
                http.put(session) {
                    header(HttpHeaders.ContentRange,
                        if (size == 0L) "bytes */0" else "bytes $offset-${offset + len - 1}/$size")
                    setBody(ByteArrayContent(if (len > 0) read(offset, len) else ByteArray(0), ContentType.parse(mimeType)))
                }
            } catch (e: Exception) {
                if (++failures > 8) throw e
                delay(minOf(30_000L, 1_000L shl failures))
                offset = committed(session, size) ?: return idOf(session, size)
                continue
            }
            when (val code = resp.status.value) {
                200, 201 -> return idFrom(resp.bodyAsText())
                308 -> {
                    failures = 0
                    offset = rangeEnd(resp.headers[HttpHeaders.Range])?.plus(1) ?: 0L
                    if (size > 0) onProgress((offset.toFloat() / size).coerceIn(0f, 1f))
                }
                404, 410 -> throw DriveException("Upload session expired", code)
                else -> {
                    if (code != 429 && code < 500) throw DriveException("Upload failed ($code): ${resp.bodyAsText().take(300)}", code)
                    if (++failures > 8) throw DriveException("Upload kept failing ($code)", code)
                    delay(minOf(30_000L, 1_000L shl failures))
                    offset = committed(session, size) ?: return idOf(session, size)
                }
            }
        }
    }

    /** Stream a file's bytes to [sink] (e.g. a FileOutputStream). */
    suspend fun download(token: String, fileId: String, sink: (ByteArray, Int) -> Unit) {
        http.prepareGet("$apiBase/files/$fileId") { auth(token); parameter("alt", "media") }.execute { r ->
            if (!r.status.isSuccess()) throw DriveException("Download failed (${r.status.value})", r.status.value)
            val ch = r.bodyAsChannel()
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ch.readAvailable(buf, 0, buf.size)
                if (n == -1) break
                if (n > 0) sink(buf, n)
            }
        }
    }

    suspend fun delete(token: String, fileId: String) {
        val r = http.delete("$apiBase/files/$fileId") { auth(token) }
        if (!r.status.isSuccess() && r.status.value != 404) throw DriveException("Delete failed (${r.status.value})", r.status.value)
    }

    // ── helpers ──────────────────────────────────────────────────────────────────
    private fun io.ktor.client.request.HttpRequestBuilder.auth(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
        expectSuccess = false
    }

    private suspend fun HttpResponse.ok(what: String): String {
        val body = bodyAsText()
        if (!status.isSuccess()) {
            val msg = runCatching {
                json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
            }.getOrNull() ?: body.take(200)
            throw DriveException("Drive $what failed (${status.value}): $msg", status.value)
        }
        return body
    }

    /** How many bytes Drive has committed for a session; null means the upload already completed. */
    private suspend fun committed(session: String, size: Long): Long? {
        val r = http.put(session) {
            header(HttpHeaders.ContentRange, "bytes */$size")
            setBody(ByteArrayContent(ByteArray(0)))
        }
        return when (r.status.value) {
            200, 201 -> null
            308 -> rangeEnd(r.headers[HttpHeaders.Range])?.plus(1) ?: 0L
            else -> throw DriveException("Upload session lost (${r.status.value})", r.status.value)
        }
    }

    private suspend fun idOf(session: String, size: Long): String {
        val r = http.put(session) {
            header(HttpHeaders.ContentRange, "bytes */$size")
            setBody(ByteArrayContent(ByteArray(0)))
        }
        return idFrom(r.bodyAsText())
    }

    private fun idFrom(body: String): String =
        json.parseToJsonElement(body).jsonObject["id"]?.jsonPrimitive?.content
            ?: throw DriveException("Upload finished but Drive returned no file id")

    /** "bytes=0-8388607" → 8388607 */
    private fun rangeEnd(range: String?): Long? = range?.substringAfter('-', "")?.toLongOrNull()

    private fun parseItem(o: JsonObject): DriveItem? {
        val id = o["id"]?.jsonPrimitive?.content ?: return null
        val p = o["appProperties"]?.jsonObject ?: return null
        fun prop(k: String) = p[k]?.jsonPrimitive?.content
        return DriveItem(
            fileId = id,
            size = o["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            meta = RelayRecording(
                recId = prop("recId") ?: id,
                title = o["description"]?.jsonPrimitive?.content?.ifBlank { null } ?: "Lecture",
                language = prop("lang") ?: "sk",
                recordedAtMillis = prop("at")?.toLongOrNull() ?: 0L,
                source = prop("src") ?: "phone",
                fileExt = prop("ext") ?: "m4a",
            ),
        )
    }
}
