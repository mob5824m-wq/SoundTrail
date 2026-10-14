package tech.egrie.soundtrail.assistant

import tech.egrie.soundtrail.integrations.MusicProvider

/**
 * What a spoken request means, once the command words are off it.
 *
 * A dictation is a sentence: "hey soundtrail, can you play Sunsets by the XX on YouTube Music".
 * [query] is what is left for a search, [title] and [artist] are filled in when the listener
 * named both, [provider] when they said where to look, and [mood] is true when they described a
 * feeling instead of a song.
 */
internal data class AssistantRequest(
    /** The words to look for, with the command and the "on <app>" tail removed. */
    val query: String,
    val title: String? = null,
    val artist: String? = null,
    val provider: MusicProvider? = null,
    /** True when this is a description of how the listener feels, not a song to look for. */
    val mood: Boolean = false,
    /**
     * The request without the "on <app>" tail but with every command word still in it — the
     * words as they were said, which a search that found nothing can fall back on.
     */
    val spoken: String = ""
) {
    /**
     * The phrase a search should be given: the artist first when the request named one, since
     * "the xx sunsets" finds the song where "sunsets" alone finds covers of it.
     */
    val phrase: String
        get() = listOfNotNull(artist, title).joinToString(" ").ifBlank { query }
}

/**
 * Turns what was said into something to search for.
 *
 * This is deliberately on-device and rule-based: it runs in the moment, it needs no connection
 * and no account, it cannot leak a voice recording, and it behaves the same for a listener who
 * has never configured a model. The only licence it takes is with "play": a bare command verb is
 * dropped only when at least two words follow it, so "play Sunsets" keeps its song name instead
 * of searching for a lonely "Sunsets" — and a stray "play" in a search phrase costs nothing.
 */
internal object AssistantParser {

    /**
     * Command words, longest first so that "i want to hear" is taken off before "i want".
     * Everything after the command is the song.
     */
    private val leadIns = listOf(
        "hey soundtrail", "ok soundtrail", "okay soundtrail", "soundtrail",
        "can you ", "could you ", "would you ", "can i hear ",
        "i want to hear ", "i wanna hear ", "i want to listen to ", "i would like to hear ",
        "i'd like to hear ", "i want to play ", "let's hear ", "lets hear ",
        "please play ", "please put on ", "please queue ",
        "search for ", "search ", "look up ", "find me ", "find ",
        "play me ", "play some ", "play us ", "play ",
        "put on ", "throw on ", "queue up ", "queue ", "start playing ", "start ",
        "give me ", "get me ", "show me "
    )

    /** Where the listener said to look, longest phrase first so "youtube music" wins. */
    private val providerHints = listOf(
        "youtube music" to MusicProvider.YOUTUBE_MUSIC,
        "youtube" to MusicProvider.YOUTUBE,
        "spotify" to MusicProvider.SPOTIFY,
        "soundcloud" to MusicProvider.SOUNDCLOUD,
        "jellyfin" to MusicProvider.JELLYFIN
    )

    /**
     * The phrases that make a request a feeling rather than a song. They are all things a person
     * says about a mood, not titles: a request containing "by" is a song request even if it also
     * says "vibes", because "vibes by kygo" is a track.
     */
    private val moodCues = listOf(
        "mood", "vibe", "vibes", "feel like", "i feel", "i'm feeling", "im feeling",
        "music for", "mix for", "something to play", "songs that", "something chill",
        "something upbeat", "something calm", "set the mood", "background music",
        "put me in the mood", "make me a mix", "build a mix"
    )

    /**
     * "the song …", "the track …" said before a title. The article is what makes it safe: the
     * bare words are only dropped when a title still has two words to stand on, so "Song 2"
     * keeps its name.
     */
    private val songWords = listOf("the song ", "the track ", "the tune ")
    private val bareSongWords = listOf("song ", "track ", "tune ")

    /** "on YouTube Music", "on Spotify" at the end of a request, and the space before it. */
    private val onProviderSuffix = Regex(
        "\\s+on\\s+(youtube music|youtube|spotify|soundcloud|jellyfin)\\s*$",
        RegexOption.IGNORE_CASE
    )

    /** How a listener gives an artist in speech: "Sunsets by the XX". */
    private val bySplit = Regex("\\s+by\\s+", RegexOption.IGNORE_CASE)

