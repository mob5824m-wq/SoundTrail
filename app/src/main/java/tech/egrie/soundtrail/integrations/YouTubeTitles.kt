package tech.egrie.soundtrail.integrations

/**
 * YouTube titles carry production noise — "(Official Video)", "[Lyric Video]", "(Audio)" — that
 * makes a song hard to find in a list of search results. Those qualifiers are removed so a row
 * reads as the song does.
 *
 * Only qualifiers that cannot change *which* recording this is are removed. "Live", "Acoustic",
 * "Remix", "Instrumental", "Extended" and "Demo" are kept: those are different recordings of the
 * same song, and hiding one behind the other's name would be a lie about what plays.
 */
internal object YouTubeTitles {

    /** Qualifiers that say how the upload was made, not what was played. */
    private val productionWords = setOf(
        "official video", "official music video", "official audio", "official lyric video",
        "official visualizer", "official hd video", "music video", "lyric video", "lyrics video",
        "lyrics", "audio", "video", "visualizer", "visualiser", "hd", "hq", "4k", "8k", "uhd",
        "full video", "full song", "full audio", "m/v", "mv", "pv", "official"
    )

    /** Any bracketed tail — round, square or full width — considered for removal. */
    private val bracket = Regex("""
        \s*[\[(\uFF08\u3010]\s*([^)\]\uFF09\u3011]{0,60}?)\s*[)\]\uFF09\u3011]\s*$
    """.trimIndent())

    /**
     * The title without its production qualifiers, and without runs of leftover spacing. A title
     * that is *only* a qualifier is left alone rather than emptied.
     */
    fun clean(raw: String): String {
        var title = raw.trim()
        while (true) {
            val match = bracket.find(title) ?: break
            val inside = match.groupValues[1]
                .removePrefix("official ").trim().lowercase()
            val isProduction = inside in productionWords ||
                inside.removeSuffix(" video").removeSuffix(" audio").trim() in productionWords
            if (!isProduction) break
            val trimmed = title.removeRange(match.range).trim()
            if (trimmed.isEmpty()) break
            title = trimmed
        }
        val collapsed = title.replace(Regex("\\s{2,}"), " ").trim().trimEnd('-', '\u2013', '|', ' ').trim()
        return collapsed.ifBlank { raw.trim() }
    }

    /**
     * The artist, when the uploader put "Artist - Song" in the title and the channel name is a
     * label, a topic channel or nothing useful. Null when the title says nothing extra.
     */
    fun artistFromTitle(raw: String): String? {
        val separator = raw.indexOf(" - ").takeIf { it > 0 } ?: return null
        val candidate = raw.substring(0, separator).trim()
        if (candidate.isEmpty() || candidate.length > 60) return null
        if (candidate.split(' ').size > 6) return null
        return candidate
    }
}
