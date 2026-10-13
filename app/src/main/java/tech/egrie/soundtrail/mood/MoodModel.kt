package tech.egrie.soundtrail.mood

/**
 * The mood layer. Everything here is plain Kotlin: no Android types, no network, no keys.
 * It reads how the listener says they feel and turns that into a target sound (valence,
 * energy, tempo, tag vocabulary) that [MoodMixer] scores the local library against.
 */

/** Where a mood reading came from; shown next to the result so the source is never ambiguous. */
internal enum class MoodSource(val label: String) {
    ON_DEVICE("On-device mood engine"),
    AI_MODEL("AI model")
}

/**
 * One mood Soundtrail recognises, together with the sound it maps to. The audio-feature
 * targets are the plain-language properties the mixer scores tracks against; the tag
 * vocabulary is what track titles, artist names, and TheAudioDB genre/mood fields are
 * matched against.
 */
internal data class MoodDefinition(
    val id: String,
    val label: String,
    val blurb: String,
    val valence: Double,
    val energy: Double,
    val tempo: Int,
    val danceability: Double,
    val acousticness: Double,
    val instrumentalness: Double,
    val tags: List<String>,
    val searchTerms: List<String>,
    val words: List<String>,
    val strongWords: List<String> = emptyList()
)

/**
 * The moods the engine knows, each with a small hand-built lexicon. Words are stored
 * apostrophe-free and lower-case because the analyser normalises its input that way.
 */
