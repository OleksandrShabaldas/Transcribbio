package com.transcribbio.phone.sync

import com.transcribbio.shared.model.Lecture
import com.transcribbio.shared.sync.LectureListDto
import com.transcribbio.shared.sync.PairRequestDto
import com.transcribbio.shared.sync.PairResponseDto
import com.transcribbio.shared.sync.PingDto
import com.transcribbio.shared.sync.SyncProtocol
import com.transcribbio.shared.sync.UploadResultDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.cio.readChannel
import kotlinx.serialization.json.Json
import java.io.File

class SyncClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 20 * 60 * 1000
            connectTimeoutMillis = 8000
            socketTimeoutMillis = 20 * 60 * 1000
        }
    }

    private fun base(host: String, port: Int) = "http://$host:$port"

    /** Is a Transcribbio desktop answering at host:port? Short timeout so a stale or wrong
     *  address fails fast instead of stalling a sync for the default 8 s. */
    suspend fun ping(host: String, port: Int, timeoutMs: Long = 3000): PingDto? = runCatching {
        http.get("${base(host, port)}/api/ping") {
            timeout {
                connectTimeoutMillis = timeoutMs
                requestTimeoutMillis = timeoutMs + 2000
                socketTimeoutMillis = timeoutMs + 2000
            }
        }.body<PingDto>().takeIf { it.app == "transcribbio" }
    }.getOrNull()

    suspend fun pair(host: String, port: Int, req: PairRequestDto): PairResponseDto =
        http.post("${base(host, port)}/api/pair") {
            contentType(ContentType.Application.Json); setBody(req)
        }.body()

    suspend fun uploadRecording(
        host: String,
        port: Int,
        token: String,
        title: String,
        language: String,
        recordedAtMillis: Long,
        file: File,
        onProgress: (Float) -> Unit = {},
    ): UploadResultDto =
        http.post("${base(host, port)}/api/upload") {
            header(SyncProtocol.TOKEN_HEADER, token)
            header(SyncProtocol.TITLE_HEADER, title)
            header(SyncProtocol.LANGUAGE_HEADER, language)
            header(SyncProtocol.SOURCE_HEADER, "phone")
            header(SyncProtocol.RECORDED_AT_HEADER, recordedAtMillis.toString())
            header(SyncProtocol.FILENAME_HEADER, file.name)
            contentType(ContentType.Application.OctetStream)
            setBody(file.readChannel())
            onUpload { sent, total ->
                if (total != null && total > 0) onProgress(sent.toFloat() / total.toFloat())
            }
        }.body()

    suspend fun listLectures(host: String, port: Int, token: String): LectureListDto =
        http.get("${base(host, port)}/api/lectures") {
            header(SyncProtocol.TOKEN_HEADER, token)
        }.body()

    suspend fun getLecture(host: String, port: Int, token: String, id: String): Lecture =
        http.get("${base(host, port)}/api/lectures/$id") {
            header(SyncProtocol.TOKEN_HEADER, token)
        }.body()

    fun close() = http.close()
}
