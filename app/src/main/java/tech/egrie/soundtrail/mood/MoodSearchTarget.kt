package tech.egrie.soundtrail.mood

import tech.egrie.soundtrail.integrations.MusicProvider

/**
 * Where a mood reading goes looking for music.
 *
 * The listener's own library is the default and needs nothing — those songs are already here, and
 * an offline copy plays with no network at all. Every other choice names a provider the listener
 * may have connected, and each is honest about what this app can then do with a result: play it
 * through the one player, or hand it to the app that owns it. YouTube Music is the exception in
 * the list on purpose — it has no public search API — so its rows open that app instead of
 * pretending a song can be played here.
 */
internal enum class MoodSearchTarget(
    val id: String,
    /** The short name on the picker pills. */
    val short: String,
    /** The name the model is given, and the name shown back to the listener. */
    val label: String,
    /** How the request reads: "find music **on YouTube** that fits …". */
    val phrase: String,
    /** The provider its results come from; null for the listener's own pins and playlists. */
    val provider: MusicProvider?,
    val blurb: String,
    /**
     * What this choice is searching: the thing that has to be connected before it can find
     * anything. Null for the two choices that need no connection of their own.
     */
    val needs: String? = null
) {
    LIBRARY(
        id = "library",
        short = "My library",
        label = "your own library",
        phrase = "in your own library",
        provider = null,
        blurb = "The songs you have already pinned or put in a playlist, offline copies included, " +
            "picked by name instead of by score. This is the one choice that needs no network, no " +
            "account, and no key."
    ),

    YOUTUBE(
        id = "youtube",
        short = "YouTube",
        label = "YouTube",
        phrase = "on YouTube",
        provider = MusicProvider.YOUTUBE,
        needs = "a connected Google account",
        blurb = "YouTube's music catalogue, which needs a connected Google account. Each song is " +
            "searched for and the best match plays here through the same player as everything else."
    ),

    YOUTUBE_MUSIC(
        id = "youtube_music",
        short = "YouTube Music",
        label = "YouTube Music",
        phrase = "on YouTube Music",
        provider = MusicProvider.YOUTUBE_MUSIC,
        blurb = "YouTube Music has no public search API, so these rows open the song in the " +
            "YouTube Music app rather than playing here. Nothing is invented to look otherwise."
    ),

    SPOTIFY(
        id = "spotify",
        short = "Spotify",
        label = "Spotify",
        phrase = "on Spotify",
        provider = MusicProvider.SPOTIFY,
        needs = "a connected Spotify account",
        blurb = "Spotify's catalogue, which needs a connected account. Full songs belong to " +
            "Spotify's own app; the 30-second preview plays here."
    ),

    SOUNDCLOUD(
        id = "soundcloud",
        short = "SoundCloud",
        label = "SoundCloud",
        phrase = "on SoundCloud",
        provider = MusicProvider.SOUNDCLOUD,
        needs = "a connected SoundCloud account",
        blurb = "SoundCloud's catalogue, which needs a connected account. Public tracks stream " +
            "here through the same player as everything else."
    ),

    JELLYFIN(
        id = "jellyfin",
        short = "Jellyfin",
        label = "Jellyfin",
        phrase = "on your Jellyfin server",
        provider = MusicProvider.JELLYFIN,
        needs = "a connected Jellyfin server",
        blurb = "Your own Jellyfin server's library, searched by name and played here. The " +
            "server's permissions stay in charge of what comes back."
    );

    companion object {
        /** What a fresh install looks for: the songs it already has. */
        val default: MoodSearchTarget = LIBRARY

        /** Never throws: an unknown or blank id means the listener's own library. */
        fun from(id: String?): MoodSearchTarget =
            values().firstOrNull { it.id == id?.trim()?.lowercase() } ?: default

        /**
         * The choice that searches a given service, for a request that named one out loud —
         * "find music on Spotify that fits …". A service no choice searches (or none at all)
         * means the listener's own library.
         */
        fun forProvider(provider: MusicProvider?): MoodSearchTarget =
            values().firstOrNull { it.provider == provider } ?: default

        /**
         * The request itself, in the words the listener asked for: "find music on YouTube that
         * fits: …". This is the first line the model reads, and the same line the Mood tab shows
         * back so the listener can see exactly what was asked on their behalf.
         */
        fun request(text: String, target: MoodSearchTarget): String =
            "find music ${target.phrase} that fits: \"" + text.trim() + "\""
    }

    /**
     * The sentence to show the moment this choice is picked, when the connection it searches has
     * not been made yet — or null when the choice is ready, or needs no connection at all.
     *
     * Picking an unconnected provider is allowed: it still says where the next reading should
     * look. Saying so at the tap means the listener never spends a mood description finding out.
     */
    fun missingConnection(
        spotify: Boolean,
        soundcloud: Boolean,
        jellyfin: Boolean,
        youtube: Boolean
    ): String? = when (this) {
        SPOTIFY -> if (spotify) null else "Spotify is not connected yet — connect it under " +
            "Connections so its catalogue can be searched."
        SOUNDCLOUD -> if (soundcloud) null else "SoundCloud is not connected yet — connect it " +
            "under Connections so its catalogue can be searched."
        JELLYFIN -> if (jellyfin) null else "No Jellyfin server is connected yet — connect one " +
            "under Connections so its library can be searched."
        YOUTUBE -> if (youtube) null else "No Google account is connected yet — connect YouTube " +
            "under Connections so its catalogue can be searched."
        LIBRARY, YOUTUBE_MUSIC -> null
    }
}
