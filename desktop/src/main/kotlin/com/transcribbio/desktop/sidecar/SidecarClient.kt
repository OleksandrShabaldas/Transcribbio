package com.transcribbio.desktop.sidecar

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
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

    suspend fun correct(req: CorrectRequestDto): CorrectResponseDto =
        http.post(url("/correct")) {
            auth(); contentType(ContentType.Application.Json); setBody(req)
        }.body()

    suspend fun material(req: MaterialRequestDto): MaterialResponseDto =
        http.post(url("/materials")) {
            auth(); contentType(ContentType.Application.Json); setBody(req)
        }.body()

    fun close() = http.close()
}
