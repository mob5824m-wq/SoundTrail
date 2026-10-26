package tech.egrie.soundtrail.mood

import tech.egrie.soundtrail.assistant.AssistantHit
import tech.egrie.soundtrail.assistant.AssistantLibrary
import tech.egrie.soundtrail.assistant.AssistantRequest
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.Playlist
import tech.egrie.soundtrail.integrations.JellyfinService
import tech.egrie.soundtrail.integrations.TrackResult

/** What one search for a mood produced: rows, and an honest note about the rest. */
internal data class MoodPickSearch(
    val hits: List<AssistantHit> = emptyList(),
    /** The provider's own complaint, when the search could not run at all. */
    val error: String? = null,
    /** Songs the model named that no search could match to anything real. */
    val unmatched: Int = 0
)

/**
 * Turns what a model said into what the app can play.
 *
 * A model's answer is a suggestion, never a source. Every song it names is searched for on the
 * listener's own Jellyfin server — or matched against the songs they already have — and only what
 * that search really returns becomes a row. That is what makes "find music on Jellyfin that fits
 * this" honest: the app is holding a real result with a real link, not repeating a model's guess.
 * A song that cannot be matched is counted and reported rather than quietly played as something
 * else.
 *
 * The same path answers a request with no model at all: when the on-device reading supplies only
 * search phrases, those phrases are looked up on the chosen target instead.
 */
internal class MoodPickResolver(
    private val jellyfin: JellyfinService
) {

    /** Resolves the songs a model named. */
    suspend fun fromPicks(
        picks: List<MoodAiPick>,
        target: MoodSearchTarget,
        pins: List<MusicPin>,
        playlists: List<Playlist>
    ): MoodPickSearch {
        if (picks.isEmpty()) return MoodPickSearch()
        if (target == MoodSearchTarget.LIBRARY) {
            val hits = picks.mapNotNull { pick -> libraryHit(pick, pins, playlists) }
            return MoodPickSearch(hits = hits, unmatched = picks.size - hits.size)
        }
        var failure: String? = null
        var unmatched = 0
        val hits = mutableListOf<AssistantHit>()
        picks.forEach { pick ->
            val phrase = MoodPickMatch.phrase(pick.title, pick.artist)
            val results = try {
                search(phrase, target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // One dead connection is one reason, not one per song.
                failure = failure ?: (e.message ?: "${target.short} could not be searched.")
                emptyList()
            }
            val match = MoodPickMatch.best(pick.title, pick.artist, results)
            if (match == null) {
                unmatched++
            } else {
                hits += providerHit(match, pick, target)
            }
        }
        return MoodPickSearch(hits = hits, error = failure, unmatched = unmatched)
    }

    /**
     * The no-model path: the reading's own search phrases, looked up on the chosen target. What
     * comes back is what that server really has for that phrase, which is as close as this app can
     * honestly get to "find music on X that fits this" without a model in the loop.
     */
    suspend fun fromTerms(
        terms: List<String>,
        target: MoodSearchTarget,
        limit: Int = 6
    ): MoodPickSearch {
        if (target == MoodSearchTarget.LIBRARY) {
            return MoodPickSearch()
        }
        var failure: String? = null
        val hits = mutableListOf<AssistantHit>()
        terms.map { it.trim() }.filter { it.isNotBlank() }.take(2).forEach { term ->
            if (hits.size >= limit) return@forEach
            val results = try {
                search(term, target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = failure ?: (e.message ?: "${target.short} could not be searched.")
                emptyList()
            }
            results.take(limit - hits.size).forEach { track ->
                hits += AssistantHit(
                    title = track.title,
                    artist = track.artist,
                    provider = track.provider,
                    detail = "\u201C$term\u201D ${target.phrase}",
                    url = track.url
                )
            }
        }
        return MoodPickSearch(hits = hits, error = failure)
    }

    private suspend fun search(phrase: String, target: MoodSearchTarget): List<TrackResult> =
        when (target) {
            MoodSearchTarget.JELLYFIN -> jellyfin.searchTracks(phrase)
            MoodSearchTarget.LIBRARY -> emptyList()
        }

    /** A song the listener already has: its stored item, so an offline copy still plays. */
    private fun libraryHit(
        pick: MoodAiPick,
        pins: List<MusicPin>,
        playlists: List<Playlist>
    ): AssistantHit? {
        val request = AssistantRequest(query = MoodPickMatch.phrase(pick.title, pick.artist))
        val found = AssistantLibrary.search(request, pins, playlists).firstOrNull() ?: return null
        return found.copy(detail = listOfNotNull(found.detail, pick.why).joinToString(" \u00b7 "))
    }

    private fun providerHit(
        track: TrackResult,
        pick: MoodAiPick,
        target: MoodSearchTarget
    ): AssistantHit = AssistantHit(
        title = track.title,
        artist = track.artist,
        provider = track.provider,
        detail = listOfNotNull(target.short, pick.why).joinToString(" \u00b7 "),
        url = track.url
    )
}

/**
 * Deciding which search result is the song that was named.
 *
 * Search engines answer a song title with the song, covers of it, live versions, and occasional
 * nonsense, so the first row is not automatically the answer. A result has to share a real word
 * of the title — shared words in the artist alone are not enough, or every song by a band would
 * match a request for one of them — and the best-scoring one wins. When nothing scores, the song
 * is reported as not found rather than played as a different song.
 */
internal object MoodPickMatch {

    /** The phrase a provider is searched with: artist first, the way a music search reads best. */
    fun phrase(title: String, artist: String): String =
        listOf(artist.trim(), title.trim()).filter { it.isNotBlank() }.joinToString(" ")

    fun best(title: String, artist: String, results: List<TrackResult>): TrackResult? {
        val titleWords = words(title)
        if (titleWords.isEmpty()) return null
        return results
            .map { result -> result to score(titleWords, words(artist), result) }
            .filter { (_, score) -> score > 0 }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    /** Higher is a closer match: title words weigh double, since that is what was asked for. */
    fun score(titleWords: Set<String>, artistWords: Set<String>, result: TrackResult): Int {
        val foundTitle = words(result.title)
        val foundArtist = words(result.artist)
        val titleHits = titleWords.count { it in foundTitle }
        if (titleHits == 0) return 0
        val artistHits = if (artistWords.isEmpty()) 0 else artistWords.count { it in foundArtist }
        // A result that is exactly what was named is not merely the best-scoring row — it is the
        // song, so it outranks a live take or a remix that shares the words but adds its own.
        val exactTitle = foundTitle == titleWords
        val exactArtist = artistWords.isEmpty() || foundArtist == artistWords
        val exact = if (exactTitle && exactArtist) 1000 else 0
        return exact + titleHits * 2 + artistHits
    }

    /** Comparable words of a name: lower case, punctuation gone, one-letter noise dropped. */
    private fun words(text: String): Set<String> = text.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 1 || it.any(Char::isDigit) }
        .toSet()
}
