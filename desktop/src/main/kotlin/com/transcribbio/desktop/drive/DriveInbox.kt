package com.transcribbio.desktop.drive

import com.transcribbio.desktop.core.AppConfig
import com.transcribbio.desktop.data.LibraryRepository
import com.transcribbio.shared.drive.DriveApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path

sealed interface DriveState {
    /** No OAuth client file loaded yet (one-time Google Cloud setup not done). */
    data object NotSetUp : DriveState
    /** Client loaded, but not signed in. */
    data object NotConnected : DriveState
    data object Connecting : DriveState
    data class Connected(
        val account: String,
        val lastCheckMillis: Long = 0,
        val checking: Boolean = false,
        val pickedUp: Int = 0,
        val lastError: String? = null,
    ) : DriveState
    data class Failed(val message: String) : DriveState
}

/**
 * The desktop end of the cloud relay: every minute, looks in the app's hidden Google Drive
 * folder for recordings the phone dropped there, imports each into the library (which starts
 * transcription like a Wi-Fi upload would), then deletes it from Drive.
 */
class DriveInbox(
    private val scope: CoroutineScope,
    private val dataDir: Path,
    private val repo: LibraryRepository,
    private val configProvider: () -> AppConfig,
    /** Atomically update the stored config (Drive credentials are owned by this class). */
    private val updateConfig: ((AppConfig) -> AppConfig) -> Unit,
    private val onImported: (String) -> Unit,
    /** Overridable endpoints (tests); Google's by default. */
    driveApiBase: String = "https://www.googleapis.com/drive/v3",
    tokenUrl: String = "https://oauth2.googleapis.com/token",
) {
    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60 * 60 * 1000 // big recordings on slow links
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 120_000
        }
    }
    private val auth = GoogleDesktopAuth(http, tokenUrl = tokenUrl)
    private val drive = DriveApi(http, apiBase = driveApiBase)
    private val mutex = Mutex()
    private var loop: Job? = null

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<DriveState> = _state.asStateFlow()

    /** Drive files already imported but not yet deleted (delete failed) — never import twice. */
    private val importedFile = dataDir.resolve("drive-imported.txt").toFile()
    private val imported: MutableSet<String> =
        runCatching { importedFile.readLines().filter { it.isNotBlank() }.toMutableSet() }.getOrDefault(mutableSetOf())

    private fun client(cfg: AppConfig = configProvider()) =
        if (cfg.driveClientId.isNotBlank() && cfg.driveClientSecret.isNotBlank())
            DesktopClient(cfg.driveClientId, cfg.driveClientSecret) else null

    private fun initialState(): DriveState {
        val cfg = configProvider()
        return when {
            client(cfg) == null -> DriveState.NotSetUp
            cfg.driveRefreshToken.isBlank() -> DriveState.NotConnected
            else -> DriveState.Connected(cfg.driveAccount.ifBlank { "your Google account" })
        }
    }

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            delay(5_000) // let the app finish starting
            while (isActive) {
                if (configProvider().driveRefreshToken.isNotBlank()) check()
                delay(60_000)
            }
        }
    }

    /** Store the Cloud Console "Desktop app" client file. */
    fun loadClientFile(file: File): Result<Unit> = runCatching {
        val c = GoogleDesktopAuth.parseClientFile(file.readText())
        updateConfig { it.copy(driveClientId = c.clientId, driveClientSecret = c.clientSecret,
            driveRefreshToken = "", driveAccount = "") }
        _state.value = DriveState.NotConnected
    }

    fun connect(openBrowser: (String) -> Unit) {
        val c = client() ?: return
        scope.launch {
            _state.value = DriveState.Connecting
            try {
                val refresh = auth.signIn(c, openBrowser)
                val email = drive.accountEmail(auth.accessToken(c, refresh)) ?: "your Google account"
                updateConfig { it.copy(driveRefreshToken = refresh, driveAccount = email) }
                _state.value = DriveState.Connected(email)
                check()
            } catch (e: Exception) {
                _state.value = DriveState.Failed(e.message ?: "Couldn't connect")
            }
        }
    }

    fun disconnect() {
        val refresh = configProvider().driveRefreshToken
        scope.launch {
            if (refresh.isNotBlank()) auth.revoke(refresh)
            updateConfig { it.copy(driveRefreshToken = "", driveAccount = "") }
            _state.value = if (client() == null) DriveState.NotSetUp else DriveState.NotConnected
        }
    }

    fun checkNow() { scope.launch { check() } }

    /** One pass over the mailbox (exposed for tests). */
    internal suspend fun checkOnce() = check()

    /** Pick up everything waiting in the Drive mailbox. */
    private suspend fun check() = mutex.withLock {
        val cfg = configProvider()
        val c = client(cfg) ?: return@withLock
        if (cfg.driveRefreshToken.isBlank()) return@withLock
        val account = cfg.driveAccount.ifBlank { "your Google account" }
        val prev = _state.value as? DriveState.Connected
        _state.value = (prev ?: DriveState.Connected(account)).copy(checking = true)
        var picked = prev?.pickedUp ?: 0
        try {
            val token = auth.accessToken(c, cfg.driveRefreshToken)
            for (item in drive.list(token)) {
                if (item.fileId !in imported) {
                    val ext = item.meta.fileExt.filter { it.isLetterOrDigit() }.ifBlank { "m4a" }
                    val tmp = dataDir.resolve("updates").resolve("drive-${item.fileId}.$ext").toFile()
                    tmp.parentFile.mkdirs()
                    FileOutputStream(tmp).use { out -> drive.download(token, item.fileId) { buf, n -> out.write(buf, 0, n) } }
                    val lecture = tmp.inputStream().use {
                        repo.saveUpload(it, "audio.$ext", item.meta.title, item.meta.language,
                            "${item.meta.source} (via Google Drive)", item.meta.recordedAtMillis)
                    }
                    tmp.delete()
                    imported += item.fileId
                    persistImported()
                    picked++
                    onImported(lecture.id)
                }
                drive.delete(token, item.fileId) // only after the recording is safely in the library
                imported -= item.fileId
                persistImported()
            }
            _state.value = DriveState.Connected(account, System.currentTimeMillis(), false, picked, null)
        } catch (e: AuthException) {
            if (e.revoked) updateConfig { it.copy(driveRefreshToken = "") }
            _state.value = if (e.revoked) DriveState.Failed(e.message ?: "Reconnect Google Drive")
            else DriveState.Connected(account, System.currentTimeMillis(), false, picked, e.message)
        } catch (e: Exception) {
            _state.value = DriveState.Connected(account, System.currentTimeMillis(), false, picked,
                e.message ?: "Couldn't reach Google Drive")
        }
    }

    private fun persistImported() {
        runCatching { importedFile.writeText(imported.joinToString("\n")) }
    }
}
