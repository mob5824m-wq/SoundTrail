package tech.egrie.soundtrail.integrations

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.UUID

/**
 * The client half of a Jellyfin connection: sign in against the user's own server, search its
 * music library, resolve streams for playback, and copy an item the signed-in account may download.
 *
 * Only a username and a password are ever sent, and only once, to the address the user typed.
 * The password is never stored; the server keeps an access token, which lives in
 * Android Keystore-encrypted storage on this device. Everything here is read-only — Soundtrail
 * never writes to, renames, or deletes anything in the user's library.
 */
data class JellyfinDownloadInfo(
    val contentType: String?,
    val filename: String?,
    val bytes: Long
)

class JellyfinService(context: Context) {
    private val settings = context.getSharedPreferences("soundtrail_settings", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    val serverUrl: String get() = settings.getString("jellyfin_server", "").orEmpty()
    val userName: String get() = settings.getString("jellyfin_user", "").orEmpty()
    val serverName: String get() = settings.getString("jellyfin_server_name", "").orEmpty()

    private val userId: String get() = settings.getString("jellyfin_user_id", "").orEmpty()
    private val token: String get() = secrets.get("jellyfin_token").orEmpty()

    val connected: Boolean
        get() = serverUrl.isNotBlank() && userId.isNotBlank() && token.isNotBlank()

    /** A stable per-install device id, so the server can recognise (and revoke) this app. */
    private val deviceId: String
        get() {
            settings.getString("jellyfin_device_id", "")?.takeIf(String::isNotBlank)?.let { return it }
            val generated = UUID.randomUUID().toString()
            settings.edit().putString("jellyfin_device_id", generated).apply()
            return generated
        }

    /** Signs in and stores the resulting session; throws [JellyfinException] with a usable message. */
    suspend fun connect(rawServer: String, rawUser: String, rawPassword: String): String {
        val server = EndpointPolicy.jellyfinServer(rawServer)
            ?: throw JellyfinException("Enter your Jellyfin address, like http://192.168.1.20:8096.")
        val user = rawUser.trim()
        if (user.isEmpty()) throw JellyfinException("Enter your Jellyfin username.")
        if (rawPassword.isEmpty()) throw JellyfinException("Enter your Jellyfin password.")

        // Ask the public endpoint first: it proves the address is a Jellyfin server and names it.
        val info = runCatching { publicInfo(server) }.getOrNull()
            ?: throw JellyfinException(
                "No Jellyfin server answered at $server. Check the address, the port, " +
                    "and that this device can reach your server."
            )

        val response = httpRequest(
            "$server/Users/AuthenticateByName", "POST",
            body = JellyfinApi.authenticationBody(user, rawPassword),
            authorization = JellyfinApi.authorizationHeader(deviceId),
            contentType = "application/json; charset=UTF-8",
            transport = TransportRule.JELLYFIN
        )
        if (response.code !in 200..299) throw response.error()
        val session = JellyfinApi.session(response.body)
            ?: throw JellyfinException("Jellyfin accepted the sign-in but returned no usable session.")

        // Only now replace stored settings: a failed attempt must not drop a working session.
        disconnect()
        settings.edit()
            .putString("jellyfin_server", server)
            .putString("jellyfin_user", user)
            .putString("jellyfin_server_name", info.name)
            .putString("jellyfin_user_id", session.userId)
            .apply()
        secrets.put("jellyfin_token", session.accessToken)
        return if (session.userName.isNotBlank()) session.userName else user
    }

    /** Clears the local session and returns the token for a best-effort server-side sign-out. */
    fun disconnect(): String? {
        val previous = secrets.get("jellyfin_token")
        secrets.remove("jellyfin_token")
        settings.edit().remove("jellyfin_user_id").apply()
        return previous
    }

    /** Ends the session on the server too. Best effort: local tokens are already gone. */
    suspend fun revokeAccessToken(server: String, previousToken: String) {
        runCatching {
            httpRequest(
                "$server/Sessions/Logout", "POST",
                authorization = JellyfinApi.authorizationHeader(deviceId, previousToken),
                transport = TransportRule.JELLYFIN
            )
        }
    }

    suspend fun searchTracks(query: String): List<TrackResult> {
        if (query.isBlank()) return emptyList()
        if (!connected) throw JellyfinException("Connect your Jellyfin server to search its library.")
        val response = apiGet(JellyfinApi.searchPath(userId, query))
        if (response.code !in 200..299) throw response.error()
        return JellyfinApi.items(response.body).mapNotNull { track ->
            // The pickable link is the item reference; the server address is added at play time.
            JellyfinRef.link(track.id)?.let {
                TrackResult(title = track.title, artist = track.artist, url = it.url, provider = it.provider)
            }
        }
    }

    /**
     * Downloads the selected item from the user's own Jellyfin server using its official
     * authenticated item-download route. Server permissions remain authoritative; Soundtrail
     * cannot bypass Jellyfin's access checks. The response is streamed to a private temp file
     * and capped so a malformed endpoint cannot fill app storage without limit.
     */
    suspend fun downloadAudio(
        rawItemId: String,
        target: File,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }
    ): JellyfinDownloadInfo = withContext(Dispatchers.IO) {
        if (!connected) throw JellyfinException("Connect your Jellyfin server before downloading.")
        val path = JellyfinApi.downloadPath(rawItemId)
            ?: throw JellyfinException("That Jellyfin item link is not valid.")
        // This request streams instead of going through httpRequest, so it applies the same
        // transport rule itself, before the connection exists and before the token is attached.
        try {
            EndpointPolicy.requireSecureTransport(serverUrl + path, TransportRule.JELLYFIN)
        } catch (e: EndpointPolicyException) {
            throw JellyfinException(e.message ?: "That Jellyfin address cannot be used.")
        }
        val connection = try {
            (URL(serverUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Accept", "audio/*, application/octet-stream;q=0.9, */*;q=0.5")
                setRequestProperty("Cache-Control", "no-store")
                setRequestProperty("Authorization", JellyfinApi.authorizationHeader(deviceId, token))
            }
        } catch (_: Exception) {
            throw JellyfinException("Could not connect to your Jellyfin server.")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = readErrorBody(connection)
                if (code == 401) {
                    disconnect()
                    throw JellyfinException("Your Jellyfin session ended. Connect again on the Connect tab.")
                }
                if (code == 403) throw JellyfinException("Jellyfin does not allow this account to download that item.")
                val detail = runCatching { HttpResponse(code, body).detail() }.getOrDefault("")
                throw JellyfinException(detail.ifBlank { "Jellyfin could not download this item (HTTP $code)." })
            }
            val mediaType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (mediaType != null && (mediaType.startsWith("text/") || mediaType.contains("json"))) {
                throw JellyfinException("Jellyfin returned a web page instead of an audio file. Check the server address.")
            }
            if (mediaType?.startsWith("video/") == true) {
                throw JellyfinException("Only Jellyfin audio tracks can be downloaded for offline playback.")
            }
            val total = connection.contentLengthLong
            if (total > MAX_OFFLINE_DOWNLOAD_BYTES) {
                throw JellyfinException("That track is larger than the 250 MiB offline-download limit.")
            }
            target.parentFile?.mkdirs()
            var received = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > MAX_OFFLINE_DOWNLOAD_BYTES) {
                            throw JellyfinException("That track is larger than the 250 MiB offline-download limit.")
                        }
                        output.write(buffer, 0, count)
                        onProgress(received, total)
                    }
                }
            }
            if (received == 0L) throw JellyfinException("Jellyfin returned an empty audio file.")
            JellyfinDownloadInfo(
                contentType = mediaType,
                filename = downloadFilename(connection.getHeaderField("Content-Disposition")),
                bytes = received
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readErrorBody(connection: HttpURLConnection): String = runCatching {
        val stream = connection.errorStream ?: return@runCatching ""
        stream.bufferedReader().use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(1_024)
            while (result.length < MAX_ERROR_BODY_CHARS) {
                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_ERROR_BODY_CHARS - result.length))
                if (count < 0) break
                result.append(buffer, 0, count)
            }
            result.toString()
        }
    }.getOrDefault("")

    private fun downloadFilename(disposition: String?): String? {
        val raw = disposition?.let {
            Regex("filename\\*?=(?:UTF-8''|\\\"?)([^\\\";]+)", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.getOrNull(1)
        }?.trim() ?: return null
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            .substringAfterLast('/').substringAfterLast(92.toChar())
            .filterNot(Char::isISOControl).take(160).takeIf(String::isNotBlank)
    }

    /** Stream URLs plus the header the player must send, or null when nothing is connected. */
    fun playback(rawItemId: String): JellyfinPlayback? {
        if (!connected) return null
        val urls = JellyfinApi.streamPaths(serverUrl, rawItemId, userId, deviceId)
        if (urls.isEmpty()) return null
        return JellyfinPlayback(urls, JellyfinApi.authorizationHeader(deviceId, token))
    }

    private suspend fun publicInfo(server: String): JellyfinServerInfo? {
        val response = httpRequest("$server/System/Info/Public", transport = TransportRule.JELLYFIN)
        if (response.code !in 200..299) return null
        return JellyfinApi.publicInfo(response.body)
    }

    private suspend fun apiGet(path: String): HttpResponse {
        val response = httpRequest(
            serverUrl + path, authorization = JellyfinApi.authorizationHeader(deviceId, token),
            transport = TransportRule.JELLYFIN
        )
        if (response.code == 401) {
            // A revoked or expired token is useless; drop it so the UI stops claiming a session.
            disconnect()
            throw JellyfinException("Your Jellyfin session ended. Connect again on the Connect tab.")
        }
        return response
    }

    private fun HttpResponse.error(): JellyfinException {
        // Http.kt's shared detail() already reads the provider's own explanation, if any.
        val detail = detail()
        val fallback = when (code) {
            400 -> "Jellyfin rejected this request. Check the address and your account name."
            401, 403 -> "Jellyfin refused those credentials. Check your username and password."
            404 -> "That address answered, but not like a Jellyfin server. Check the URL and port."
            else -> "Your Jellyfin server answered with HTTP $code. Try again shortly."
        }
        return JellyfinException(detail.takeIf { it.isNotBlank() && code != 403 } ?: fallback)
    }

    private companion object {
        const val MAX_ERROR_BODY_CHARS = 4_000
        const val MAX_OFFLINE_DOWNLOAD_BYTES = 250L * 1024L * 1024L
    }
}
