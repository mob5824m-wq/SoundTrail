package tech.egrie.soundtrail.scrobble

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

/**
 * Minimal ListenBrainz API client (https://api.listenbrainz.org/1/).
 *
 * Only validate-token and submit-listens are used. Plain HttpURLConnection + org.json, no extra deps.
 */
class ListenBrainzApi(context: Context) {
    data class TokenResult(val valid: Boolean, val userName: String, val message: String)

    class HttpFailure(val code: Int, message: String, val retryAfterSec: Long) : IOException(message)

    private val version: String = readVersion(context)
    private val userAgent: String = "Soundtrail/$version ( https://github.com/mob5824m-wq/SoundTrail )"

    fun validateToken(token: String): TokenResult {
        return try {
            val body = JSONObject(request(token, "GET", "validate-token", null))
            TokenResult(
                valid = body.optBoolean("valid", false),
                userName = body.optString("user_name", ""),
                message = body.optString("message", "")
            )
        } catch (e: JSONException) {
            throw IOException("Unexpected answer from ListenBrainz: ${e.message}")
        } catch (e: HttpFailure) {
            if (e.code == 401 || e.code == 400) TokenResult(false, "", e.message.orEmpty()) else throw e
        }
    }

    fun submitPlayingNow(token: String, listen: Scrobble) {
        val payload = JSONArray().put(trackMetadataOnly(listen))
        request(token, "POST", "submit-listens", envelope("playing_now", payload))
    }

    fun submitSingle(token: String, listen: Scrobble) {
        val payload = JSONArray().put(withTimestamp(listen))
        request(token, "POST", "submit-listens", envelope("single", payload))
    }

    fun submitImport(token: String, listens: List<Scrobble>) {
        val payload = JSONArray()
        for (listen in listens) {
            if (listen.isSubmittable()) payload.put(withTimestamp(listen))
        }
        if (payload.length() == 0) return
        request(token, "POST", "submit-listens", envelope("import", payload))
    }

    private fun envelope(type: String, payload: JSONArray): JSONObject = try {
        JSONObject().put("listen_type", type).put("payload", payload)
    } catch (e: JSONException) {
        throw IOException("Could not build listen payload: ${e.message}")
    }

    private fun withTimestamp(listen: Scrobble): JSONObject {
        val o = trackMetadataOnly(listen)
        try {
            o.put("listened_at", maxOf(0L, listen.listenedAtSec))
        } catch (e: JSONException) {
            throw IOException("Could not build listen payload: ${e.message}")
        }
        return o
    }

    private fun trackMetadataOnly(listen: Scrobble): JSONObject {
        if (!listen.isSubmittable()) throw IOException("Listen is missing artist or title.")
        return try {
            val additional = JSONObject()
                .put("submission_client", CLIENT_NAME)
                .put("submission_client_version", version)
                .put("media_player", CLIENT_NAME)
                .put("media_player_version", version)
            if (listen.musicService.isNotEmpty()) additional.put("music_service", listen.musicService)
            if (listen.originUrl.isNotEmpty()) additional.put("origin_url", listen.originUrl)
            if (listen.durationMs > 0L) additional.put("duration_ms", listen.durationMs)
            if (listen.playedMs > 0L) additional.put("duration_played_ms", listen.playedMs)
            val track = JSONObject()
                .put("artist_name", listen.artist)
                .put("track_name", listen.title)
                .put("additional_info", additional)
            if (listen.release.isNotEmpty()) track.put("release_name", listen.release)
            JSONObject().put("track_metadata", track)
        } catch (e: JSONException) {
            throw IOException("Could not build listen payload: ${e.message}")
        }
    }

    private fun request(token: String, method: String, path: String, body: JSONObject?): String {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) throw HttpFailure(401, "No ListenBrainz token is configured.", 0L)
        waitForRequestSlot()
        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        try {
            connection = URI.create(ROOT + path).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Token $cleanToken")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", userAgent)
            if (body != null) {
                val payload = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(payload.size)
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                val output: OutputStream = connection.outputStream
                try {
                    output.write(payload)
                    output.flush()
                } finally {
                    output.close()
                }
            }
            val code = connection.responseCode
            input = if (code >= 400) connection.errorStream else connection.inputStream
            val text = String(readFully(input), Charsets.UTF_8)
            if (code < 200 || code >= 300) {
                val detail = summarize(text)
                if (code == 429) {
                    throw HttpFailure(code, "ListenBrainz rate limit reached" + if (detail.isNotEmpty()) ": $detail" else "", retryAfter(connection))
                }
                if (code == 401) {
                    throw HttpFailure(code, "ListenBrainz rejected the token" + if (detail.isNotEmpty()) ": $detail" else "", 0L)
                }
                throw HttpFailure(code, "ListenBrainz HTTP $code" + if (detail.isNotEmpty()) ": $detail" else "", 0L)
            }
            return text
        } finally {
            try { input?.close() } catch (_: IOException) {}
            connection?.disconnect()
        }
    }

    companion object {
        const val ROOT = "https://api.listenbrainz.org/1/"
        const val CLIENT_NAME = "Soundtrail"
        private const val TIMEOUT_MS = 15000
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val MIN_REQUEST_SPACING_MS = 1100L
        private val REQUEST_SPACING_LOCK = Any()
        @Volatile private var lastRequestStartedMs: Long = 0L

        fun waitForRequestSlot() {
            synchronized(REQUEST_SPACING_LOCK) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("ListenBrainz request cancelled")
                val elapsed = SystemClock.elapsedRealtime() - lastRequestStartedMs
                val wait = MIN_REQUEST_SPACING_MS - elapsed
                if (wait > 0L) {
                    try {
                        Thread.sleep(wait)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        val cancelled = InterruptedIOException("ListenBrainz request cancelled")
                        cancelled.initCause(e)
                        throw cancelled
                    }
                }
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("ListenBrainz request cancelled")
                lastRequestStartedMs = SystemClock.elapsedRealtime()
            }
        }

        private fun retryAfter(connection: HttpURLConnection): Long {
            var seconds = parseSeconds(connection.getHeaderField("Retry-After"))
            if (seconds <= 0L) seconds = parseSeconds(connection.getHeaderField("X-RateLimit-Reset-In"))
            return if (seconds > 0L) minOf(seconds, 120L) else 2L
        }

        private fun parseSeconds(value: String?): Long {
            if (value == null) return 0L
            return try { value.trim().toLong() } catch (_: NumberFormatException) { 0L }
        }

        private fun summarize(text: String?): String {
            if (text == null) return ""
            var compact = text.trim().replace(Regex("\\s+"), " ")
            if (compact.length > 200) compact = compact.substring(0, 200) + "…"
            return compact
        }

        private fun readFully(input: InputStream?): ByteArray {
            if (input == null) return ByteArray(0)
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                buffer.write(chunk, 0, read)
                if (buffer.size() >= MAX_RESPONSE_BYTES) break
            }
            return buffer.toByteArray()
        }

        private fun readVersion(context: Context): String = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "dev"
        } catch (_: Exception) { "dev" }
    }
}
