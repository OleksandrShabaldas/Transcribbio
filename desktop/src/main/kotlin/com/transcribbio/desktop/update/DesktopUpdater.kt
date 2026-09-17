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
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

/**
 * Checks GitHub Releases and applies desktop updates. When running as an installed
 * app-image, it downloads only the app payload (`…-app.zip` — the app/ folder, no
 * bundled Java runtime) and swaps it in place on restart. Otherwise (or if no app
 * payload is published) it downloads and runs the full `.exe` installer.
 */
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
            requestTimeoutMillis = Long.MAX_VALUE
            socketTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 30_000
        }
    }

    private val updatesDir = File(dataDir, "updates").apply { runCatching { mkdirs() } }
    private var pendingExe: GithubAsset? = null
    private var pendingAppZip: GithubAsset? = null
    private var downloadedFile: File? = null
    private var downloadedIsAppZip = false

    fun checkOnLaunch() { scope.launch { runCatching { check() } } }
    fun checkManually() { scope.launch { runCatching { check() } } }

    suspend fun check(): UpdateStatus {
        _status.value = UpdateStatus.Checking
        val release = fetchLatest() ?: return set(UpdateStatus.Error("Couldn't reach GitHub"))
        val exe = release.assets.firstOrNull {
            it.name.contains(UpdateConfig.DESKTOP_KEYWORD, true) && it.name.endsWith(".exe", true)
        }
        val appZip = release.assets.firstOrNull {
            it.name.contains(UpdateConfig.DESKTOP_KEYWORD, true) && it.name.endsWith("-app.zip", true)
        }
        val versionAsset = exe ?: appZip ?: return set(UpdateStatus.UpToDate)
        val version = Versions.fromFileName(versionAsset.name) ?: release.tagName.removePrefix("v")
        return if (Versions.isNewer(version, currentVersion)) {
            pendingExe = exe
            pendingAppZip = appZip
            set(UpdateStatus.Available(version, release, versionAsset))
        } else set(UpdateStatus.UpToDate)
    }

    private suspend fun fetchLatest(): GithubRelease? = runCatching {
        http.get(UpdateConfig.LATEST_RELEASE_API) {
            header(HttpHeaders.UserAgent, "Transcribbio-Desktop")
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }.body<GithubRelease>()
    }.getOrNull()

    fun startDownload() {
        val version = (_status.value as? UpdateStatus.Available)?.version ?: return
        scope.launch {
            try {
                // In-place app swap when installed and a payload exists; else full installer.
                val useAppZip = detectInstallDir() != null && pendingAppZip != null
                val asset = if (useAppZip) pendingAppZip!! else (pendingExe ?: pendingAppZip) ?: return@launch
                val target = File(updatesDir, asset.name)
                resumableDownload(asset, target) { frac ->
                    _status.value = UpdateStatus.Downloading(version, frac)
                }
                downloadedFile = target
                downloadedIsAppZip = useAppZip
                _status.value = UpdateStatus.Downloaded(version)
            } catch (e: Exception) {
                _status.value = UpdateStatus.Error(e.message ?: "Download failed")
            }
        }
    }

    fun installAndRestart() {
        val file = downloadedFile ?: return
        if (downloadedIsAppZip) applyAppZip(file) else runInstaller(file)
    }

    fun openReleasesPage() {
        runCatching { Desktop.getDesktop().browse(URI(UpdateConfig.RELEASES_PAGE)) }
    }

    // ── apply strategies ──

    private fun runInstaller(exe: File) {
        try {
            ProcessBuilder(exe.absolutePath).start()
            exitProcess(0)
        } catch (e: Exception) {
            runCatching { Desktop.getDesktop().open(exe.parentFile) }
            _status.value = UpdateStatus.Error("Couldn't launch installer: ${e.message}")
        }
    }

    /** Swap the installed app/ folder (jars + resources) with the downloaded payload,
     *  leaving the Java runtime and launcher untouched. Applied on restart. */
    private fun applyAppZip(zip: File) {
        val installDir = detectInstallDir() ?: run {
            runCatching { Desktop.getDesktop().open(zip.parentFile) }
            return
        }
        try {
            val staging = File(updatesDir, "staging")
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
            extractZip(zip, staging) // -> staging/app/...
            val newApp = File(staging, "app").takeIf { it.isDirectory } ?: staging
            val script = writeSwapScript(
                stagingApp = newApp,
                installAppDir = File(installDir, "app"),
                launcher = File(installDir, "Transcribbio.exe"),
                staging = staging,
            )
            ProcessBuilder(
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                script.absolutePath, ProcessHandle.current().pid().toString(),
            ).start()
            exitProcess(0)
        } catch (e: Exception) {
            _status.value = UpdateStatus.Error("Update failed: ${e.message}")
        }
    }

    private fun detectInstallDir(): File? {
        val javaHome = File(System.getProperty("java.home"))
        val install = javaHome.parentFile ?: return null // <install>/runtime -> <install>
        return if (File(install, "Transcribbio.exe").exists()) install else null
    }

    private fun extractZip(zip: File, dest: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(dest, entry.name)
                if (entry.isDirectory) outFile.mkdirs()
                else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun writeSwapScript(stagingApp: File, installAppDir: File, launcher: File, staging: File): File {
        val script = Files.createTempFile("transcribbio-update", ".ps1").toFile()
        script.writeText(
            """
            param([int]${'$'}procId)
            try { Wait-Process -Id ${'$'}procId -Timeout 120 } catch {}
            Start-Sleep -Seconds 1
            robocopy "${stagingApp.absolutePath}" "${installAppDir.absolutePath}" /MIR /NFL /NDL /NJH /NJS /R:3 /W:2 | Out-Null
            Start-Sleep -Seconds 1
            Start-Process "${launcher.absolutePath}"
            Remove-Item -Recurse -Force "${staging.absolutePath}" -ErrorAction SilentlyContinue
            Remove-Item -Force "${'$'}PSCommandPath" -ErrorAction SilentlyContinue
            """.trimIndent(),
            Charsets.UTF_8,
        )
        return script
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
            } catch (e: Exception) {
                attempt++
                if (attempt > 30) throw e
                delay(minOf(30_000L, 1500L * attempt))
            }
        }
    }

    private fun set(s: UpdateStatus): UpdateStatus {
        _status.value = s
        return s
    }
}
