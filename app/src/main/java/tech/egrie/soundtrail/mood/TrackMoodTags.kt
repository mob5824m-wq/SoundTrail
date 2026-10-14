package tech.egrie.soundtrail.mood

import kotlin.math.roundToInt

/**
 * Turns whatever is known about a song into mood vocabulary and rough audio features.
 *
 * Three sources are combined, cheapest first: the song's own text (title, artist, playlist
 * name), genre/mood strings from TheAudioDB when the listener has opted in to tag lookups, and
 * the provider the song came from. Nothing here needs the network or the Android framework —
 * it is plain text matching, so it runs in unit tests and on the phone equally.
 */
internal object TrackMoodTags {

    /** Tags plus any feature estimates the source could support. */
    internal data class Tags(
        val tags: List<String> = emptyList(),
        val valence: Double? = null,
        val energy: Double? = null,
        val tempo: Int? = null,
        val origin: String? = null
    ) {
        val isEmpty: Boolean get() = tags.isEmpty() && valence == null && energy == null

        operator fun plus(other: Tags): Tags = Tags(
            tags = (tags + other.tags).distinct(),
            valence = other.valence ?: valence,
            energy = other.energy ?: energy,
            tempo = other.tempo ?: tempo,
            origin = other.origin ?: origin
        )
    }

    /** Tag → keywords that appear in song titles, artist names, or playlist names. */
    private val cues: List<Pair<String, List<String>>> = listOf(
        "lofi" to listOf("lofi", "lo fi", "chillhop", "study beats", "beats to relax"),
        "chill" to listOf("chill", "chillout", "mellow", "relax", "laid back", "laidback", "smooth", "calm", "easy listening"),
        "acoustic" to listOf("acoustic", "unplugged", "ukulele", "singer songwriter", "fingerstyle"),
        "instrumental" to listOf("instrumental", "no vocals", "type beat", "karaoke", "backing track"),
        "piano" to listOf("piano", "nocturne", "keys", "pianist"),
        "classical" to listOf("classical", "symphony", "orchestra", "orchestral", "sonata", "concerto", "mozart", "beethoven", "chopin", "bach", "vivaldi", "debussy"),
        "jazz" to listOf("jazz", "swing", "bossa", "bebop", "saxophone", "blues"),
        "soul" to listOf("soul", "motown", "funk", "groove"),
        "rnb" to listOf("r&b", "rnb", "slow jam", "neo soul"),
        "rock" to listOf("rock", "guitar solo", "grunge", "alternative", "indie rock", "classic rock"),
        "metal" to listOf("metal", "metalcore", "deathcore", "thrash", "screamo", "hardcore"),
        "punk" to listOf("punk", "ska"),
        "pop" to listOf("pop", "top hits", "chart", "radio edit", "mainstream"),
        "hiphop" to listOf("hip hop", "hiphop", "rap", "trap", "drill", "grime", "boom bap", "freestyle"),
        "edm" to listOf("edm", "electro", "house", "techno", "trance", "dubstep", "drum and bass", "dnb", "hardstyle", "big room"),
        "bass" to listOf("bass boosted", "808", "sub bass", "wobble"),
        "phonk" to listOf("phonk", "drift"),
        "dance" to listOf("dance", "club", "dancefloor", "disco", "latin", "salsa", "afrobeat", "amapiano", "reggaeton", "merengue", "baile"),
        "remix" to listOf("remix", "bootleg", "mashup", "vip mix", "flip"),
        "slowed" to listOf("slowed", "reverb", "sped up", "nightcore", "8d audio", "daycore"),
        "ambient" to listOf("ambient", "drone", "soundscape", "meditation", "binaural", "white noise", "nature sounds", "rain", "asmr"),
        "sleep" to listOf("sleep", "lullaby", "insomnia", "dreamy", "bedtime", "deep sleep"),
        "workout" to listOf("workout", "gym", "training", "beast mode", "motivation", "cardio", "running music", "spin"),
        "study" to listOf("study", "focus", "concentration", "coding", "deep work", "exam"),
        "party" to listOf("party", "celebration", "birthday", "pregame", "festival", "anthem"),
        "summer" to listOf("summer", "beach", "holiday", "vacation", "sunset", "tropical"),
        "love" to listOf("love", "romantic", "valentine", "wedding", "kiss", "crush", "darling"),
        "sad" to listOf("sad", "tears", "breakup", "heartbreak", "heartbroken", "lonely", "hurt", "cry", "miss you", "melancholy", "melancholic"),
        "nostalgia" to listOf("throwback", "retro", "80s", "90s", "2000s", "old school", "oldschool", "vintage", "memories"),
        "anime" to listOf("anime", "ost", "soundtrack", "jpop", "j pop", "kpop", "k pop", "opening theme"),
        "christmas" to listOf("christmas", "xmas", "holiday"),
        "country" to listOf("country", "bluegrass", "folk", "americana", "honky tonk"),
        "reggae" to listOf("reggae", "dub", "dancehall"),
        "epic" to listOf("epic", "cinematic", "trailer", "orchestral epic", "score"),
        "hype" to listOf("hype", "adrenaline", "hard", "heavy", "banger", "drop")
    )

