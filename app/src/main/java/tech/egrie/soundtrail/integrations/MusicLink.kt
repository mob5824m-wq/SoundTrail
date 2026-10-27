package tech.egrie.soundtrail.integrations

/**
 * The catalogues Soundtrail can hold a song from.
 *
 * Jellyfin is the only one left. Spotify, SoundCloud and YouTube were removed: their links
 * cannot be resolved, streamed or handed to another app any more, so accepting one would mean
 * storing a row that looks playable and is not. [MusicLinkParser] therefore refuses every link
 * shape it used to accept, and [tech.egrie.soundtrail.data.LegacyServiceCleanup] strips the rows
 * an older version already saved.
 */
enum class MusicProvider(val label: String) {
    /** A song on the user's own Jellyfin server; played by Soundtrail itself, not an app. */
    JELLYFIN("Jellyfin")
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
 * The only accepted shape is a Jellyfin item reference (`jellyfin:item:<id>`), which carries an
 * item id and nothing else: no server address, no user, no token. Everything else — including
 * the https links this parser used to canonicalize for streaming services — is refused, so a
 * caller can never mistake a dead link for a playable one.
 */
object MusicLinkParser {
    fun fromText(text: String): MusicLink? = parse(text)

    fun parse(input: String): MusicLink? =
        JellyfinRef.itemId(input)?.let { itemId -> JellyfinRef.link(itemId) }

    /**
     * True when a stored link is one this version cannot play.
     *
     * A link is either a Jellyfin item reference or it is nothing: a Spotify, SoundCloud or
     * YouTube URL left behind by an older install reads as unplayable here, and so does anything
     * that was never a song link at all. Callers use this to decide what survives an upgrade —
     * see [tech.egrie.soundtrail.data.LegacyServiceCleanup]. A row keeps an audio file the
     * listener attached themselves and loses the dead link; a row whose only content was the dead
     * link goes away, because keeping it would show a song that can never play.
     */
    fun isUnplayable(url: String?): Boolean = url != null && parse(url) == null
}
