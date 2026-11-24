package tech.egrie.soundtrail.integrations

/**
 * YouTube Music tracks are stored as opaque item references (`ytmusic:track:<videoId>`), never as
 * web URLs or API endpoints. The 11-character video id is the only thing persisted; the stream is
 * resolved on demand through public InnerTube endpoints.
 */
internal object YouTubeMusicRef {
    private val idPattern = Regex("[A-Za-z0-9_-]{11}")

    fun isVideoId(value: String): Boolean = idPattern.matches(value)

    /** The link shape Soundtrail stores for a YouTube Music track; null when the id is not plausible. */
    fun link(rawVideoId: String): MusicLink? = rawVideoId.trim()
        .takeIf(::isVideoId)
        ?.let { MusicLink(MusicProvider.YOUTUBE_MUSIC, "ytmusic:track:$it") }

    /** Reads a stored link back into a video id; anything else is refused. */
    fun videoId(value: String): String? {
        val trimmed = value.trim()
        val prefix = "ytmusic:track:"
        if (!trimmed.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return null
        return trimmed.substring(prefix.length).takeIf(::isVideoId)
    }

    /** A plain link to the track in YouTube Music's web client, for sharing or opening. */
    fun webUrl(rawVideoId: String): String? = rawVideoId.trim()
        .takeIf(::isVideoId)
        ?.let { "https://music.youtube.com/watch?v=$it" }
}