    /** How each tag feels, used to estimate features when nothing better is known. */
    private val anchors: Map<String, Pair<Double, Double>> = mapOf(
        // tag to (energy, valence)
        "lofi" to (0.28 to 0.60),
        "chill" to (0.24 to 0.64),
        "acoustic" to (0.35 to 0.60),
        "instrumental" to (0.40 to 0.55),
        "piano" to (0.25 to 0.50),
        "classical" to (0.30 to 0.55),
        "jazz" to (0.42 to 0.65),
        "soul" to (0.52 to 0.72),
        "rnb" to (0.45 to 0.65),
        "rock" to (0.72 to 0.60),
        "metal" to (0.92 to 0.32),
        "punk" to (0.86 to 0.48),
        "pop" to (0.68 to 0.86),
        "hiphop" to (0.72 to 0.70),
        "edm" to (0.93 to 0.78),
        "bass" to (0.88 to 0.68),
        "phonk" to (0.90 to 0.50),
        "dance" to (0.85 to 0.86),
        "remix" to (0.70 to 0.70),
        "slowed" to (0.30 to 0.42),
        "ambient" to (0.14 to 0.55),
        "sleep" to (0.10 to 0.50),
        "workout" to (0.90 to 0.80),
        "study" to (0.30 to 0.60),
        "party" to (0.90 to 0.90),
        "summer" to (0.70 to 0.86),
        "love" to (0.45 to 0.78),
        "sad" to (0.30 to 0.20),
        "nostalgia" to (0.50 to 0.60),
        "anime" to (0.60 to 0.70),
        "christmas" to (0.50 to 0.72),
        "country" to (0.45 to 0.60),
        "reggae" to (0.50 to 0.75),
        "epic" to (0.78 to 0.70),
        "hype" to (0.88 to 0.72)
    )

    /** TheAudioDB genre strings mapped onto the same vocabulary. */
    private val genreTags: Map<String, String> = mapOf(
        "pop" to "pop", "rock" to "rock", "electronic" to "edm", "dance" to "dance",
        "hip hop" to "hiphop", "hip-hop" to "hiphop", "rap" to "hiphop", "r&b" to "rnb",
        "rnb" to "rnb", "soul" to "soul", "funk" to "soul", "jazz" to "jazz", "blues" to "jazz",
        "classical" to "classical", "opera" to "classical", "country" to "country",
        "folk" to "country", "reggae" to "reggae", "metal" to "metal", "punk" to "punk",
        "indie" to "rock", "alternative" to "rock", "ambient" to "ambient", "new age" to "ambient",
        "soundtrack" to "epic", "score" to "epic", "world" to "dance", "latin" to "dance",
        "disco" to "dance", "techno" to "edm", "house" to "edm", "trance" to "edm",
        "gospel" to "soul", "christian" to "soul", "children" to "sleep", "vocal" to "pop"
    )

