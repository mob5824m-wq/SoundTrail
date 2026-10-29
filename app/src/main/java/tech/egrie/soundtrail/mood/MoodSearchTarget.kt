package tech.egrie.soundtrail.mood

import tech.egrie.soundtrail.integrations.MusicProvider

/**
 * Where a mood reading goes looking for music.
 *
 * A reading always looks on the listener's own Jellyfin server: the place the music they can
 * actually play lives, searched by name and played here. Nothing is left to choose, so nothing on
 * the Mood tab asks — a request that says where to look is read for its words, not its choice of
 * service. [LIBRARY] stays because "find music in your own library" is still a sentence the parser
 * understands, but no reading is sent there any more.
 *
 * The streaming-service choices this list used to carry were removed with those services — a
 * reading that could only answer "here is a row that opens someone else's app" was never really an
 * answer, and nothing that plays here depended on it.
 */
internal enum class MoodSearchTarget(
    val id: String,
    /** The short name this choice reads as on screen. */
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
        /**
         * Where every reading looks: the listener's Jellyfin server. There is no setting behind
         * this, so an install that has never connected a server still asks one for its library
         * rather than quietly answering from a shorter shelf.
         */
        val default: MoodSearchTarget = JELLYFIN

        /**
         * The request itself, in the words the reading asked for: "find music on your Jellyfin
         * server that fits: …". This is the first line the model reads.
         */
        fun request(text: String, target: MoodSearchTarget): String =
            "find music ${target.phrase} that fits: \"" + text.trim() + "\""
    }

    /**
     * The sentence to show while the connection this choice searches has not been made yet — or
     * null when it is ready, or needs no connection at all.
     *
     * Readings always look on Jellyfin, so this is not about a choice being picked: it is what the
     * Mood tab says up front, so the listener never spends a mood description finding out that
     * there is no server to search.
     */
    fun missingConnection(jellyfin: Boolean): String? = when (this) {
        JELLYFIN -> if (jellyfin) null else "No Jellyfin server is connected yet — connect one " +
            "under Connections so its library can be searched."
        LIBRARY -> null
    }
}
