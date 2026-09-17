package com.transcribbio.desktop.update

import com.transcribbio.shared.update.GithubAsset
import com.transcribbio.shared.update.GithubRelease
import com.transcribbio.shared.update.UpdateConfig
import com.transcribbio.shared.update.UpdateStatus
import com.transcribbio.shared.update.Versions
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import kotlin.system.exitProcess

/** Checks GitHub Releases, downloads the desktop asset resumably, and applies it
 *  by swapping the portable app-image folder on restart. */
class DesktopUpdater(
    private val currentVersion: String,
    private val dataDir: File,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            // Never time out on a slow/paused connection; only bound the initial connect.
            requestTimeoutMillis = Long.MAX_VALUE
            socketTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 30_000
        }
    }

    private val updatesDir = File(dataDir, "updates").apply { runCatching { mkdirs() } }
    private var downloadedFile: File? = null

    fun checkOnLaunch() {
        scope.launch { runCatching { check() } }
    }

    fun checkManually() {
        scope.launch { runCatching { check() } }
    }

    suspend fun check(): UpdateStatus {
        _status.value = UpdateStatus.Checking
        val release = fetchLatest() ?: return set(UpdateStatus.Error("Couldn't reach GitHub"))
        val asset = release.assetFor(UpdateConfig.DESKTOP_KEYWORD, UpdateConfig.DESKTOP_EXT)
            ?: return set(UpdateStatus.UpToDate)
        return if (Versions.isNewer(release.tagName, currentVersion))
            set(UpdateStatus.Available(release.tagName.removePrefix("v"), release, asset))
        else set(UpdateStatus.UpToDate)
    }

    private suspend fun fetchLatest(): GithubRelease? = runCatching {
        http.get(UpdateConfig.LATEST_RELEASE_API) {
            header(HttpHeaders.UserAgent, "Transcribbio-Desktop")
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }.body<GithubRelease>()
    }.getOrNull()

    fun startDownload() {
        val available = _status.value as? UpdateStatus.Available ?: return
        scope.launch {
            try {
                val target = File(updatesDir, available.asset.name)
                resumableDownload(available.asset, target) { frac ->
                    _status.value = UpdateStatus.Downloading(available.version, frac)
                }
                downloadedFile = target
                _status.value = UpdateStatus.Downloaded(available.version)
            } catch (e: Exception) {
                _status.value = UpdateStatus.Error(e.message ?: "Download failed")
            }
        }
    }

    /** Resumable, retrying download using HTTP Range. Survives disconnects and slow links. */
    private suspend fun resumableDownload(asset: GithubAsset, target: File, onProgress: (Float) -> Unit) {
        val total = asset.size
        var attempt = 0
        while (true) {
            val existing = if (target.exists()) target.length() else 0L
            if (total > 0 && existing >= total) return
            try {
                http.prepareGet(asset.browserDownloadUrl) {
                    header(HttpHeaders.UserAgent, "Transcribbio-Desktop")
                    if (existing > 0) header(HttpHeaders.Range, "bytes=$existing-")
                }.execute { resp ->
                    val append = resp.status == HttpStatusCode.PartialContent && existing > 0
                    val grandTotal = if (total > 0) total else existing + (resp.contentLength() ?: 0L)
                    var written = if (append) existing else 0L
                    val channel = resp.bodyAsChannel()
                    FileOutputStream(target, append).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val read = channel.readAvailable(buf, 0, buf.size)
                            if (read == -1) break
                            if (read > 0) {
                                out.write(buf, 0, read)
                                written += read
                                if (grandTotal > 0) onProgress((written.toFloat() / grandTotal).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (total <= 0 || target.length() >= total) return
                // Short read: loop and resume from the new length.
            } catch (e: Exception) {
                attempt++
                if (attempt > 30) throw e
                delay(minOf(30_000L, 1500L * attempt))
                // loop; download resumes from target.length()
            }
        }
    }

    /** Apply the update: launch the downloaded installer, then exit so it can replace files. */
    fun installAndRestart() {
        val installer = downloadedFile ?: return
        try {
            // The .exe is a per-user installer; running it upgrades the install in place
            // (same upgrade UUID) and creates the Start Menu shortcut.
            ProcessBuilder(installer.absolutePath).start()
            exitProcess(0)
        } catch (e: Exception) {
            runCatching { Desktop.getDesktop().open(installer.parentFile) }
            _status.value = UpdateStatus.Error("Couldn't launch installer: ${e.message}")
        }
    }

    fun openReleasesPage() {
        runCatching { Desktop.getDesktop().browse(URI(UpdateConfig.RELEASES_PAGE)) }
    }

    private fun set(s: UpdateStatus): UpdateStatus {
        _status.value = s
        return s
    }
}