internal object MoodCatalog {
    val moods: List<MoodDefinition> = listOf(
        MoodDefinition(
            id = "calm",
            label = "Calm",
            blurb = "Steady, unhurried sound to settle into",
            valence = 0.62, energy = 0.26, tempo = 76,
            danceability = 0.30, acousticness = 0.55, instrumentalness = 0.55,
            tags = listOf("chill", "lofi", "ambient", "acoustic", "piano", "mellow", "soft"),
            searchTerms = listOf("calm chill mix", "lofi chill beats"),
            words = listOf(
                "calm", "calmer", "relax", "relaxed", "chill", "chilled", "chillout", "peaceful",
                "peace", "easy", "easygoing", "cozy", "cosy", "mellow", "soft", "gentle", "quiet",
                "serene", "unwind", "decompress", "breathe", "breathing", "rest", "rested", "slow",
                "smooth", "settled", "still", "steady", "balm", "soothe", "soothing", "meditative"
            ),
            strongWords = listOf("relaxing", "unwind", "peaceful", "serene", "chilledout")
        ),
        MoodDefinition(
            id = "happy",
            label = "Happy",
            blurb = "Bright, upbeat music to match the lift",
            valence = 0.90, energy = 0.68, tempo = 118,
            danceability = 0.62, acousticness = 0.22, instrumentalness = 0.20,
            tags = listOf("upbeat", "pop", "dance", "feelgood", "summer", "sunshine", "funk"),
            searchTerms = listOf("feel good hits", "happy upbeat playlist"),
            words = listOf(
                "happy", "happier", "happiness", "glad", "great", "good", "joy", "joyful", "cheerful",
                "cheery", "cheer", "smile", "smiling", "grin", "sunny", "sunshine", "bright", "light",
                "fun", "lively", "bubbly", "delighted", "excited", "buzzing", "grateful", "thankful",
                "blessed", "sunlit", "hopeful", "optimistic", "celebrate", "pleased", "elated"
            ),
            strongWords = listOf("ecstatic", "overjoyed", "amazing", "wonderful", "fantastic")
        ),
        MoodDefinition(
            id = "sad",
            label = "Low",
            blurb = "Gentle, slow songs for a heavy day",
            valence = 0.16, energy = 0.28, tempo = 74,
            danceability = 0.30, acousticness = 0.62, instrumentalness = 0.35,
            tags = listOf("sad", "ballad", "piano", "slow", "melancholy", "acoustic", "strings"),
            searchTerms = listOf("sad songs playlist", "slow sad acoustic"),
            words = listOf(
                "sad", "sadness", "down", "low", "blue", "unhappy", "gloomy", "gloom", "heavy",
                "tears", "tearful", "cry", "crying", "weep", "hurt", "hurting", "aching", "ache",
                "empty", "hollow", "numb", "grey", "gray", "hopeless", "defeated", "sinking",
                "melancholy", "somber", "sombre", "grief", "grieving", "loss", "sorrow"
            ),
            strongWords = listOf("depressed", "devastated", "heartbroken", "miserable", "worthless")
        ),
        MoodDefinition(
            id = "hype",
            label = "Hyped",
            blurb = "Loud, fast tracks to keep the surge going",
            valence = 0.80, energy = 0.95, tempo = 146,
            danceability = 0.72, acousticness = 0.05, instrumentalness = 0.35,
            tags = listOf("edm", "hardstyle", "phonk", "trap", "club", "bass", "workout", "remix"),
            searchTerms = listOf("hype gym mix", "adrenaline playlist"),
            words = listOf(
                "hype", "hyped", "hyping", "pumped", "pump", "amped", "energised", "energized",
                "energy", "energetic", "fired", "firedup", "turnt", "wild", "loud", "fast", "adrenaline",
                "rush", "intense", "sprint", "sprinting", "gym", "workout", "lifting", "training",
                "cardio", "run", "running", "rage", "unstoppable", "beast", "hustle"
            ),
            strongWords = listOf("hyped", "adrenaline", "unstoppable", "beast", "electric")
        ),
        MoodDefinition(
            id = "focus",
            label = "Focused",
            blurb = "Even, low-distraction sound for deep work",
            valence = 0.55, energy = 0.36, tempo = 92,
            danceability = 0.35, acousticness = 0.40, instrumentalness = 0.85,
            tags = listOf("lofi", "instrumental", "ambient", "study", "beats", "piano", "coding"),
            searchTerms = listOf("instrumental study beats", "lofi focus mix"),
            words = listOf(
                "focus", "focused", "concentrate", "concentrating", "concentration", "study",
                "studying", "work", "working", "coding", "code", "deep", "flow", "productive",
                "productivity", "deadline", "exam", "revision", "reading", "writing", "thinking",
                "clearheaded", "locked", "headsdown", "grind", "grinding"
            ),
            strongWords = listOf("deep work", "headsdown", "in the zone")
        ),
        MoodDefinition(
            id = "angry",
            label = "Angry",
            blurb = "Hard, driving music to burn it off",
            valence = 0.24, energy = 0.92, tempo = 152,
            danceability = 0.42, acousticness = 0.05, instrumentalness = 0.35,
            tags = listOf("metal", "rock", "punk", "hardcore", "industrial", "rage"),
            searchTerms = listOf("heavy angry playlist", "rock rage mix"),
            words = listOf(
                "angry", "anger", "mad", "furious", "fury", "rage", "raging", "irritated", "annoyed",
                "pissed", "frustrated", "frustrating", "fed", "burnedout", "burntout", "screaming",
                "shout", "yelling", "seething", "bitter", "resentful", "hate", "hating", "snapped",
                "boiling", "livid"
            ),
            strongWords = listOf("furious", "livid", "seething", "rage")
        ),
        MoodDefinition(
            id = "romantic",
            label = "Romantic",
            blurb = "Warm, close music for a tender mood",
            valence = 0.82, energy = 0.42, tempo = 92,
            danceability = 0.55, acousticness = 0.35, instrumentalness = 0.25,
            tags = listOf("love", "rnb", "soul", "ballad", "slow", "jazz", "romantic"),
            searchTerms = listOf("love songs playlist", "slow rnb love mix"),
            words = listOf(
                "love", "loved", "loving", "romantic", "romance", "crush", "crushing", "inlove",
                "heart", "hearts", "kiss", "kissing", "cuddle", "cuddling", "date", "dating",
                "valentine", "wedding", "anniversary", "tender", "affection", "affectionate",
                "sweet", "smitten", "adore", "adores", "dear", "darling", "warm"
            ),
            strongWords = listOf("inlove", "smitten", "adore", "soulmate")
        ),
        MoodDefinition(
            id = "nostalgic",
            label = "Nostalgic",
            blurb = "Old favourites and warm memories",
            valence = 0.58, energy = 0.45, tempo = 104,
            danceability = 0.42, acousticness = 0.40, instrumentalness = 0.30,
            tags = listOf("retro", "throwback", "classic", "oldschool", "vintage", "acoustic"),
            searchTerms = listOf("throwback classics", "old school hits"),
            words = listOf(
                "nostalgic", "nostalgia", "memories", "memory", "remember", "remembering", "throwback",
                "retro", "oldies", "classic", "classics", "vintage", "younger", "childhood", "teen",
                "school", "summer", "hometown", "backthen", "used", "usedto", "gone", "past",
                "sentimental", "wistful", "miss", "missing", "missed"
            ),
            strongWords = listOf("nostalgic", "nostalgia", "throwback", "childhood")
        ),
        MoodDefinition(
            id = "anxious",
            label = "Anxious",
            blurb = "Soft, slow sound to steady your breathing",
            valence = 0.34, energy = 0.22, tempo = 70,
            danceability = 0.20, acousticness = 0.68, instrumentalness = 0.78,
            tags = listOf("ambient", "piano", "breathing", "meditation", "soft", "slow"),
            searchTerms = listOf("calm anxiety relief music", "ambient breathing mix"),
            words = listOf(
                "anxious", "anxiety", "nervous", "nerves", "worried", "worry", "worrying", "stressed",
                "stress", "stressful", "overwhelmed", "overwhelming", "panic", "panicking", "tense",
                "tension", "uneasy", "restless", "jittery", "racing", "spiral", "spiralling",
                "spiraling", "dread", "onedge", "pressure", "swamped", "burnout"
            ),
            strongWords = listOf("panic", "overwhelmed", "anxiety", "dread", "burnout")
        ),
        MoodDefinition(
            id = "confident",
            label = "Confident",
            blurb = "Smooth, swaggering tracks for feeling unstoppable",
            valence = 0.78, energy = 0.78, tempo = 124,
            danceability = 0.78, acousticness = 0.15, instrumentalness = 0.35,
            tags = listOf("hiphop", "rap", "trap", "funk", "groove", "swagger"),
            searchTerms = listOf("confident rap mix", "boss playlist"),
            words = listOf(
                "confident", "confidence", "boss", "bossy", "swagger", "smooth", "sharp", "ready",
                "determined", "driven", "ambitious", "focusedup", "winning", "win", "won", "slaying",
                "killing", "slick", "cool", "suave", "powerful", "strong", "solid", "secure",
                "unstoppable", "iconic", "main", "lockingin"
            ),
            strongWords = listOf("unstoppable", "slaying", "boss", "won")
        ),
        MoodDefinition(
            id = "sleepy",
            label = "Sleepy",
            blurb = "Barely-there sound for winding down",
            valence = 0.52, energy = 0.10, tempo = 62,
            danceability = 0.10, acousticness = 0.75, instrumentalness = 0.90,
            tags = listOf("sleep", "ambient", "rain", "lullaby", "dreamy", "soft", "piano"),
            searchTerms = listOf("sleep music", "rain sounds for sleep"),
            words = listOf(
                "sleepy", "sleep", "sleeping", "tired", "exhausted", "wiped", "drained", "drowsy",
                "knackered", "spent", "worn", "bedtime", "bed", "night", "midnight", "insomnia",
                "winding", "winddown", "dozing", "nap", "yawn", "sluggish", "foggy", "zoned"
            ),
            strongWords = listOf("exhausted", "drained", "knackered", "insomnia")
        ),
        MoodDefinition(
            id = "party",
            label = "Party",
            blurb = "Big, danceable music to keep the room moving",
            valence = 0.92, energy = 0.90, tempo = 126,
            danceability = 0.88, acousticness = 0.10, instrumentalness = 0.45,
            tags = listOf("party", "club", "dance", "edm", "house", "disco", "afrobeat", "latin"),
            searchTerms = listOf("party dance mix", "club hits playlist"),
            words = listOf(
                "party", "partying", "pregame", "celebration", "celebrating", "birthday", "bash",
                "festival", "dancing", "dance", "dancefloor", "club", "clubbing", "nightout",
                "friends", "crowd", "vibes", "weekend", "shots", "drinks", "holiday", "vacation",
                "beach", "roadtrip"
            ),
            strongWords = listOf("party", "dancefloor", "clubbing", "nightout")
        ),
        MoodDefinition(
            id = "heartbreak",
            label = "Heartbreak",
            blurb = "Tender songs for missing someone",
            valence = 0.20, energy = 0.30, tempo = 76,
            danceability = 0.35, acousticness = 0.60, instrumentalness = 0.35,
            tags = listOf("sad", "ballad", "acoustic", "soul", "slow", "melancholy", "strings"),
            searchTerms = listOf("heartbreak songs", "sad love songs"),
            words = listOf(
                "heartbreak", "heartbroken", "breakup", "brokeup", "ex", "dumped", "rejected",
                "unrequited", "divorce", "split", "goodbye", "left", "alone", "lonely", "loneliness",
                "abandoned", "solo", "hurt", "betrayed", "cheated", "grief"
            ),
            strongWords = listOf("heartbroken", "breakup", "dumped", "betrayed")
        ),
        MoodDefinition(
            id = "motivated",
            label = "Motivated",
            blurb = "Rising, cinematic sound for getting things done",
            valence = 0.74, energy = 0.74, tempo = 124,
            danceability = 0.55, acousticness = 0.20, instrumentalness = 0.55,
            tags = listOf("motivation", "epic", "cinematic", "anthem", "rock", "orchestra"),
            searchTerms = listOf("motivational epic music", "workout anthems"),
            words = listOf(
                "motivated", "motivation", "motivating", "inspired", "inspiring", "inspiration",
                "ready", "goal", "goals", "going", "start", "starting", "begin", "beginning",
                "build", "building", "progress", "momentum", "forward", "push", "pushing",
                "disciplined", "consistency", "reset", "fresh", "newchapter"
            ),
            strongWords = listOf("motivated", "inspired", "momentum", "disciplined")
        ),
        MoodDefinition(
            id = "lonely",
            label = "Solitary",
            blurb = "Quiet company for a solitary stretch",
            valence = 0.30, energy = 0.25, tempo = 80,
            danceability = 0.30, acousticness = 0.60, instrumentalness = 0.70,
            tags = listOf("acoustic", "ambient", "soft", "slow", "sad", "piano"),
            searchTerms = listOf("quiet late night songs", "solitude acoustic mix"),
            words = listOf(
                "lonely", "alone", "solitude", "solitary", "isolated", "isolating", "withdrawn",
                "quiet", "myself", "introvert", "homesick", "far", "distance", "apart", "shutin",
                "antisocial", "invisible"
            ),
            strongWords = listOf("lonely", "isolated", "homesick", "invisible")
        )
    )

