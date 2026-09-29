package com.transcribbio.shared.drive

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
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

/** "Rename the lecture that came from recording [recId]", left by the phone for the desktop. */
data class RenameNote(val fileId: String, val recId: String, val title: String)

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
        const val RENAME_KIND = "transcribbio-rename"
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
    suspend fun list(token: String): List<DriveItem> = query(token, KIND).mapNotNull { parseItem(it) }

    /** All mailbox entries of one kind (recordings or rename notes), oldest first. */
    private suspend fun query(token: String, kind: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var pageToken: String? = null
        do {
            val r = http.get("$apiBase/files") {
                auth(token)
                parameter("spaces", "appDataFolder")
                parameter("q", "appProperties has { key='kind' and value='$kind' } and trashed = false")
                parameter("fields", "nextPageToken,files(id,size,description,appProperties)")
                parameter("orderBy", "createdTime")
                parameter("pageSize", "100")
                pageToken?.let { parameter("pageToken", it) }
            }
            val root = json.parseToJsonElement(r.ok("list")).jsonObject
            root["files"]?.jsonArray?.forEach { out += it.jsonObject }
            pageToken = root["nextPageToken"]?.jsonPrimitive?.content
        } while (pageToken != null)
        return out
    }

    /** Drive file id of an already-uploaded recording (so a retry never uploads twice). */
    suspend fun findByRecId(token: String, recId: String): String? {
        val r = http.get("$apiBase/files") {
            auth(token)
            parameter("spaces", "appDataFolder")
            // Filter on kind too: rename notes carry the same recId as the recording.
            parameter("q", "appProperties has { key='kind' and value='$KIND' } and " +
                "appProperties has { key='recId' and value='${recId.replace("'", "")}' } and trashed = false")
            parameter("fields", "files(id)")
        }
        val files = json.parseToJsonElement(r.ok("find")).jsonObject["files"]?.jsonArray
        return files?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
    }

    /**
     * Resumable, chunked upload into the hidden app folder. Survives dropped connections *and*
     * the app being killed: pass the [session] saved from an earlier attempt (it is reported via
     * [onSession], and `null` once finished) and it asks Drive how much already arrived and
     * continues from exactly that byte. [read] returns [len] bytes starting at [offset].
     * Returns the Drive file id.
     */
    suspend fun upload(
        token: String,
        meta: RelayRecording,
        size: Long,
        mimeType: String,
        read: (offset: Long, len: Int) -> ByteArray,
        onProgress: (Float) -> Unit = {},
        session: String? = null,
        onSession: (String?) -> Unit = {},
    ): String {
        var sessionUri: String? = session
        var offset = 0L
        if (sessionUri != null) {
            // Resume an earlier attempt; an expired/unknown session just means starting over.
            val resumed = runCatching { committed(sessionUri, size) }
            if (resumed.isSuccess) {
                val at = resumed.getOrNull() ?: return idOf(sessionUri, size).also { onSession(null) }
                offset = at
            } else {
                sessionUri = null
            }
        }
        if (sessionUri == null) {
            sessionUri = startSession(token, meta, size, mimeType)
            onSession(sessionUri)
        }
        if (size > 0 && offset > 0) onProgress((offset.toFloat() / size).coerceIn(0f, 1f))

        var failures = 0
        var restarted = false
        while (true) {
            val s = sessionUri!!
            val len = minOf(CHUNK.toLong(), size - offset).toInt()
            // Read outside the network try-block: a local file error is not a network hiccup.
            val bytes = if (len > 0) read(offset, len) else ByteArray(0)
            val resp: HttpResponse = try {
                http.put(s) {
                    header(HttpHeaders.ContentRange,
                        if (size == 0L) "bytes */0" else "bytes $offset-${offset + len - 1}/$size")
                    setBody(ByteArrayContent(bytes, ContentType.parse(mimeType)))
                }
            } catch (e: Exception) {
                if (++failures > 8) throw e
                delay(minOf(30_000L, 1_000L shl failures))
                offset = committed(s, size) ?: return idOf(s, size).also { onSession(null) }
                continue
            }
            when (val code = resp.status.value) {
                200, 201 -> {
                    onSession(null)
                    return idFrom(resp.bodyAsText())
                }
                308 -> {
                    failures = 0
                    offset = rangeEnd(resp.headers[HttpHeaders.Range])?.plus(1) ?: 0L
                    if (size > 0) onProgress((offset.toFloat() / size).coerceIn(0f, 1f))
                }
                404, 410 -> {
                    // Sessions last about a week; if this one is gone, start a fresh one (once).
                    if (restarted) throw DriveException("Upload session expired", code)
                    restarted = true
                    sessionUri = startSession(token, meta, size, mimeType)
                    onSession(sessionUri)
                    offset = 0L
                }
                else -> {
                    if (code != 429 && code < 500) throw DriveException("Upload failed ($code): ${resp.bodyAsText().take(300)}", code)
                    if (++failures > 8) throw DriveException("Upload kept failing ($code)", code)
                    delay(minOf(30_000L, 1_000L shl failures))
                    offset = committed(s, size) ?: return idOf(s, size).also { onSession(null) }
                }
            }
        }
    }

    private suspend fun startSession(token: String, meta: RelayRecording, size: Long, mimeType: String): String {
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
        return start.headers[HttpHeaders.Location] ?: throw DriveException("Drive didn't return an upload session")
    }

    /** Retitle a recording that is still waiting in the mailbox. False if it's gone already
     *  (the desktop picked it up) — then use [postRename]. */
    suspend fun setTitle(token: String, fileId: String, title: String): Boolean {
        val r = http.patch("$apiBase/files/$fileId") {
            auth(token)
            parameter("fields", "id")
            setBody(TextContent(buildJsonObject { put("description", title) }.toString(), ContentType.Application.Json))
        }
        if (r.status.value == 404) return false
        r.ok("rename")
        return true
    }

    /** Leave a rename note for a recording the desktop already imported; it applies and deletes it. */
    suspend fun postRename(token: String, recId: String, title: String) {
        val metadata = buildJsonObject {
            put("name", "rename-$recId.json")
            putJsonArray("parents") { add("appDataFolder") }
            put("description", title)
            putJsonObject("appProperties") {
                put("kind", RENAME_KIND)
                put("recId", recId)
            }
        }
        http.post("$apiBase/files") {
            auth(token)
            parameter("fields", "id")
            setBody(TextContent(metadata.toString(), ContentType.Application.Json))
        }.ok("rename note")
    }

    /** Rename notes waiting in the mailbox, oldest first. */
    suspend fun listRenames(token: String): List<RenameNote> =
        query(token, RENAME_KIND).mapNotNull { o ->
            val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val recId = o["appProperties"]?.jsonObject?.get("recId")?.jsonPrimitive?.content ?: return@mapNotNull null
            RenameNote(id, recId, o["description"]?.jsonPrimitive?.content.orEmpty())
        }.filter { it.title.isNotBlank() }

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
