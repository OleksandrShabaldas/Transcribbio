package com.transcribbio.desktop.sidecar

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.call.body
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Loopback HTTP client for the Python ML sidecar. */
class SidecarClient(
    private val host: String,
    private val port: Int,
    private val token: String?,
) {
    private val jsonCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val http = HttpClient(CIO) {
        expectSuccess = true
        install(ContentNegotiation) { json(jsonCodec) }
        install(HttpTimeout) {
            // Correction/materials over a large transcript can take minutes.
            requestTimeoutMillis = 30 * 60 * 1000
            connectTimeoutMillis = 10 * 1000
            socketTimeoutMillis = 30 * 60 * 1000
        }
    }

    private fun url(path: String) = "http://$host:$port$path"

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        token?.let { header("X-Transcribbio-Token", it) }
    }

    suspend fun health(): HealthDto =
        http.get(url("/health")) { auth() }.body()

    suspend fun diag(): String =
        http.get(url("/diag")) { auth() }.bodyAsText()

    suspend fun submitJob(req: ProcessRequestDto): JobRefDto =
        http.post(url("/jobs")) {
            auth(); contentType(ContentType.Application.Json); setBody(req)
        }.body()

    suspend fun jobStatus(jobId: String): JobStatusDto =
        http.get(url("/jobs/$jobId")) { auth() }.body()

    suspend fun transcribe(req: TranscribeRequestDto): TranscriptionResponseDto =
        http.post(url("/transcribe")) {
            auth(); contentType(ContentType.Application.Json); setBody(req)
        }.body()

    suspend fun correct(req: CorrectRequestDto): CorrectResponseDto = wrapErrors {
        http.post(url("/correct")) {
            auth(); contentType(ContentType.Application.Json); setBody(req)
        }.body()
    }

    suspend fun material(req: MaterialRequestDto): MaterialResponseDto = wrapErrors {
        http.post(url("/materials")) {
            auth(); contentType(ContentType.Application.Json); setBody(req)
        }.body()
    }

    suspend fun llmModels(): LlmModelsDto = wrapErrors {
        http.get(url("/llm/models")) { auth() }.body()
    }

    suspend fun llmTest(models: List<String>): LlmTestResponseDto = wrapErrors {
        http.post(url("/llm/test")) {
            auth(); contentType(ContentType.Application.Json); setBody(LlmTestRequestDto(models))
        }.body()
    }

    /** Turn an HTTP error into a clean exception carrying the sidecar's own `detail`
     *  message (e.g. "AI provider unavailable: …"), so the UI can explain the real cause
     *  instead of a bare status code. */
    private suspend fun <T> wrapErrors(block: suspend () -> T): T = try {
        block()
    } catch (e: ResponseException) {
        val detail = runCatching {
            val body = e.response.bodyAsText()
            (jsonCodec.parseToJsonElement(body) as? JsonObject)
                ?.get("detail")?.jsonPrimitive?.content ?: body
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: (e.message ?: "request failed")
        throw RuntimeException(detail)
    }

    fun close() = http.close()
}
