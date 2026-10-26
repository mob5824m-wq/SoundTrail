package tech.egrie.soundtrail.mood

import tech.egrie.soundtrail.integrations.MusicProvider

/**
 * Where a mood reading goes looking for music.
 *
 * Two choices, and both are things the listener actually has: their own library, which needs no
 * network, no account and no key, and their own Jellyfin server, which needs a connection they
 * made themselves. The streaming-service choices this list used to carry were removed with those
 * services — a reading that could only answer "here is a row that opens someone else's app" was
 * never really an answer, and nothing that plays here depended on it.
 */
internal enum class MoodSearchTarget(
    val id: String,
    /** The short name on the picker pills. */
    val short: String,
    /** The name the model is given, and the name shown back to the listener. */
    val label: String,
    /** How the request reads: "find music **in your own library** that fits …". */
    val phrase: String,
    /** The provider its results come from; null for the listener's own pins and playlists. */
    val provider: MusicProvider?,
    val blurb: String,
    /**
     * What this choice is searching: the thing that has to be connected before it can find
     * anything. Null for the choice that needs no connection of its own.
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

        /**
         * Never throws: an unknown or blank id means the listener's own library. An id saved by an
         * older version for a service that is gone ("youtube", "spotify", "soundcloud") lands here
         * too, so a stored preference can never select a choice that no longer exists.
         */
        fun from(id: String?): MoodSearchTarget =
            values().firstOrNull { it.id == id?.trim()?.lowercase() } ?: default

        /**
         * The choice that searches a given service, for a request that named one out loud —
         * "find music on Jellyfin that fits …". A service no choice searches (or none at all)
         * means the listener's own library.
         */
        fun forProvider(provider: MusicProvider?): MoodSearchTarget =
            values().firstOrNull { it.provider == provider } ?: default

        /**
         * The request itself, in the words the listener asked for: "find music in your own library
         * that fits: …". This is the first line the model reads, and the same line the Mood tab
         * shows back so the listener can see exactly what was asked on their behalf.
         */
        fun request(text: String, target: MoodSearchTarget): String =
            "find music ${target.phrase} that fits: \"" + text.trim() + "\""
    }

    /**
     * The sentence to show the moment this choice is picked, when the connection it searches has
     * not been made yet — or null when the choice is ready, or needs no connection at all.
     *
     * Picking an unconnected server is allowed: it still says where the next reading should look.
     * Saying so at the tap means the listener never spends a mood description finding out.
     */
    fun missingConnection(jellyfin: Boolean): String? = when (this) {
        JELLYFIN -> if (jellyfin) null else "No Jellyfin server is connected yet — connect one " +
            "under Connections so its library can be searched."
        LIBRARY -> null
    }
}
