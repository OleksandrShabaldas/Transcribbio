package com.transcribbio.shared.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android/Wear updater. Uses the system [DownloadManager] for the actual download,
 * which is exactly what "bulletproof" requires: it runs in the background across
 * app death and screen-off, never times out on slow links, and auto-resumes after
 * a disrupted connection. Shared by the phone and watch apps.
 */
class AndroidUpdater(
    private val context: Context,
    private val currentVersion: String,
    private val assetKeyword: String,   // "phone" or "watch"
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    @Volatile private var downloadId: Long = -1L

    fun checkOnLaunch() { scope.launch { runCatching { check() } } }

    suspend fun check(): UpdateStatus {
        _status.value = UpdateStatus.Checking
        val release = withContext(Dispatchers.IO) { fetchLatest() }
            ?: return set(UpdateStatus.Error("Couldn't reach GitHub"))
        val asset = release.assetFor(assetKeyword, UpdateConfig.APK_EXT)
            ?: return set(UpdateStatus.UpToDate)
        return if (Versions.isNewer(release.tagName, currentVersion))
            set(UpdateStatus.Available(release.tagName.removePrefix("v"), release, asset))
        else set(UpdateStatus.UpToDate)
    }

    fun startDownload() {
        val available = _status.value as? UpdateStatus.Available ?: return
        val dm = context.getSystemService(DownloadManager::class.java)
        val fileName = "Transcribbio-$assetKeyword-${available.version}.apk"
        val request = DownloadManager.Request(Uri.parse(available.asset.browserDownloadUrl)).apply {
            setTitle("Transcribbio ${available.version}")
            setDescription("Downloading update")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, null, fileName)
            setMimeType("application/vnd.android.package-archive")
            setAllowedOverMetered(true)   // download anywhere; DownloadManager resumes on loss
            setAllowedOverRoaming(true)
        }
        downloadId = dm.enqueue(request)
        _status.value = UpdateStatus.Downloading(available.version, 0f)
        pollProgress(downloadId, available.version)
    }

    /** Hand the downloaded APK to the system installer (user confirms). */
    fun install() {
        if (downloadId < 0) return
        val dm = context.getSystemService(DownloadManager::class.java)
        val uri = dm.getUriForDownloadedFile(downloadId) ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openReleasesPage() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateConfig.RELEASES_PAGE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun pollProgress(id: Long, version: String) {
        scope.launch {
            val dm = context.getSystemService(DownloadManager::class.java)
            while (true) {
                val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: break
                var finished = false
                cursor.use { c ->
                    if (c.moveToFirst()) {
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                _status.value = UpdateStatus.Downloaded(version); finished = true
                            }
                            DownloadManager.STATUS_FAILED -> {
                                _status.value = UpdateStatus.Error("Download failed"); finished = true
                            }
                            else -> {
                                val f = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                                _status.value = UpdateStatus.Downloading(version, f)
                            }
                        }
                    }
                }
                if (finished) break
                delay(600)
            }
        }
    }

    private fun fetchLatest(): GithubRelease? {
        val conn = (URL(UpdateConfig.LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Transcribbio-Android")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                json.decodeFromString(GithubRelease.serializer(), body)
            } else null
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun set(s: UpdateStatus): UpdateStatus {
        _status.value = s
        return s
    }
}
