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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

sealed interface SyncServerState {
    data object Stopped : SyncServerState
    data class Running(
        val host: String,
        val port: Int,
        /** Every usable address of this PC (the phone can be pointed at any of them). */
        val addresses: List<String> = emptyList(),
        /** False on networks that hand out public addresses (e.g. eduroam), which usually
         *  block devices from reaching each other and always block auto-discovery. */
        val privateNetwork: Boolean = true,
        val vpnActive: Boolean = false,
    ) : SyncServerState
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    fun start() {
        if (_state.value is SyncServerState.Running) return
        try {
            val port = preferredPort()
            val host = NetworkAddresses.primaryIpv4() ?: "127.0.0.1"
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
            _state.value = runningState(host, port)
            watchNetwork(port)
        } catch (e: Exception) {
            _state.value = SyncServerState.Failed(e.message ?: "sync server failed to start")
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
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

    private fun runningState(host: String, port: Int) = SyncServerState.Running(
        host = host,
        port = port,
        addresses = (listOf(host) + NetworkAddresses.candidates().map { it.first }).distinct()
            .filter { it != "127.0.0.1" },
        privateNetwork = NetworkAddresses.isPrivate(host),
        vpnActive = NetworkAddresses.vpnActive(),
    )

    /** A laptop moves between networks (lecture hall → home). The HTTP server listens on all
     *  interfaces, but mDNS is bound to one address, so re-advertise when the address changes. */
    private fun watchNetwork(port: Int) {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive) {
                delay(15_000)
                val current = (_state.value as? SyncServerState.Running) ?: continue
                val host = NetworkAddresses.primaryIpv4() ?: "127.0.0.1"
                val next = runningState(host, port)
                if (host != current.host) {
                    runCatching { jmdns?.unregisterAllServices(); jmdns?.close() }
                    jmdns = null
                    if (host != "127.0.0.1") registerMdns(host, port)
                }
                if (next != current) _state.value = next
            }
        }
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

        /** The fixed sync port if it's free, else any free port. */
        fun preferredPort(): Int = runCatching {
            ServerSocket().use { s ->
                s.reuseAddress = false
                s.bind(InetSocketAddress(SyncProtocol.DEFAULT_PORT))
                SyncProtocol.DEFAULT_PORT
            }
        }.getOrElse { freePort() }
    }
}
