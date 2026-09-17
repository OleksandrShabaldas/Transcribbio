package com.transcribbio.shared.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where updates come from, and the naming convention for per-platform assets. */
object UpdateConfig {
    const val REPO_OWNER = "OleksandrShabaldas"
    const val REPO_NAME = "Transcribbio"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"

    // Release assets are named "Transcribbio-<platform>-<version>...".
    const val DESKTOP_KEYWORD = "desktop"
    const val DESKTOP_EXT = ".exe"   // single-file Windows installer
    const val PHONE_KEYWORD = "phone"
    const val WATCH_KEYWORD = "watch"
    const val APK_EXT = ".apk"
}

@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
    @SerialName("content_type") val contentType: String = "",
)

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
    @SerialName("html_url") val htmlUrl: String = "",
) {
    fun assetFor(keyword: String, extension: String): GithubAsset? =
        assets.firstOrNull { it.name.contains(keyword, ignoreCase = true) && it.name.endsWith(extension, ignoreCase = true) }
}

/** Result of a check, surfaced to the UI on every platform. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val version: String, val release: GithubRelease, val asset: GithubAsset) : UpdateStatus
    data class Downloading(val version: String, val fraction: Float) : UpdateStatus
    data class Downloaded(val version: String) : UpdateStatus
    data class Error(val message: String) : UpdateStatus
}

object Versions {
    private val VERSION_RE = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""")

    /** Extract a version like "1.2.3" from an asset filename, e.g.
     *  "Transcribbio-phone-1.2.3.apk" -> "1.2.3". Null if none found. */
    fun fromFileName(name: String): String? = VERSION_RE.find(name)?.value

    /** "v1.2.3-beta" -> [1,2,3] */
    fun normalize(v: String): List<Int> {
        val cleaned = v.trim().removePrefix("v").removePrefix("V")
        val core = cleaned.substringBefore('-').substringBefore('+')
        return core.split('.').map { it.trim().toIntOrNull() ?: 0 }
    }

    /** True if [latest] is strictly newer than [current]. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = normalize(latest)
        val b = normalize(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
