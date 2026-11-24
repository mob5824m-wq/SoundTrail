package tech.egrie.soundtrail.integrations

/**
 * The catalogues Soundtrail can hold a song from.
 *
 * A song is either a stream Soundtrail can resolve and play itself (Jellyfin, YouTube Music),
 * or an audio file the listener attached to a pin/playlist. Old Spotify/SoundCloud/YouTube
 * *web* links that an earlier version stored are not playable and are refused by
 * [MusicLinkParser]; [tech.egrie.soundtrail.data.LegacyServiceCleanup] strips those legacy rows.
 */
enum class MusicProvider(val label: String) {
    /** A song on the user's own Jellyfin server; played by Soundtrail itself, not an app. */
    JELLYFIN("Jellyfin"),
    /** A song from YouTube Music, resolved through public InnerTube endpoints (no sign-in). */
    YOUTUBE_MUSIC("YouTube Music")
}

data class MusicLink(val provider: MusicProvider, val url: String)

/** A search result from the connected catalogue, ready to be pinned. */
data class TrackResult(
    val title: String,
    val artist: String,
    val url: String,
    val provider: MusicProvider,
    val durationMs: Long? = null
)

/**
 * Reads a stored or typed song link.
 *
 * Accepted shapes are provider item references that carry only an opaque id — never a server
 * address, user, or token: `jellyfin:item:<id>` and `ytmusic:track:<videoId>`. Everything else
 * — including https links to streaming services that an older install stored — is refused, so a
 * caller can never mistake a dead link for a playable one.
 */
object MusicLinkParser {
    fun fromText(text: String): MusicLink? = parse(text)

    fun parse(input: String): MusicLink? =
        JellyfinRef.itemId(input)?.let { itemId -> JellyfinRef.link(itemId) }
            ?: YouTubeMusicRef.videoId(input)?.let { id -> YouTubeMusicRef.link(id) }

    /**
     * True when a stored link is one this version cannot play.
     *
     * A link is either a recognized item reference or it is nothing: a Spotify, SoundCloud or
     * legacy YouTube https URL left behind by an older install reads as unplayable here, and so
     * does anything that was never a song link at all. Callers use this to decide what survives an
     * upgrade — see [tech.egrie.soundtrail.data.LegacyServiceCleanup]. A row keeps an audio file
     * the listener attached themselves and loses the dead link; a row whose only content was the
     * dead link goes away, because keeping it would show a song that can never play.
     */
    fun isUnplayable(url: String?): Boolean = url != null && parse(url) == null
}
