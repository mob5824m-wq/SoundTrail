package tech.egrie.soundtrail.assistant

/**
 * How an answer is put together: what to offer, and what to say about it.
 *
 * Kept apart from the view model so the wording — which is spoken out loud as well as shown — is
 * decided by rules that can be tested, not by whatever the state happened to be at the time.
 */
internal object AssistantAnswer {

    /** How many songs one answer lists: enough for a queue, few enough to read in a car. */
    const val MAX_HITS = 16

    /**
     * The songs the answer offers: the listener's own first — those are the ones that play with
     * no connection at all — then what a search found for the rest, minus anything that is
     * already in the library under another link.
     */
    fun merge(
        mine: List<AssistantHit>,
        found: List<AssistantHit>,
        limit: Int = MAX_HITS
    ): List<AssistantHit> {
        val fresh = found.filterNot { candidate -> mine.any { it.isSameSongAs(candidate) } }
        return (mine + fresh).take(limit)
    }

    /** Trims an answer down to what the player can actually start. */
    fun playable(hits: List<AssistantHit>): List<AssistantHit> = hits.filter { it.playable }

    /**
     * The sentence the app says back. [searched] is whether a catalogue search was attempted at
     * all, and [error] what it said if it failed — a failure with songs in hand is not worth
     * mentioning, but a failure with nothing in hand is the whole answer.
     */
    fun sentence(
        hits: List<AssistantHit>,
        searched: Boolean,
        error: String?
    ): String {
        val playable = playable(hits)
        if (playable.isEmpty()) {
            if (hits.isNotEmpty()) {
                return if (hits.size == 1) {
                    "Found 1 song, but it cannot play on this phone."
                } else {
                    "Found ${hits.size} songs, but none of them can play on this phone."
                }
            }
            return when {
                error != null -> error
                searched -> "Nothing matched that, in your library or on your Jellyfin server."
                else -> "Nothing in your library matches that. Connect your Jellyfin server on " +
                    "the Connect tab to search its library as well."
            }
        }
        val first = playable.first()
        val opening = "Playing ${first.title}${first.artist.takeIf(String::isNotBlank)?.let { " by $it" }.orEmpty()}"
        val more = playable.size - 1
        val rest = when {
            more <= 0 -> "."
            more == 1 -> ", then 1 more."
            else -> ", then $more more."
        }
        return opening + rest
    }

    /** What to say out loud: the same sentence, without the count when there is nothing to add. */
    fun spoken(sentence: String): String = sentence

    /**
     * True when the two rows are the same song. Titles match exactly or one contains the other,
     * and artists must be unknown on one side or agree — which keeps "Sunsets" by The xx from
     * appearing twice (once from the library, once from the server) without hiding a cover.
     */
    private fun AssistantHit.isSameSongAs(other: AssistantHit): Boolean {
        val mine = title.lowercase().trim()
        val theirs = other.title.lowercase().trim()
        if (mine.isBlank() || theirs.isBlank()) return false
        val sameTitle = mine == theirs ||
            (mine.length >= 4 && theirs.length >= 4 && (mine.contains(theirs) || theirs.contains(mine)))
        if (!sameTitle) return false
        val mineArtist = artist.lowercase().trim()
        val theirArtist = other.artist.lowercase().trim()
        if (mineArtist.isBlank() || theirArtist.isBlank()) return true
        return mineArtist.contains(theirArtist) || theirArtist.contains(mineArtist)
    }
}