    private val byId: Map<String, MoodDefinition> = moods.associateBy { it.id }

    fun find(id: String): MoodDefinition? = byId[id]

    /** The mood whose sound profile is closest to a free-form feature reading (AI output). */
    fun nearest(
        valence: Double,
        energy: Double,
        label: String? = null,
        tags: List<String> = emptyList()
    ): MoodDefinition {
        label?.let { wanted ->
            val key = wanted.trim().lowercase().replace(Regex("[^a-z0-9]+"), "")
            moods.firstOrNull { mood ->
                mood.id == key || mood.label.lowercase().replace(Regex("[^a-z0-9]+"), "") == key
            }?.let { return it }
        }
        tags.map { it.lowercase() }.forEach { tag ->
            moods.firstOrNull { mood -> mood.tags.any { it == tag } }?.let { return it }
        }
        return moods.minByOrNull { mood ->
            val valenceGap = mood.valence - valence
            val energyGap = mood.energy - energy
            valenceGap * valenceGap * 1.2 + energyGap * energyGap
        } ?: moods.first()
    }
}

/** Moods that flip when the listener says they are *not* feeling the first one. */
internal val moodCounterparts: Map<String, String> = mapOf(
    "happy" to "sad",
    "calm" to "anxious",
    "hype" to "sleepy",
    "party" to "lonely",
    "romantic" to "heartbreak",
    "confident" to "anxious",
    "motivated" to "sleepy",
    "nostalgic" to "heartbreak"
)

