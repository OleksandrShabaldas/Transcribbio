package com.transcribbio.desktop.sync

import com.transcribbio.desktop.core.AppConfig
import com.transcribbio.desktop.data.LibraryRepository
import com.transcribbio.shared.model.DeviceKind
import com.transcribbio.shared.sync.LectureListDto
import com.transcribbio.shared.sync.LectureSummaryDto
import com.transcribbio.shared.sync.PairRequestDto
import com.transcribbio.shared.sync.PairResponseDto
import com.transcribbio.shared.sync.PingDto
import com.transcribbio.shared.sync.SyncProtocol
import com.transcribbio.shared.sync.UploadResultDto
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

sealed interface SyncServerState {
    data object Stopped : SyncServerState
    data class Running(val host: String, val port: Int) : SyncServerState
    data class Failed(val reason: String) : SyncServerState
}

class SyncServer(
    private val repo: LibraryRepository,
    private val configProvider: () -> AppConfig,
    private val appVersion: String,
    private val onUpload: (String) -> Unit,
) {
    private val _state = MutableStateFlow<SyncServerState>(SyncServerState.Stopped)
    val state: StateFlow<SyncServerState> = _state.asStateFlow()

    private var server: EmbeddedServer<*, *>? = null
    private var jmdns: JmDNS? = null

    fun start() {
        if (_state.value is SyncServerState.Running) return
        try {
            val port = freePort()
            val host = localIpv4() ?: "127.0.0.1"
            server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                install(ContentNegotiation) { json() }
                routing {
                    get("/api/ping") {
                        val cfg = configProvider()
                        call.respond(PingDto(desktopName = cfg.desktopName, version = appVersion,
                            desktopDeviceId = cfg.desktopDeviceId))
                    }
                    post("/api/pair") {
                        val cfg = configProvider()
                        runCatching { call.receive<PairRequestDto>() }
                        call.respond(PairResponseDto(cfg.syncToken, cfg.desktopName, cfg.desktopDeviceId))
                    }
                    get("/api/lectures") {
                        if (!call.authed()) return@get
                        val summaries = repo.lectures.value.map {
                            LectureSummaryDto(
                                id = it.id, title = it.title, status = it.status,
                                createdAtMillis = it.createdAtMillis, durationS = it.durationS,
                                language = it.language, hasTranscript = it.transcript != null,
                                hasMaterials = it.materials.isNotEmpty(),
                            )
                        }
                        call.respond(LectureListDto(summaries))
                    }
                    get("/api/lectures/{id}") {
                        if (!call.authed()) return@get
                        val id = call.parameters["id"]
                        val lecture = id?.let { repo.get(it) }
                        if (lecture == null) call.respond(HttpStatusCode.NotFound, "Unknown lecture")
                        else call.respond(lecture)
                    }
                    post("/api/upload") {
                        if (!call.authed()) return@post
                        val title = call.request.header(SyncProtocol.TITLE_HEADER) ?: "Lecture"
                        val language = call.request.header(SyncProtocol.LANGUAGE_HEADER) ?: configProvider().language
                        val source = call.request.header(SyncProtocol.SOURCE_HEADER) ?: "phone"
                        val filename = call.request.header(SyncProtocol.FILENAME_HEADER) ?: "audio.m4a"
                        val recordedAt = call.request.header(SyncProtocol.RECORDED_AT_HEADER)?.toLongOrNull() ?: 0L
                        val lecture = call.receiveStream().use { input ->
                            repo.saveUpload(input, filename, title, language, source, recordedAt)
                        }
                        onUpload(lecture.id)
                        call.respond(UploadResultDto(lecture.id, "received"))
                    }
                }
            }.also { it.start(wait = false) }

            registerMdns(host, port)
            _state.value = SyncServerState.Running(host, port)
        } catch (e: Exception) {
            _state.value = SyncServerState.Failed(e.message ?: "sync server failed to start")
        }
    }

    fun stop() {
        runCatching { jmdns?.unregisterAllServices(); jmdns?.close() }
        jmdns = null
        runCatching { server?.stop(500, 1000) }
        server = null
        _state.value = SyncServerState.Stopped
    }

    private suspend fun ApplicationCall.authed(): Boolean {
        val token = configProvider().syncToken
        if (token.isBlank() || request.header(SyncProtocol.TOKEN_HEADER) != token) {
            respond(HttpStatusCode.Unauthorized, "Pair this device first")
            return false
        }
        return true
    }

    private fun registerMdns(host: String, port: Int) {
        runCatching {
            val addr = InetAddress.getByName(host)
            val md = JmDNS.create(addr)
            val cfg = configProvider()
            val props = hashMapOf(
                SyncProtocol.TXT_NAME to cfg.desktopName,
                SyncProtocol.TXT_DEVICE_ID to cfg.desktopDeviceId,
            )
            val info = ServiceInfo.create(SyncProtocol.SERVICE_TYPE_JMDNS, "Transcribbio", port, 0, 0, props)
            md.registerService(info)
            jmdns = md
        }
    }

    companion object {
        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        fun localIpv4(): String? {
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress
            }.getOrNull()
        }
    }
}
