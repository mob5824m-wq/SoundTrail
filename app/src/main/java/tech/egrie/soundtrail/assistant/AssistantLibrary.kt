package tech.egrie.soundtrail.assistant

import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.Playlist

/**
 * One row of an answer: something that can be played, plus where it came from.
 *
 * A hit is either one of the listener's own stored songs ([itemId] — a pin or a playlist item,
 * so an offline copy plays exactly as it would from the lists) or a song a search turned up
 * ([url] — the link the player resolves). [musicUrl] is the same song in the YouTube Music app,
 * which is the way to hear a catalogue hit in full without the phone's own extraction.
 */
internal data class AssistantHit(
    val title: String,
    val artist: String,
    val provider: tech.egrie.soundtrail.integrations.MusicProvider?,
    /** One line under the title: where this came from. */
    val detail: String,
    /** The stored pin or playlist item, when the hit is one of the listener's own. */
    val itemId: String? = null,
    /** The song's link, when the hit came from a search. */
    val url: String? = null,
    /** The same song in the YouTube Music app, when there is such a link. */
    val musicUrl: String? = null,
    /** The name of the attached offline copy, when the stored song has one. */
    val localFile: String? = null
) {
    /** True when the player has something to work with. */
    val playable: Boolean
        get() = !itemId.isNullOrBlank() || !url.isNullOrBlank()
}

/**
 * What the listener already owns, matched against what they asked for.
 *
 * This is what makes asking work with no Google connection, no network, and no server: the same
 * words are matched against the pins on the map and the songs in every playlist, offline copies
 * included. All the words have to appear somewhere in the song — matching any one of them would
 * make "the xx sunsets" answer with every song that has a "the" in it.
 */
internal object AssistantLibrary {

    /** How many of the listener's own songs one answer offers. */
    const val MAX_HITS = 12

    /**
     * Words that say how a request was phrased rather than what to look for. "Play the song from
     * Toronto" has to match a pin in Toronto, and the only way "from" does that is by being
     * ignored. A song whose title is nothing but these words is not reachable by asking for it.
     */
    private val fillerWords = setOf(
        "the", "a", "an", "of", "from", "in", "at", "on", "for", "to", "my", "me", "that",
        "this", "song", "songs", "track", "tracks", "tune", "music"
    )

    fun search(request: AssistantRequest, pins: List<MusicPin>, playlists: List<Playlist>): List<AssistantHit> {
        val words = request.phrase
            .lowercase()
            .split(' ')
            .filter(String::isNotBlank)
            .filterNot { it in fillerWords }
        if (words.isEmpty()) return emptyList()

        val fromPins = pins.filter { matches(words, it.title, it.artist, it.place) }.map { pin ->
            AssistantHit(
                title = pin.title.ifBlank { "Untitled song" },
                artist = pin.artist,
                provider = pin.provider,
                detail = detailOf(pin.place, null),
                itemId = pin.id,
                url = pin.url.takeIf(String::isNotBlank)
            )
        }

        val fromPlaylists = playlists.flatMap { playlist ->
            playlist.items
                .filter { matches(words, it.title, it.artist, playlist.name) }
                .map { item ->
                    AssistantHit(
                        title = item.title.ifBlank { "Untitled song" },
                        artist = item.artist,
                        provider = item.provider,
                        detail = detailOf(playlist.name, item.localFile),
                        itemId = item.id,
                        url = item.url,
                        localFile = item.localFile
                    )
                }
        }

        // Pins first: they are the songs the listener put somewhere on purpose.
        return (fromPins + fromPlaylists).distinctBy { it.itemId }.take(MAX_HITS)
    }

    private fun matches(words: List<String>, title: String, artist: String, place: String?): Boolean {
        val haystack = "$title $artist ${place.orEmpty()}".lowercase()
        return words.all { haystack.contains(it) }
    }

    /** "Offline copy · Road trip" reads better than two lines that repeat each other. */
    private fun detailOf(where: String?, localFile: String?): String {
        val offline = if (localFile.isNullOrBlank()) null else "Offline copy"
        val place = where?.takeIf(String::isNotBlank)
        return listOfNotNull(offline, place).joinToString(" · ").ifBlank { "Your library" }
    }
}