    /** Null when there is no song in what was said — silence, or a bare "play". */
    fun parse(spoken: String): AssistantRequest? {
        val said = normalise(spoken)
        if (said.isEmpty()) return null

        // "on YouTube Music" only says where to look; the song is what is left.
        var body = said
        val provider = onProviderSuffix.find(body)?.let { match ->
            body = body.removeRange(match.range).trim()
            providerHints.firstOrNull { it.first == match.groupValues[1].lowercase() }?.second
        }

        val asked = stripLeadIns(body)
        // Nothing but a command was said: "play", "please play", "hey soundtrail". There is no
        // request in it, and searching for the command word would answer with nonsense.
        if (asked.isEmpty() || leadIns.any { it.trim().equals(asked, ignoreCase = true) }) return null

        if (looksLikeAFeeling(said) && provider == null) {
            // The whole sentence goes to the mood engine: it reads "i feel" better than a
            // query would.
            return AssistantRequest(query = said, mood = true)
        }

        val named = nameSong(asked)
        // "the song X" said without an artist leaves the label on the query, so it comes off
        // here too — the title survives even when it is one word, because "the" is what says
        // the word after it is a title rather than part of the command.
        return AssistantRequest(
            query = named?.second ?: asked.stripSongWords().ifBlank { asked },
            title = named?.second,
            artist = named?.first,
            provider = provider,
            spoken = body
        )
    }

    /**
     * Turns dictation into plain words: curly quotes straightened, commas and full stops that a
     * recogniser inserts as pauses removed, runs of space collapsed.
     */
    private fun normalise(spoken: String): String = spoken
        .replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201B', '\'')
        .replace('\u201C', '"').replace('\u201D', '"')
        .replace(',', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.', '!', '?', ';', ':', '"', '\'', '-', ' ')

    /**
     * Removes command words from the front, repeatedly, so "hey soundtrail, can you play" ends up
     * as the song. A bare verb is only removed when two or more words follow it, which is what
     * keeps "play Sunsets" from turning into a search for nothing.
     */
    private fun stripLeadIns(text: String): String {
        var current = text
        var changed = true
        while (changed) {
            changed = false
            for (lead in leadIns) {
                if (!current.startsWith(lead, ignoreCase = true)) continue
                val rest = current.removeRange(0, lead.length).trimStart()
                // Nothing but the command was said: there is no request in it.
                if (rest.isEmpty()) return ""
                if (lead.trim().split(' ').size == 1) {
                    // A single command word comes off only when a title of at least two words
                    // follows it — "Play Sunsets" keeps its words, because the song may well be
                    // called "Play" — and never when what follows starts with a command word of
                    // its own, which is how "Play Search and Destroy" and "Play Start Me Up"
                    // survive being read as two commands in a row.
                    if (rest.split(' ').size < 2) break
                    if (leadIns.any { rest.startsWith(it, ignoreCase = true) }) break
                }
                current = rest
                changed = true
                break
            }
        }
        return current.trim()
    }

    /** True when what was said is a feeling. A named artist makes it a song request instead. */
    private fun looksLikeAFeeling(said: String): Boolean {
        val lower = said.lowercase()
        if (bySplit.containsMatchIn(said)) return false
        return moodCues.any { lower.contains(it) }
    }

    /**
     * Splits "Sunsets by the XX" into artist and title. Null when the listener named no artist,
     * which keeps the whole phrase as the query — the common case for titles nobody would guess.
     */
    private fun nameSong(asked: String): Pair<String, String>? {
        val match = bySplit.find(asked) ?: return null
        val title = asked.substring(0, match.range.first).trim().stripSongWords()
        val artist = asked.substring(match.range.last + 1).trim()
        if (title.isEmpty() || artist.isEmpty()) return null
        return artist to title
    }

    /** Drops a "song" or "track" said before a title, without eating one that is the title. */
    private fun String.stripSongWords(): String {
        var value = this
        var changed = true
        while (changed) {
            changed = false
            val words = listOf(songWords, bareSongWords)
            for (group in words) {
                for (word in group) {
                    if (!value.startsWith(word, ignoreCase = true)) continue
                    val rest = value.removeRange(0, word.length).trimStart()
                    if (rest.isEmpty()) break
                    if (group === bareSongWords && rest.split(' ').size < 2) break
                    value = rest
                    changed = true
                    break
                }
                if (changed) break
            }
        }
        return value.trim()
    }
}