    /** TheAudioDB mood strings, which are the strongest single signal the app can get. */
    private val moodTags: Map<String, Pair<String, Pair<Double, Double>>> = mapOf(
        // mood string to (tag, (energy, valence))
        "happy" to ("pop" to (0.70 to 0.92)),
        "joyful" to ("pop" to (0.72 to 0.90)),
        "energetic" to ("hype" to (0.92 to 0.78)),
        "chill" to ("chill" to (0.24 to 0.64)),
        "relaxed" to ("chill" to (0.22 to 0.66)),
        "laid-back" to ("chill" to (0.26 to 0.64)),
        "soothing" to ("calm" to (0.18 to 0.62)),
        "peaceful" to ("calm" to (0.16 to 0.66)),
        "romantic" to ("love" to (0.45 to 0.84)),
        "passionate" to ("love" to (0.62 to 0.78)),
        "melancholic" to ("sad" to (0.30 to 0.18)),
        "sad" to ("sad" to (0.28 to 0.16)),
        "brooding" to ("sad" to (0.45 to 0.22)),
        "dark" to ("metal" to (0.60 to 0.28)),
        "aggressive" to ("metal" to (0.92 to 0.30)),
        "angry" to ("metal" to (0.90 to 0.26)),
        "uplifting" to ("pop" to (0.68 to 0.90)),
        "hopeful" to ("pop" to (0.60 to 0.84)),
        "party" to ("party" to (0.90 to 0.90)),
        "groovy" to ("soul" to (0.78 to 0.84)),
        "funky" to ("soul" to (0.80 to 0.82)),
        "sentimental" to ("nostalgia" to (0.42 to 0.58)),
        "reflective" to ("nostalgia" to (0.32 to 0.50)),
        "dreamy" to ("ambient" to (0.24 to 0.60)),
        "trippy" to ("ambient" to (0.45 to 0.58)),
        "epic" to ("epic" to (0.80 to 0.72)),
        "quirky" to ("pop" to (0.62 to 0.78)),
        "silly" to ("pop" to (0.66 to 0.84)),
        "smooth" to ("soul" to (0.45 to 0.74)),
        "confident" to ("hiphop" to (0.76 to 0.78)),
        "motivational" to ("workout" to (0.82 to 0.78)),
        "sexy" to ("rnb" to (0.55 to 0.80))
    )

    /** Tags are read from the song's own text; unknown text simply yields no tags. */
    fun fromText(title: String, artist: String, playlistName: String? = null): Tags {
        val haystack = MoodEngine.normalize(
            listOf(title, artist, playlistName.orEmpty()).joinToString(" ")
        )
        if (haystack.isBlank()) return Tags()
        val found = cues.filter { (_, keywords) ->
            keywords.any { keyword ->
                val needle = MoodEngine.normalize(keyword)
                needle.isNotBlank() && haystack.contains(needle)
            }
        }.map { it.first }
        if (found.isEmpty()) return Tags(origin = "Text")
        return Tags(tags = found, origin = "Text") + estimateFrom(found, "Text")
    }

    /** Genre/mood strings, as returned by TheAudioDB's free track search. */
    fun fromMetadata(genre: String?, mood: String?): Tags {
        var tags = Tags()
        val moodKey = mood?.let { MoodEngine.normalize(it) }?.trim()
        moodTags[moodKey]?.let { (tag, anchor) ->
            tags = Tags(
                tags = listOf(tag),
                valence = anchor.second,
                energy = anchor.first,
                origin = "TheAudioDB"
            )
        }
        val genreKey = genre?.let { MoodEngine.normalize(it) }?.trim()
        genreTags[genreKey]?.let { tag ->
            tags = tags + Tags(tags = listOf(tag), origin = "TheAudioDB")
        }
        return if (tags.isEmpty) {
            // Unknown vocabulary: fall back to whatever words the strings themselves carry.
            fromText(genre.orEmpty(), mood.orEmpty())
        } else {
            tags
        }
    }

    private fun estimateFrom(tags: List<String>, origin: String): Tags {
        val known = tags.mapNotNull { tag -> anchors[tag] }
        if (known.isEmpty()) return Tags(origin = origin)
        val energy = known.map { it.first }.average()
        val valence = known.map { it.second }.average()
        return Tags(
            valence = valence,
            energy = energy,
            tempo = tempoFor(energy),
            origin = origin
        )
    }

    /** Rough tempo estimate from energy, used only when nothing better is available. */
    fun tempoFor(energy: Double): Int = (72.0 + energy.coerceIn(0.0, 1.0) * 74.0).roundToInt()
}
