package com.transcribbio.desktop.drive

import com.transcribbio.shared.drive.DriveApi
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** OAuth client from the Cloud Console "Desktop app" credentials JSON. */
data class DesktopClient(val clientId: String, val clientSecret: String)

class AuthException(message: String, val revoked: Boolean = false) : Exception(message)

/**
 * Google sign-in for the desktop app: the installed-app loopback flow with PKCE.
 * Opens the browser at Google's consent page and catches the redirect on a one-shot
 * 127.0.0.1 server, then trades the code for a refresh token (stored in the app config).
 */
class GoogleDesktopAuth(
    private val http: HttpClient,
    private val tokenUrl: String = "https://oauth2.googleapis.com/token",
    private val revokeUrl: String = "https://oauth2.googleapis.com/revoke",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val rnd = SecureRandom()

    private var cachedToken: String? = null
    private var cachedUntil = 0L

    companion object {
        /** Parse the downloaded client_secret_*.json ({"installed": {...}}). */
        fun parseClientFile(text: String): DesktopClient {
            val root = Json.parseToJsonElement(text).jsonObject
            val node = (root["installed"] ?: root["web"])?.jsonObject
                ?: throw IllegalArgumentException("Not an OAuth client file (no \"installed\" section)")
            val id = node["client_id"]?.jsonPrimitive?.content
            val secret = node["client_secret"]?.jsonPrimitive?.content
            require(!id.isNullOrBlank() && !secret.isNullOrBlank()) { "Client file is missing client_id / client_secret" }
            require(root["installed"] != null) { "That's a Web client — create a \"Desktop app\" client instead" }
            return DesktopClient(id!!, secret!!)
        }
    }

    /** Full interactive sign-in. Returns the refresh token. */
    suspend fun signIn(client: DesktopClient, openBrowser: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val verifier = randomString(64)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomString(24)

        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val redirect = "http://127.0.0.1:${server.localPort}"
            val url = "https://accounts.google.com/o/oauth2/v2/auth?" + listOf(
                "client_id" to client.clientId,
                "redirect_uri" to redirect,
                "response_type" to "code",
                "scope" to DriveApi.SCOPE,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "access_type" to "offline",
                "prompt" to "consent",
                "state" to state,
            ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }
            openBrowser(url)

            server.soTimeout = 5 * 60 * 1000
            val code = try {
                awaitRedirect(server, state)
            } catch (_: SocketTimeoutException) {
                throw AuthException("Sign-in timed out — try Connect again")
            }
            val r = http.submitForm(tokenUrl, Parameters.build {
                append("code", code)
                append("client_id", client.clientId)
                append("client_secret", client.clientSecret)
                append("redirect_uri", redirect)
                append("grant_type", "authorization_code")
                append("code_verifier", verifier)
            })
            val body = json.parseToJsonElement(r.bodyAsText()).jsonObject
            if (!r.status.isSuccess()) {
                throw AuthException("Google rejected the sign-in: ${body["error_description"]?.jsonPrimitive?.content ?: r.status}")
            }
            cachedToken = body["access_token"]?.jsonPrimitive?.content
            cachedUntil = System.currentTimeMillis() + ((body["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0) - 60) * 1000
            body["refresh_token"]?.jsonPrimitive?.content
                ?: throw AuthException("Google didn't return a refresh token — remove Transcribbio's access at myaccount.google.com/permissions and connect again")
        }
    }

    /** A valid access token, refreshed as needed. Throws AuthException(revoked=true) if access was removed. */
    suspend fun accessToken(client: DesktopClient, refreshToken: String): String {
        cachedToken?.let { if (System.currentTimeMillis() < cachedUntil) return it }
        val r = http.submitForm(tokenUrl, Parameters.build {
            append("client_id", client.clientId)
            append("client_secret", client.clientSecret)
            append("refresh_token", refreshToken)
            append("grant_type", "refresh_token")
        })
        val body = json.parseToJsonElement(r.bodyAsText()).jsonObject
        if (!r.status.isSuccess()) {
            val err = body["error"]?.jsonPrimitive?.content
            throw AuthException(
                if (err == "invalid_grant") "Google access was removed or expired — connect again"
                else "Couldn't refresh Google access (${body["error_description"]?.jsonPrimitive?.content ?: err})",
                revoked = err == "invalid_grant",
            )
        }
        val token = body["access_token"]?.jsonPrimitive?.content ?: throw AuthException("No access token in response")
        cachedToken = token
        cachedUntil = System.currentTimeMillis() + ((body["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600) - 60) * 1000
        return token
    }

    suspend fun revoke(refreshToken: String) {
        runCatching {
            http.submitForm(revokeUrl, Parameters.build { append("token", refreshToken) })
        }
        cachedToken = null
        cachedUntil = 0
    }

    /** Wait for Google's redirect (ignoring favicon etc.), reply with a friendly page, return the code. */
    private fun awaitRedirect(server: ServerSocket, state: String): String {
        while (true) {
            server.accept().use { sock ->
                sock.soTimeout = 10_000
                val requestLine = sock.getInputStream().bufferedReader().readLine() ?: return@use
                val target = requestLine.split(" ").getOrNull(1) ?: return@use
                val query = target.substringAfter('?', "")
                val params = query.split('&').filter { '=' in it }.associate {
                    URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to
                        URLDecoder.decode(it.substringAfter('='), StandardCharsets.UTF_8)
                }
                if (params["code"] == null && params["error"] == null) {
                    respond(sock, 404, "")
                    return@use
                }
                val ok = params["state"] == state && params["code"] != null
                respond(sock, 200, page(
                    if (ok) "Transcribbio is connected to Google Drive." else "Sign-in didn't complete.",
                    if (ok) "You can close this tab and go back to Transcribbio." else (params["error"] ?: "Please try again."),
                ))
                if (params["state"] != state) throw AuthException("Sign-in response didn't match — try again")
                params["error"]?.let { throw AuthException(if (it == "access_denied") "Access wasn't granted" else "Sign-in failed: $it") }
                return params["code"]!!
            }
        }
    }

    private fun respond(sock: java.net.Socket, status: Int, html: String) {
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        sock.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    private fun page(title: String, text: String) =
        "<!doctype html><meta charset=utf-8><title>Transcribbio</title>" +
            "<body style=\"font-family:system-ui,sans-serif;display:grid;place-items:center;height:90vh;margin:0\">" +
            "<div style=\"text-align:center\"><h2>$title</h2><p>${text.replace("<", "&lt;")}</p></div></body>"

    private fun randomString(len: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        return (1..len).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
}
