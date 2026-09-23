package com.transcribbio.phone.sync

import android.content.Context
import android.os.Build
import com.transcribbio.phone.core.Prefs
import com.transcribbio.phone.data.RecordingStore
import com.transcribbio.shared.model.DeviceKind
import com.transcribbio.shared.model.Lecture
import com.transcribbio.shared.sync.LectureSummaryDto
import com.transcribbio.shared.sync.PairRequestDto
import com.transcribbio.shared.sync.SyncProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncUiState {
    data object Idle : SyncUiState
    data object Discovering : SyncUiState
    data class Uploading(val current: Int, val total: Int, val fraction: Float) : SyncUiState
    data class Error(val message: String) : SyncUiState
    data class Done(val desktopName: String, val uploaded: Int, val atMillis: Long) : SyncUiState
}

class SyncManager(
    private val context: Context,
    private val prefs: Prefs,
    private val store: RecordingStore,
    private val scope: CoroutineScope,
) {
    private val discovery = Discovery(context)
    private val client = SyncClient()
    private val mutex = Mutex()

    private val _state = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private val _endpoint = MutableStateFlow<DesktopEndpoint?>(null)
    val endpoint: StateFlow<DesktopEndpoint?> = _endpoint.asStateFlow()

    fun requestSync(context: Context): Job = scope.launch { syncNow() }

    suspend fun syncNow(): Boolean = mutex.withLock {
        _state.value = SyncUiState.Discovering
        val ep = resolveEndpoint() ?: run {
            _state.value = SyncUiState.Error(
                "Couldn't reach your desktop. Make sure Transcribbio is open on the PC. University and " +
                    "public Wi-Fi (like eduroam) often block devices from seeing each other — if you're on " +
                    "the same network, enter the PC's address in Settings ▸ Desktop connection."
            )
            return false
        }

        // Paired with a different PC before? Its token won't work here — pair again.
        val known = prefs.desktopDeviceId.value
        if (known.isNotBlank() && ep.deviceId.isNotBlank() && ep.deviceId != known) prefs.clearPairing()

        var token = prefs.token.value
        if (token.isBlank()) {
            val resp = runCatching {
                client.pair(ep.host, ep.port, PairRequestDto(prefs.deviceId, deviceName(), DeviceKind.PHONE))
            }.getOrNull()
            if (resp == null) {
                _state.value = SyncUiState.Error("Could not pair with ${ep.name}")
                return false
            }
            prefs.setPairing(resp.token, resp.desktopName, resp.desktopDeviceId)
            token = resp.token
        }

        val pending = store.pendingUploads()
        var uploaded = 0
        pending.forEachIndexed { i, rec ->
            _state.value = SyncUiState.Uploading(i + 1, pending.size, 0f)
            val file = store.audioFile(rec)
            if (!file.exists()) {
                store.setError(rec.id, "recording file missing")
                return@forEachIndexed
            }
            runCatching {
                client.uploadRecording(
                    ep.host, ep.port, token, rec.title, rec.language, rec.createdAtMillis, file,
                ) { f -> _state.value = SyncUiState.Uploading(i + 1, pending.size, f) }
            }.onSuccess {
                store.markUploaded(rec.id, it.lectureId); uploaded++
            }.onFailure {
                store.setError(rec.id, it.message ?: "upload failed")
            }
        }
        _state.value = SyncUiState.Done(ep.name, uploaded, System.currentTimeMillis())
        return true
    }

    suspend fun fetchLectures(): List<LectureSummaryDto> {
        val ep = _endpoint.value ?: resolveEndpoint() ?: return emptyList()
        val token = prefs.token.value
        if (token.isBlank()) return emptyList()
        return runCatching { client.listLectures(ep.host, ep.port, token).lectures }.getOrElse { emptyList() }
    }

    suspend fun fetchLecture(id: String): Lecture? {
        val ep = _endpoint.value ?: resolveEndpoint() ?: return null
        val token = prefs.token.value
        if (token.isBlank()) return null
        return runCatching { client.getLecture(ep.host, ep.port, token, id) }.getOrNull()
    }

    /** Where is the desktop? 1) the address the user typed (works where discovery is blocked),
     *  2) mDNS auto-discovery, 3) the last address that worked. */
    private suspend fun resolveEndpoint(): DesktopEndpoint? {
        parseAddress(prefs.manualAddress.value)?.let { (h, p) -> probe(h, p)?.let { return found(it) } }
        discovery.discover()?.let { return found(it) }
        prefs.lastEndpoint()?.let { (h, p) -> probe(h, p)?.let { return found(it) } }
        return null
    }

    private fun found(ep: DesktopEndpoint): DesktopEndpoint {
        prefs.setLastEndpoint(ep.host, ep.port)
        _endpoint.value = ep
        return ep
    }

    private suspend fun probe(host: String, port: Int): DesktopEndpoint? =
        client.ping(host, port)?.let { DesktopEndpoint(host, port, it.desktopName, it.desktopDeviceId) }

    /** Check an address typed in Settings; returns the desktop it reached, or null. */
    suspend fun testAddress(raw: String): DesktopEndpoint? =
        parseAddress(raw)?.let { (h, p) -> probe(h, p) }?.also { found(it) }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Phone" }

    companion object {
        /** Accepts "192.168.1.20:47815", "192.168.1.20" (default port) or "http://…/" forms. */
        fun parseAddress(raw: String): Pair<String, Int>? {
            val s = raw.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
            if (s.isBlank()) return null
            val host = s.substringBefore(':').trim()
            val port = s.substringAfter(':', "").trim().toIntOrNull() ?: SyncProtocol.DEFAULT_PORT
            return if (host.isNotBlank() && port in 1..65535) host to port else null
        }
    }
}
