package com.transcribbio.phone.sync

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.transcribbio.phone.core.Prefs
import com.transcribbio.phone.data.PendingRecording
import com.transcribbio.shared.drive.DriveApi
import com.transcribbio.shared.drive.RelayRecording
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.RandomAccessFile

/**
 * Phone end of the cloud relay: when the desktop can't be reached directly, recordings go to the
 * app's hidden Google Drive folder (scope drive.appdata — it can't see the user's other files);
 * the desktop downloads and deletes them. Sign-in uses Google Play services' Authorization API,
 * which needs an Android OAuth client (package + signing SHA-1) in the user's Cloud project.
 */
class DriveRelay(private val context: Context, private val prefs: Prefs) {
    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60 * 60 * 1000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
        }
    }
    private val api = DriveApi(http)

    val isConnected: Boolean get() = prefs.driveAccount.value.isNotBlank()

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DriveApi.SCOPE)))
        .build()

    /** Start connecting from the UI. Returns a consent screen to launch, or null if access
     *  was already granted (then the connection is complete). */
    suspend fun beginConnect(activity: Activity): PendingIntent? {
        val result = Identity.getAuthorizationClient(activity).authorize(request()).await()
        if (result.hasResolution()) return result.pendingIntent
        finish(result.accessToken)
        return null
    }

    /** Finish after the consent screen returns. */
    suspend fun completeConnect(data: Intent?) {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        finish(result.accessToken)
    }

    private suspend fun finish(token: String?) {
        val t = token ?: throw IllegalStateException("Google didn't grant access")
        prefs.setDriveAccount(api.accountEmail(t) ?: "your Google account")
    }

    /** A fresh access token without any UI (works in the background), or null if the user
     *  has to reconnect in Settings. */
    suspend fun silentToken(): String? = runCatching {
        val r = Identity.getAuthorizationClient(context).authorize(request()).await()
        if (r.hasResolution()) null else r.accessToken
    }.getOrNull()

    suspend fun disconnect() {
        val email = prefs.driveAccount.value
        if (email.contains('@')) runCatching {
            Identity.getAuthorizationClient(context).revokeAccess(
                RevokeAccessRequest.builder()
                    .setAccount(Account(email, "com.google"))
                    .setScopes(listOf(Scope(DriveApi.SCOPE)))
                    .build()
            ).await()
        }
        prefs.setDriveAccount("")
    }

    /** Upload one recording to the Drive mailbox (skipped if it's already there). Resumes the
     *  recording's saved upload session, so an interrupted upload continues instead of restarting.
     *  Returns the Drive file id. */
    suspend fun upload(
        token: String,
        rec: PendingRecording,
        file: File,
        onSession: (String?) -> Unit,
        onProgress: (Float) -> Unit,
    ): String {
        if (rec.driveSession == null) api.findByRecId(token, rec.id)?.let { return it }
        val meta = RelayRecording(
            recId = rec.id,
            title = rec.title,
            language = rec.language,
            recordedAtMillis = rec.createdAtMillis,
            source = "phone",
            fileExt = file.extension.ifBlank { "m4a" },
        )
        return RandomAccessFile(file, "r").use { raf ->
            api.upload(token, meta, file.length(), "audio/mp4",
                read = { offset, len -> ByteArray(len).also { raf.seek(offset); raf.readFully(it) } },
                onProgress = onProgress,
                session = rec.driveSession,
                onSession = onSession)
        }
    }

    /** Push a rename for a recording that is in (or has been through) the Drive mailbox. */
    suspend fun pushRename(token: String, rec: PendingRecording) {
        val fileId = rec.cloudFileId
        if (!rec.uploaded && fileId != null && api.setTitle(token, fileId, rec.title)) return // still waiting there
        api.postRename(token, rec.id, rec.title) // the PC already has it: leave it a rename note
    }

    /** Has the PC already taken this recording out of the Drive mailbox? */
    suspend fun pickedUp(token: String, recId: String): Boolean = api.findByRecId(token, recId) == null

    /** True on mobile data / metered hotspots (where large uploads wait unless allowed). */
    fun onMeteredNetwork(): Boolean =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    companion object {
        /** A user-facing explanation for a failed connect, or null if the user just cancelled. */
        fun explain(e: Throwable): String? {
            val code = (e as? ApiException)?.statusCode
            return when (code) {
                CommonStatusCodes.CANCELED, 12501 -> null
                CommonStatusCodes.DEVELOPER_ERROR ->
                    "Google doesn't recognise this app yet. Finish the one-time setup on your PC " +
                        "(Transcribbio ▸ Settings ▸ Cloud relay) — including the Android client with the SHA-1 shown there."
                CommonStatusCodes.NETWORK_ERROR -> "No internet connection — try again when you're online."
                else -> "Couldn't connect Google Drive: ${e.message ?: e::class.simpleName}"
            }
        }
    }
}
