package tech.egrie.soundtrail.integrations

import org.json.JSONObject
import java.net.URLEncoder

/** TheAudioDB's documented shared free key; premium keys use the same URL format. */
const val AUDIO_DB_FREE_KEY = "123"

private const val API_BASE = "https://www.theaudiodb.com/api/v1/json"

class AudioDbException(message: String) : Exception(message)

/**
 * Metadata-only song details.
 *
 * TheAudioDB also publishes a Spotify id and a music-video URL per track; this app no longer
 * reads either, because it no longer supports those services and a link it cannot play would be
 * a promise it cannot keep. What is left is the part that was always the point: the names and the
 * tags that prefill a pin and enrich a mood mix.
 */
data class SongInfo(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val mood: String
)

/**
 * TheAudioDB's free music API: no account, no secret, nothing stored. It is a metadata
 * source only — it never provides playback, so results merely prefill a pin.
 */
class AudioDbService {
    suspend fun searchTracks(artist: String, title: String): List<SongInfo> {
        if (artist.isBlank() || title.isBlank()) {
            throw AudioDbException("Enter both artist and song title.")
        }
        val a = URLEncoder.encode(artist.trim().take(80), "UTF-8")
        val t = URLEncoder.encode(title.trim().take(80), "UTF-8")
        val response = httpRequest("$API_BASE/$AUDIO_DB_FREE_KEY/searchtrack.php?s=$a&t=$t")
        if (response.code !in 200..299) throw response.error()
        return AudioDbParser.parse(response.body)
    }
}

internal object AudioDbParser {

    fun parse(body: String): List<SongInfo> = runCatching {
        val items = JSONObject(body).optJSONArray("track") ?: return emptyList()
        (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val title = item.text("strTrack") ?: return@mapNotNull null
            val artist = item.text("strArtist") ?: return@mapNotNull null
            SongInfo(
                title = title.take(120),
                artist = artist,
                album = item.text("strAlbum").orEmpty(),
                genre = item.text("strGenre").orEmpty(),
                mood = item.text("strMood").orEmpty()
            )
        }.distinctBy { "${it.title}|${it.artist}|${it.album}".lowercase() }.take(12)
    }.getOrDefault(emptyList())

    /**
     * Android and JVM org.json builds disagree on how JSON null reads back through
     * optString; this treats blank and literal "null" as absent on both.
     */
    private fun JSONObject.text(name: String): String? = optString(name)
        .takeIf { it.isNotBlank() && it != "null" }
}

private fun HttpResponse.error(): AudioDbException {
    val detail = detail()
    val fallback = when (code) {
        404 -> "TheAudioDB's shared free key is inactive right now. Check their API docs for the current free key."
        429 -> "TheAudioDB is rate-limiting the shared free key. Try again in a moment."
        503 -> "TheAudioDB is temporarily unavailable. Try again shortly."
        else -> "TheAudioDB is unavailable (HTTP $code). Try again later."
    }
    return AudioDbException(detail.takeIf { it.isNotBlank() && code != 404 } ?: fallback)
}
