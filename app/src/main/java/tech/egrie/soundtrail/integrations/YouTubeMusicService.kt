package tech.egrie.soundtrail.integrations

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.egrie.soundtrail.integrations.youtube.YouTubeMusicApi
import tech.egrie.soundtrail.integrations.youtube.YtStream

/** Resolved YouTube Music stream, ready for [tech.egrie.soundtrail.playback.JellyfinPlayer]. */
data class YouTubeMusicPlayback(
    val urls: List<String>,
    val headers: Map<String, String>,
    val title: String,
    val author: String,
    val artworkUrl: String,
    val durationSec: Long
)

/**
 * Searches and resolves tracks from YouTube Music through public InnerTube endpoints.
 *
 * No account, no sign-in, no cookies. Provider-side restrictions (age-gated, region-locked,
 * bot checks) are surfaced as errors, never bypassed.
 */
class YouTubeMusicService(context: Context) {
    private val api = YouTubeMusicApi(context.applicationContext)

    /** True when YT Music can be queried; always true (no connection required), but kept for symmetry. */
    val available: Boolean get() = true

    suspend fun searchTracks(query: String): List<TrackResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val items = api.search(query.trim().take(120), YouTubeMusicApi.MODE_SONGS)
        items.mapNotNull { item ->
            val id = item.id?.trim().orEmpty()
            if (id.length < 8 || item.isBrowsable) return@mapNotNull null
            val link = YouTubeMusicRef.link(id) ?: return@mapNotNull null
            TrackResult(
                title = item.title?.trim().orEmpty().ifBlank { "Unknown track" }.take(120),
                artist = item.author?.trim().orEmpty().ifBlank { "Unknown artist" }.take(120),
                url = link.url,
                provider = MusicProvider.YOUTUBE_MUSIC,
                durationMs = item.durationSec.takeIf { it > 0 }?.times(1000L)
            )
        }.distinctBy { it.url }
    }

    /** Resolves a video id into a playable stream URL. Throws on failure. */
    suspend fun resolve(videoId: String): YouTubeMusicPlayback = withContext(Dispatchers.IO) {
        val stream: YtStream = api.resolve(videoId)
        if (!stream.hasStream()) throw IllegalStateException("No playable stream for this track.")
        YouTubeMusicPlayback(
            urls = listOf(stream.url),
            headers = stream.headers.toMap(),
            title = stream.title.orEmpty(),
            author = stream.author.orEmpty(),
            artworkUrl = stream.artworkUrl.orEmpty(),
            durationSec = stream.durationSec
        )
    }
}
