package tech.egrie.soundtrail.ui

import tech.egrie.soundtrail.data.MusicPin

/**
 * Finding a soundspot on the map.
 *
 * The same rule the Ask tab uses for the library, for the same reason: every word has to appear
 * somewhere in the pin — matching any one of them would answer "the xx" with every pin that has
 * "the" in it. A query of nothing but connecting words ("from the") finds nothing rather than
 * everything.
 */
internal object PinSearch {

    /** How many matches the map offers at once: enough to choose from, few enough to stay visible. */
    const val MAX_MATCHES = 4

    private val fillerWords = setOf(
        "the", "a", "an", "of", "from", "in", "at", "on", "for", "to", "my", "me", "that", "this"
    )

    /** The pins matching [query], best first: a title match before an artist or a place one. */
    fun matches(pins: List<MusicPin>, query: String): List<MusicPin> {
        val words = query.lowercase().split(' ')
            .filter(String::isNotBlank)
            .filterNot { it in fillerWords }
        if (words.isEmpty()) return emptyList()
        val matching = pins.filter { pin ->
            val haystack = "${pin.title} ${pin.artist} ${pin.place}".lowercase()
            words.all { haystack.contains(it) }
        }
        return matching
            .sortedByDescending { pin -> words.count { pin.title.lowercase().contains(it) } }
            .take(MAX_MATCHES)
    }
}
