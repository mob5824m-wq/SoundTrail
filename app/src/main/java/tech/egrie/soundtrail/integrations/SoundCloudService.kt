package tech.egrie.soundtrail.integrations

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder

const val SOUNDCLOUD_REDIRECT_URI = "app.soundtrail://soundcloud-auth"

private const val AUTHORIZE_URL = "https://secure.soundcloud.com/authorize"
private const val TOKEN_URL = "https://secure.soundcloud.com/oauth/token"
private const val SIGN_OUT_URL = "https://secure.soundcloud.com/sign-out"
private const val API_BASE = "https://api.soundcloud.com"

class SoundCloudException(message: String) : Exception(message)

/**
 * SoundCloud's OAuth 2.1 Authorization Code + PKCE flow and a small, read-only API client.
 * SoundCloud currently treats every client as confidential, so the token exchange also
 * requires the app's Client Secret; it is stored encrypted and never leaves this device.
 */
class SoundCloudService(context: Context) {
    private val settings = context.getSharedPreferences("soundtrail_settings", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    private val refreshMutex = Mutex()
    private val idPattern = Regex("[A-Za-z0-9]{20,64}")
    private val secretPattern = Regex("[A-Za-z0-9_-]{16,128}")

    val clientId: String get() = settings.getString("soundcloud_client_id", "").orEmpty()
    private val clientSecret: String get() = secrets.get("soundcloud_client_secret").orEmpty()
    val connected: Boolean get() = readToken() != null

    fun beginSignIn(inputId: String, inputSecret: String): Uri {
        val id = inputId.trim()
        val secret = inputSecret.trim()
        when {
            !idPattern.matches(id) ->
                throw SoundCloudException("Enter the Client ID from your SoundCloud API app.")
            !secretPattern.matches(secret) ->
                throw SoundCloudException("Enter the Client Secret from your SoundCloud API app.")
        }
        if (clientId != id || clientSecret != secret) {
            disconnect()
            settings.edit().putString("soundcloud_client_id", id).apply()
            secrets.put("soundcloud_client_secret", secret)
        }
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        secrets.put("sc_pending", JSONObject().apply {
            put("state", state)
            put("verifier", verifier)
            put("clientId", id)
            put("createdAt", System.currentTimeMillis())
        }.toString())
        return Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", id)
            .appendQueryParameter("redirect_uri", SOUNDCLOUD_REDIRECT_URI)
            .appendQueryParameter("code_challenge", Pkce.challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("display", "popup")
            .build()
    }

    fun cancelSignIn() = secrets.remove("sc_pending")

    suspend fun finishSignIn(uri: Uri) {
        if (uri.scheme != "app.soundtrail" || uri.host != "soundcloud-auth" ||
            !uri.path.isNullOrEmpty() || uri.port != -1 || uri.fragment != null
        ) throw SoundCloudException("Unexpected SoundCloud sign-in response.")

        val saved = secrets.get("sc_pending") ?: throw SoundCloudException("Start SoundCloud sign-in again.")
        // The authorization response can be consumed only once, even if it was denied.
        secrets.remove("sc_pending")
        val pending = JSONObject(saved)
        val age = System.currentTimeMillis() - pending.getLong("createdAt")
        if (age !in 0L..600_000L || uri.getQueryParameter("state") != pending.getString("state") ||
            clientId != pending.getString("clientId")
        ) throw SoundCloudException("SoundCloud sign-in expired or could not be verified. Try again.")

        if (uri.getQueryParameter("error") != null) {
            throw SoundCloudException("SoundCloud sign-in was cancelled.")
        }
        val code = uri.getQueryParameter("code")
            ?: throw SoundCloudException("SoundCloud did not return an authorization code.")
        val response = httpRequest(
            TOKEN_URL, "POST",
            body = form(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to SOUNDCLOUD_REDIRECT_URI,
                "client_id" to clientId,
                "client_secret" to requireSecret(),
                "code_verifier" to pending.getString("verifier")
            )
        )
        if (response.code !in 200..299) throw response.error()
        saveToken(parseToken(response.body, clientId))
    }

    /** Clears local tokens and returns the previous access token for a best-effort revocation. */
    fun disconnect(): String? {
        secrets.remove("sc_pending")
        val token = readToken()?.access
        secrets.remove("sc_token")
        return token
    }

    /** Ask SoundCloud to end the session. Failures are ignored; local tokens are already gone. */
    suspend fun revokeAccessToken(token: String) {
        runCatching {
            httpRequest(
                SIGN_OUT_URL, "POST",
                body = JSONObject().put("access_token", token).toString(),
                contentType = "application/json; charset=UTF-8"
            )
        }
    }

    suspend fun profileName(): String {
        val json = JSONObject(apiGet("me"))
        return json.optString("username").takeIf { it.isNotBlank() && it != "null" }
            ?: "SoundCloud listener"
    }

    suspend fun searchTracks(query: String): List<TrackResult> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim().take(100), "UTF-8")
        val json = JSONObject(
            apiGet("tracks?access=playable&limit=10&linked_partitioning=true&q=$encoded")
        )
        val items = json.optJSONArray("collection") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val track = items.optJSONObject(index) ?: return@mapNotNull null
            val title = track.optString("title").ifBlank { return@mapNotNull null }
            // permalink_url comes from the API, but it must still parse as a safe link.
            val link = MusicLinkParser.parse(track.optString("permalink_url")) ?: return@mapNotNull null
            val artist = track.optString("metadata_artist").ifBlank {
                track.optJSONObject("user")?.optString("username").orEmpty()
            }.ifBlank { "Unknown artist" }
            // Try to extract a direct stream candidate from the search payload to allow instant in-app playback.
            val playable = extractPlayableFromTrackJson(track)
            TrackResult(
                title = title.take(120),
                artist = artist,
                url = link.url,
                provider = MusicProvider.SOUNDCLOUD,
                playableUrl = playable
            )
        }.distinctBy { it.url }
    }

    /** Attempts to resolve a SoundCloud permalink to a direct streaming URL for in-app playback. */
    suspend fun resolvePlayableUrl(link: MusicLink): String? {
        if (link.provider != MusicProvider.SOUNDCLOUD) return null
        val permalink = link.url
        // First try to resolve via /resolve endpoint to get the track id.
        val trackId = try {
            val encoded = URLEncoder.encode(permalink, "UTF-8")
            val resolved = JSONObject(apiGet("resolve?url=$encoded"))
            resolved.optLong("id", 0L).takeIf { it > 0 }?.toString()
                ?: resolved.optString("id").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        if (trackId != null) {
            resolveStreamForTrackId(trackId)?.let { return it }
        }
        // Fallback: if permalink itself contains an id-like path, try to extract via API again using search.
        return null
    }

    private suspend fun resolveStreamForTrackId(trackId: String): String? {
        // Attempt 1: legacy streams endpoint
        try {
            val streams = JSONObject(apiGet("tracks/$trackId/streams"))
            streams.optString("http_mp3_128_url").takeIf { it.isNotBlank() }?.let { return it }
            streams.optString("hls_mp3_128_url").takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Exception) {
            // Continue to transcodings path
        }
        // Attempt 2: media.transcodings
        return try {
            val track = JSONObject(apiGet("tracks/$trackId"))
            val media = track.optJSONObject("media") ?: return null
            val transcodings = media.optJSONArray("transcodings") ?: return null
            var progressiveUrl: String? = null
            var hlsUrl: String? = null
            for (i in 0 until transcodings.length()) {
                val t = transcodings.optJSONObject(i) ?: continue
                val format = t.optJSONObject("format") ?: continue
                val protocol = format.optString("protocol")
                val mime = format.optString("mime_type")
                val url = t.optString("url")
                if (url.isBlank()) continue
                if (protocol == "progressive" && mime.contains("mpeg")) {
                    progressiveUrl = url
                    break
                }
                if (hlsUrl == null && protocol == "hls") {
                    hlsUrl = url
                }
            }
            val chosen = progressiveUrl ?: hlsUrl ?: return null
            // This transcoding url needs to be fetched with auth to get the actual stream url
            val resolved = JSONObject(apiGet(chosen.removePrefix("$API_BASE/")))
            resolved.optString("url").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractPlayableFromTrackJson(track: JSONObject): String? {
        // Some search payloads already include stream_url or media transcodings with direct urls.
        track.optString("stream_url").takeIf { it.isNotBlank() }?.let { return it }
        track.optJSONObject("media")?.optJSONArray("transcodings")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val format = t.optJSONObject("format")
                if (format?.optString("protocol") == "progressive") {
                    t.optString("url").takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return null
    }

    private suspend fun apiGet(path: String): String {
        val first = httpRequest("$API_BASE/$path", authorization = "OAuth ${accessToken()}")
        if (first.code in 200..299) return first.body
        if (first.code == 401) {
            // A token can be revoked before its reported expiry. Refresh once, never loop.
            val retry = httpRequest(
                "$API_BASE/$path", authorization = "OAuth ${accessToken(forceRefresh = true)}"
            )
            if (retry.code in 200..299) return retry.body
            throw retry.error()
        }
        throw first.error()
    }

    private suspend fun accessToken(forceRefresh: Boolean = false): String = refreshMutex.withLock {
        val old = readToken() ?: throw SoundCloudException("Connect SoundCloud to search for tracks.")
        if (!forceRefresh && old.expiresAt > System.currentTimeMillis() + 60_000) {
            return@withLock old.access
        }
        val response = httpRequest(
            TOKEN_URL, "POST",
            body = form(
                "grant_type" to "refresh_token",
                "refresh_token" to old.refresh,
                "client_id" to old.clientId,
                "client_secret" to requireSecret()
            )
        )
        if (response.code !in 200..299) {
            if (response.code == 400 || response.code == 401) {
                disconnect()
                throw SoundCloudException("SoundCloud session expired. Connect again.")
            }
            throw response.error()
        }
        // Refresh tokens are single-use; the response carries the next one.
        val refreshed = parseToken(response.body, old.clientId, old.refresh)
        saveToken(refreshed)
        refreshed.access
    }

    private fun requireSecret(): String = clientSecret.ifBlank {
        throw SoundCloudException("Your SoundCloud Client Secret is missing. Connect again.")
    }

    private fun readToken(): SoundCloudToken? {
        val raw = secrets.get("sc_token") ?: return null
        return runCatching {
            val json = JSONObject(raw)
            SoundCloudToken(
                json.getString("access"), json.getString("refresh"),
                json.getString("clientId"), json.getLong("expiresAt")
            )
        }.getOrElse {
            secrets.remove("sc_token")
            null
        }
    }

    private fun saveToken(token: SoundCloudToken) {
        secrets.put("sc_token", JSONObject().apply {
            put("access", token.access)
            put("refresh", token.refresh)
            put("clientId", token.clientId)
            put("expiresAt", token.expiresAt)
        }.toString())
    }

    private fun parseToken(body: String, id: String, previousRefresh: String? = null): SoundCloudToken {
        val json = JSONObject(body)
        val access = json.optString("access_token")
        if (access.isBlank()) throw SoundCloudException("SoundCloud did not return an access token.")
        return SoundCloudToken(
            access = access,
            refresh = json.optString("refresh_token").ifBlank {
                previousRefresh ?: throw SoundCloudException("SoundCloud did not return a refresh token.")
            },
            clientId = id,
            expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3_600L) * 1_000L
        )
    }

    private data class SoundCloudToken(
        val access: String, val refresh: String, val clientId: String, val expiresAt: Long
    )

    private fun HttpResponse.error(): SoundCloudException {
        val detail = detail()
        val fallback = when (code) {
            400 -> "SoundCloud rejected this request. Check the Client ID and Secret."
            401 -> "SoundCloud authorization failed. Reconnect your account."
            403 -> "SoundCloud denied access. Check your API app's permissions."
            429 -> "SoundCloud is rate-limiting requests. Try again later."
            else -> "SoundCloud is unavailable (HTTP $code). Try again later."
        }
        return SoundCloudException(detail.takeIf { it.isNotBlank() && code != 403 } ?: fallback)
    }
}