/** What the listener has said and what the phone already knows, blended by the engine. */
internal data class MoodContext(
    /** 0–23 local hour; shown to the listener and used as a gentle tilt, never a verdict. */
    val hourOfDay: Int? = null,
    /** On-device motion from the automation watch, when it is running. */
    val motion: String? = null,
    /** An explicit "how much energy do you want" hint, 0..1. */
    val energyHint: Double? = null
)

/** A finished mood reading: what was heard, what it means, and the sound it asks for. */
internal data class MoodProfile(
    val id: String,
    val label: String,
    val blurb: String,
    val source: MoodSource,
    val confidence: Double,
    val valence: Double,
    val energy: Double,
    val tempo: Int,
    val danceability: Double,
    val acousticness: Double,
    val instrumentalness: Double,
    val tags: List<String>,
    val searchTerms: List<String>,
    /** The words in the listener's own text that carried the most weight. */
    val matchedWords: List<String> = emptyList(),
    /** Other moods the text pointed at, strongest first, as (label, share 0..1) pairs. */
    val supporting: List<Pair<String, Double>> = emptyList(),
    /** A one-line explanation of the reading, for the result card. */
    val note: String? = null,
    /** The model name when an AI model produced the reading. */
    val modelLabel: String? = null
) {
    /** Tags plus search terms, for handing the mood to YouTube Music or another app. */
    val query: String
        get() = (searchTerms + tags).joinToString(" ").take(120)

    val confidencePercent: Int get() = (confidence * 100).toInt().coerceIn(0, 100)
}
