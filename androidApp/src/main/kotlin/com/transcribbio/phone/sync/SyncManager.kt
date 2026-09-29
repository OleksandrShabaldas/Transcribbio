package com.transcribbio.phone.sync

import android.content.Context
import android.os.Build
import com.transcribbio.phone.core.Prefs
import com.transcribbio.phone.data.RecordingStore
import com.transcribbio.shared.model.DeviceKind
import com.transcribbio.shared.model.Lecture
import com.transcribbio.shared.sync.LectureSummaryDto
import com.transcribbio.shared.sync.PairRequestDto
import com.transcribbio.shared.sync.RenameRequestDto
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
    private val drive: DriveRelay,
    private val scope: CoroutineScope,
) {
    private val discovery = Discovery(context)
    private val client = SyncClient()
    private val mutex = Mutex()

    private val _state = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private val _endpoint = MutableStateFlow<DesktopEndpoint?>(null)
    val endpoint: StateFlow<DesktopEndpoint?> = _endpoint.asStateFlow()

    /** Ask for a sync. Runs as a WorkManager job (not an in-app coroutine), so it keeps going
     *  when the screen turns off and resumes after the app is killed. [userAsked] = run on any
     *  network right away (the Drive part still honours the mobile-data setting). */
    fun requestSync(context: Context, userAsked: Boolean = false) = SyncWorker.kick(context, userAsked)

    suspend fun syncNow(): Boolean = mutex.withLock {
        _state.value = SyncUiState.Discovering
        val ep = resolveEndpoint() ?: run {
            // PC not reachable directly — hand the recordings to the Google Drive relay if set up.
            uploadViaCloud()?.let { return it }
            _state.value = SyncUiState.Error(
                "Couldn't reach your desktop. Make sure Transcribbio is open on the PC. University and " +
                    "public Wi-Fi (like eduroam) often block devices from seeing each other — connect Google " +
                    "Drive in Settings ▸ Cloud relay so recordings reach your PC from anywhere, or enter the " +
                    "PC's address in Settings ▸ Desktop connection."
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
        var failed = 0
        pending.forEachIndexed { i, rec ->
            _state.value = SyncUiState.Uploading(i + 1, pending.size, 0f)
            val file = store.audioFile(rec)
            if (!file.exists()) {
                store.setError(rec.id, "recording file missing")
                return@forEachIndexed
            }
            runCatching {
                client.uploadRecording(
                    ep.host, ep.port, token, rec.title, rec.language, rec.createdAtMillis, file, rec.id,
                ) { f -> _state.value = SyncUiState.Uploading(i + 1, pending.size, f) }
            }.onSuccess {
                store.markUploaded(rec.id, it.lectureId); uploaded++
            }.onFailure {
                failed++
                store.setError(rec.id, it.message ?: "upload failed")
            }
        }
        // Renames made on the phone after the desktop already had the recording.
        store.items.value.filter { it.renamePending }.forEach { rec ->
            val ok = client.rename(ep.host, ep.port, token,
                RenameRequestDto(rec.title, recordingId = rec.id, lectureId = rec.lectureId?.ifBlank { null }))
            if (ok) store.clearRenamePending(rec.id)
        }
        if (drive.isConnected && store.items.value.any { it.inCloud || it.renamePending }) {
            drive.silentToken()?.let { t ->
                runCatching { confirmCloudPickups(t) }
                // Still waiting in Drive (the desktop hasn't imported it): rename it there.
                store.items.value.filter { it.renamePending && it.cloudFileId != null }.forEach { rec ->
                    runCatching { drive.pushRename(t, rec) }.onSuccess { store.clearRenamePending(rec.id) }
                }
            }
        }
        _state.value = SyncUiState.Done(ep.name, uploaded, System.currentTimeMillis())
        return failed == 0
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
        // Discovery can succeed where connections are still blocked, so confirm it answers.
        discovery.discover()?.let { d -> probe(d.host, d.port)?.let { return found(it) } }
        prefs.lastEndpoint()?.let { (h, p) -> probe(h, p)?.let { return found(it) } }
        return null
    }

    /** Deliver through the Drive mailbox: pending recordings (resuming interrupted uploads) and
     *  renames. Returns null when the relay isn't connected (caller shows the "can't reach desktop"
     *  message instead); true when nothing is left to deliver, false to have the job retry later. */
    private suspend fun uploadViaCloud(): Boolean? {
        if (!drive.isConnected) return null
        val firstToken = drive.silentToken() ?: run {
            _state.value = SyncUiState.Error("Google Drive needs reconnecting — open Settings ▸ Cloud relay.")
            return false
        }
        runCatching { confirmCloudPickups(firstToken) }
        val pending = store.pendingUploads()
        fun renames() = store.items.value.filter { it.renamePending && (it.uploaded || it.cloudFileId != null) }
        if (pending.isEmpty() && renames().isEmpty()) {
            _state.value = SyncUiState.Done("Google Drive", 0, System.currentTimeMillis())
            return true
        }
        if (pending.isNotEmpty() && drive.onMeteredNetwork() && !prefs.cloudOnMobileData.value) {
            _state.value = SyncUiState.Error("${pending.size} recording(s) will upload to Google Drive on Wi-Fi " +
                "(uploading over mobile data is off in Settings ▸ Cloud relay).")
            return false
        }
        var uploaded = 0
        var failed = 0
        pending.forEachIndexed { i, queued ->
            val rec = store.get(queued.id) ?: return@forEachIndexed
            val file = store.audioFile(rec)
            if (!file.exists()) {
                store.setError(rec.id, "recording file missing")
                return@forEachIndexed
            }
            // Fresh token per recording: a long upload queue can outlive a 1-hour token.
            val token = drive.silentToken() ?: run {
                _state.value = SyncUiState.Error("Google Drive needs reconnecting — open Settings ▸ Cloud relay.")
                return false
            }
            _state.value = SyncUiState.Uploading(i + 1, pending.size, 0f)
            runCatching {
                drive.upload(token, rec, file, onSession = { store.setDriveSession(rec.id, it) }) { f ->
                    _state.value = SyncUiState.Uploading(i + 1, pending.size, f)
                }
            }.onSuccess { fileId ->
                store.markUploadedToCloud(rec.id, fileId); uploaded++
            }.onFailure {
                failed++
                store.setError(rec.id, "Google Drive: ${it.message ?: "upload failed"} — will retry")
            }
        }
        // Renames — including ones made while those uploads were still in flight.
        for (rec in renames()) {
            val token = drive.silentToken() ?: break
            runCatching { drive.pushRename(token, rec) }
                .onSuccess { store.clearRenamePending(rec.id) }
                .onFailure { failed++ }
        }
        _state.value = SyncUiState.Done("Google Drive (your PC picks it up)", uploaded, System.currentTimeMillis())
        return failed == 0
    }

    /** Recordings the PC has already pulled out of Drive count as synced on the phone too. */
    private suspend fun confirmCloudPickups(token: String) {
        store.items.value.filter { it.inCloud }.forEach { rec ->
            if (runCatching { drive.pickedUp(token, rec.id) }.getOrDefault(false)) store.markUploaded(rec.id, "")
        }
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
