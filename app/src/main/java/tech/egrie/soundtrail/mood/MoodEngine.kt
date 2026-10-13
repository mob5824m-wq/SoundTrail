package tech.egrie.soundtrail.mood

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The on-device mood engine.
 *
 * It reads a plain-language description of how the listener feels ("low and tired but I want
 * something warm for the drive home") and turns it into a [MoodProfile]: a recognised mood plus
 * target audio features. Everything happens on the phone — no key, no network, nothing leaves
 * the device — which is why this is always available as the fallback when an optional AI model
 * is not configured or cannot be reached.
 *
 * The reading is a small, explainable bag-of-words model with negation, intensifiers, phrases,
 * emoji, and context (time of day and on-device motion). It is deliberately readable: each
 * score can be traced back to the words that produced it, and [MoodProfile.matchedWords] shows
 * the listener exactly which ones counted.
 */
internal object MoodEngine {
    private const val NEGATION_WINDOW = 3

    private data class Scored(val moodId: String, val weight: Double)

    private val words: Map<String, List<Scored>>
    private val phrases: Map<String, List<Scored>>

    /** Emoji the engine understands, with the weight they carry. */
    private val emoji: List<Triple<String, String, Double>> = listOf(
        Triple("\uD83D\uDE0A", "happy", 1.4),        // 😊
        Triple("\uD83D\uDE04", "happy", 1.5),        // 😄
        Triple("\uD83E\uDD73", "happy", 1.5),        // 🥳
        Triple("\uD83D\uDE0D", "romantic", 1.5),     // 😍
        Triple("\uD83E\uDD70", "romantic", 1.3),     // 🥰
        Triple("\uD83D\uDE22", "sad", 1.6),          // 😢
        Triple("\uD83D\uDE2D", "sad", 1.7),          // 😭
        Triple("\uD83D\uDE14", "sad", 1.1),          // 😔
        Triple("\uD83E\uDD71", "sad", 1.2),          // 🥱 (tired/bored)
        Triple("\uD83D\uDE20", "angry", 1.5),        // 😠
        Triple("\uD83D\uDE24", "angry", 1.6),        // 😤
        Triple("\uD83D\uDE30", "anxious", 1.5),      // 😰
        Triple("\uD83D\uDE1F", "anxious", 1.3),      // 😟
        Triple("\uD83D\uDE34", "sleepy", 1.5),       // 😴
        Triple("\uD83C\uDF19", "sleepy", 1.1),       // 🌙
        Triple("\uD83D\uDD25", "hype", 1.4),         // 🔥
        Triple("\uD83D\uDCAA", "hype", 1.4),         // 💪
        Triple("\uD83C\uDF89", "party", 1.5),        // 🎉
        Triple("\uD83D\uDE0E", "confident", 1.3),    // 😎
        Triple("\uD83D\uDE4C", "calm", 1.1)          // 🙏
    )

    private val negators = setOf(
        "not", "no", "never", "dont", "cant", "cannot", "wont", "without", "isnt", "arent",
        "wasnt", "werent", "aint", "barely", "hardly", "nor", "nothing", "nothin", "stop"
    )

    private val intensifiers = mapOf(
        "very" to 1.5, "really" to 1.45, "so" to 1.35, "super" to 1.5, "extremely" to 1.7,
        "incredibly" to 1.6, "totally" to 1.3, "absolutely" to 1.5, "completely" to 1.4,
        "proper" to 1.3, "deeply" to 1.45, "seriously" to 1.3, "hella" to 1.5, "mad" to 1.2
    )

    private val dampeners = mapOf(
        "slightly" to 0.6, "bit" to 0.65, "kinda" to 0.7, "kindof" to 0.7, "little" to 0.65,
        "somewhat" to 0.7, "mildly" to 0.6, "sorta" to 0.7, "ish" to 0.7, "fairly" to 0.85,
        "pretty" to 1.1
    )

    init {
        val wordIndex = mutableMapOf<String, MutableList<Scored>>()
        val phraseIndex = mutableMapOf<String, MutableList<Scored>>()
        MoodCatalog.moods.forEach { mood ->
            mood.words.forEach { raw ->
                val token = normalize(raw)
                if (token.isBlank()) return@forEach
                if (token.contains(' ')) {
                    phraseIndex.getOrPut(token) { mutableListOf() } += Scored(mood.id, 1.0)
                } else {
                    wordIndex.getOrPut(token) { mutableListOf() } += Scored(mood.id, 1.0)
                }
            }
            mood.strongWords.forEach { raw ->
                val token = normalize(raw)
                if (token.isBlank()) return@forEach
                if (token.contains(' ')) {
                    phraseIndex.getOrPut(token) { mutableListOf() } += Scored(mood.id, 1.6)
                } else {
                    wordIndex.getOrPut(token) { mutableListOf() } += Scored(mood.id, 1.6)
                }
            }
        }
        words = wordIndex
        phrases = phraseIndex
    }

    /** Chips shown above the mood field: an id from [MoodCatalog] plus its label. */
    val quickPicks: List<Pair<String, String>> = MoodCatalog.moods.map { it.id to it.label }

    /**
     * Reads [text] (and whatever context is available) and returns the mood profile.
     * Never throws and never returns null: with no usable words it still returns a gentle,
     * low-confidence reading built from the time of day alone.
     */
    fun analyze(text: String, context: MoodContext = MoodContext()): MoodProfile {
        val normalized = normalize(text)
        val scores = linkedMapOf<String, Double>()
        val matched = mutableListOf<Pair<String, Double>>()
        var signal = 0.0

        // `counted` marks evidence that came from the listener's own words, which is what the
        // confidence figure is built from; context and energy tilts steer the result but do not
        // pretend to be evidence.
        fun add(moodId: String, weight: Double, evidence: String?, counted: Boolean = true) {
            if (weight == 0.0) return
            val mood = MoodCatalog.find(moodId) ?: return
            scores[mood.id] = (scores[mood.id] ?: 0.0) + weight
            if (counted) signal += abs(weight)
            if (evidence != null && weight > 0) matched += evidence to weight
            if (weight < 0) {
                moodCounterparts[mood.id]?.let { counterpart ->
                    scores[counterpart] = (scores[counterpart] ?: 0.0) + (-weight * 0.7)
                }
            }
        }

        // 1. Multi-word phrases first ("burnt out", "road trip").
        phrases.forEach { (phrase, hits) ->
            if (phrase.isNotBlank() && normalized.contains(phrase)) {
                hits.forEach { hit -> add(hit.moodId, hit.weight, phrase) }
            }
        }

        // 2. Single words, with negation scope and intensifiers/dampeners.
        val tokens = normalized.split(' ').filter { it.isNotBlank() }
        var negatedUntil = -1
        var multiplier = 1.0
        tokens.forEachIndexed { index, token ->
            val bare = token
            if (negators.contains(bare)) {
                negatedUntil = index + NEGATION_WINDOW
                return@forEachIndexed
            }
            intensifiers[bare]?.let { multiplier *= it; return@forEachIndexed }
            dampeners[bare]?.let { multiplier *= it; return@forEachIndexed }
            val hits = wordIndexFor(bare) ?: return@forEachIndexed
            val negated = index <= negatedUntil
            hits.forEach { hit ->
                val weight = hit.weight * multiplier * (if (negated) -1.0 else 1.0)
                add(hit.moodId, weight, if (negated) "not $bare" else bare)
            }
            multiplier = 1.0
        }

        // 3. Emoji speak too, and they are not affected by word-level negation.
        emoji.forEach { (glyph, moodId, weight) ->
            if (text.contains(glyph)) add(moodId, weight, glyph)
        }

        // 4. Context: a tilt, never a verdict.
        contextBoosts(context).forEach { (moodId, boost) -> add(moodId, boost, null, counted = false) }

        // 5. An explicit energy hint steers every mood, and dominates when no words were given.
        context.energyHint?.let { hint ->
            val bounded = hint.coerceIn(0.0, 1.0)
            MoodCatalog.moods.forEach { mood ->
                val gap = abs(mood.energy - bounded)
                val bonus = max(0.0, 0.7 - gap * 1.1)
                if (bonus > 0.0) {
                    add(mood.id, bonus * if (signal > 0.0) 0.5 else 1.0, null, counted = false)
                }
            }
        }

        val ordered = scores.entries
            .filter { it.value > 0.0 }
            .sortedByDescending { it.value }
        if (ordered.isEmpty()) {
            val fallback = MoodCatalog.find("calm") ?: MoodCatalog.moods.first()
            return profileFor(
                fallback,
                MoodSource.ON_DEVICE,
                confidence = 0.18,
                matched = emptyList(),
                supporting = emptyList(),
                note = "Tell me how you feel — even three words is enough.",
                energyHint = context.energyHint,
                valenceOverride = null,
                energyOverride = null,
                modelLabel = null
            )
        }

        val top = ordered.first()
        val total = ordered.sumOf { it.value }.coerceAtLeast(0.0001)
        val spread = top.value / total
        val signalScore = min(1.0, signal / 3.0)
        val confidence = (0.32 + 0.40 * spread + 0.24 * signalScore).coerceIn(0.20, 0.95)

        val picks = ordered.take(3).mapNotNull { entry ->
            MoodCatalog.find(entry.key)?.let { it to entry.value }
        }
        val primary = picks.first().first
        val shareTotal = picks.sumOf { it.second }.coerceAtLeast(0.0001)
        val weights = picks.map { it.second / shareTotal }

        val valence = picks.mapIndexed { index, pick -> pick.first.valence * weights[index] }.sum()
        val energyRaw = picks.mapIndexed { index, pick -> pick.first.energy * weights[index] }.sum()
        val tempo = picks.mapIndexed { index, pick -> pick.first.tempo * weights[index] }.sum()
        val dance = picks.mapIndexed { index, pick -> pick.first.danceability * weights[index] }.sum()
        val acoustic = picks.mapIndexed { index, pick -> pick.first.acousticness * weights[index] }.sum()
        val instrumental =
            picks.mapIndexed { index, pick -> pick.first.instrumentalness * weights[index] }.sum()
        val tags = picks.flatMap { it.first.tags }.distinct().take(8)
        val supporting = picks.drop(1).mapIndexed { index, pick ->
            pick.first.label to weights[index + 1]
        }

        return profileFor(
            primary,
            MoodSource.ON_DEVICE,
            confidence = confidence,
            matched = matched.sortedByDescending { it.second }.map { it.first }.distinct(),
            supporting = supporting,
            note = noteFor(matched, context),
            energyHint = context.energyHint,
            valenceOverride = valence,
            energyOverride = energyRaw,
            tempoOverride = tempo,
            danceOverride = dance,
            acousticOverride = acoustic,
            instrumentalOverride = instrumental,
            tagOverride = tags,
            modelLabel = null
        )
    }

    /** A direct reading for a mood the listener picked from the chips, not typed. */
    fun forMood(id: String, context: MoodContext = MoodContext(), confidence: Double = 0.9): MoodProfile? {
        val mood = MoodCatalog.find(id) ?: return null
        return profileFor(
            mood,
            MoodSource.ON_DEVICE,
            confidence = confidence.coerceIn(0.2, 0.98),
            matched = emptyList(),
            supporting = emptyList(),
            note = "Picked from the mood list",
            energyHint = context.energyHint,
            valenceOverride = null,
            energyOverride = null,
            modelLabel = null
        )
    }

    /**
     * Assembles a [MoodProfile] from a mood definition plus any overrides. Shared by the
     * on-device engine, the chip picks, and the optional AI model so every path produces the
     * same shape of result.
     */
    fun profileFor(
        mood: MoodDefinition,
        source: MoodSource,
        confidence: Double,
        matched: List<String>,
        supporting: List<Pair<String, Double>>,
        note: String?,
        energyHint: Double?,
        valenceOverride: Double?,
        energyOverride: Double?,
        tempoOverride: Double? = null,
        danceOverride: Double? = null,
        acousticOverride: Double? = null,
        instrumentalOverride: Double? = null,
        tagOverride: List<String>? = null,
        modelLabel: String? = null
    ): MoodProfile {
        val valence = (valenceOverride ?: mood.valence).coerceIn(0.0, 1.0)
        val energyRaw = (energyOverride ?: mood.energy).coerceIn(0.0, 1.0)
        val energy = if (energyHint != null) {
            (energyRaw * 0.45 + energyHint.coerceIn(0.0, 1.0) * 0.55).coerceIn(0.0, 1.0)
        } else {
            energyRaw
        }
        return MoodProfile(
            id = mood.id,
            label = mood.label,
            blurb = mood.blurb,
            source = source,
            confidence = confidence,
            valence = valence,
            energy = energy,
            tempo = (tempoOverride ?: mood.tempo.toDouble()).roundToInt(),
            danceability = (danceOverride ?: mood.danceability).coerceIn(0.0, 1.0),
            acousticness = (acousticOverride ?: mood.acousticness).coerceIn(0.0, 1.0),
            instrumentalness = (instrumentalOverride ?: mood.instrumentalness).coerceIn(0.0, 1.0),
            tags = (tagOverride ?: mood.tags).map { it.lowercase() }.distinct().take(10),
            searchTerms = mood.searchTerms,
            matchedWords = matched.take(6),
            supporting = supporting.take(3),
            note = note,
            modelLabel = modelLabel
        )
    }

    /** Lower-case, accent-free, apostrophe-free form of the listener's words. */
    fun normalize(input: String): String {
        val withoutMarks = runCatching {
            Normalizer.normalize(input.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
        }.getOrElse { input.lowercase() }
        // Apostrophes are dropped rather than spaced out, so "don't", "can't", and "won't" come
        // out as the negators the lexicon already knows ("dont", "cant", "wont") and keep their
        // power to flip the mood of the word that follows them.
        return withoutMarks
            .replace("'", "")
            .replace("\u2019", "")
            .replace("\u2018", "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    /** Tries the token, then a couple of cheap stem forms, against the lexicon. */
    private fun wordIndexFor(token: String): List<Scored>? {
        words[token]?.let { return it }
        val stems = buildList {
            if (token.length > 4 && token.endsWith("ing")) add(token.dropLast(3))
            if (token.length > 3 && token.endsWith("ed")) add(token.dropLast(2))
            if (token.length > 3 && token.endsWith("es")) add(token.dropLast(2))
            if (token.length > 2 && token.endsWith("s")) add(token.dropLast(1))
            if (token.length > 3 && token.endsWith("ly")) add(token.dropLast(2))
        }
        stems.forEach { stem ->
            words[stem]?.let { return it }
            if (stem.length > 3 && stem.lastOrNull() == stem.getOrNull(stem.length - 2)) {
                words[stem.dropLast(1)]?.let { return it }
            }
        }
        return null
    }

    /** Time of day and on-device motion, expressed as small score bonuses. */
    private fun contextBoosts(context: MoodContext): Map<String, Double> {
        val boosts = mutableMapOf<String, Double>()
        fun bump(moodId: String, value: Double) {
            boosts[moodId] = (boosts[moodId] ?: 0.0) + value
        }
        context.hourOfDay?.let { hour ->
            when (hour.coerceIn(0, 23)) {
                in 5..9 -> { bump("happy", 0.5); bump("motivated", 0.35); bump("focus", 0.2) }
                in 10..12 -> { bump("focus", 0.3); bump("confident", 0.2) }
                in 13..16 -> { bump("focus", 0.35); bump("motivated", 0.25); bump("happy", 0.15) }
                in 17..20 -> { bump("party", 0.45); bump("romantic", 0.3); bump("confident", 0.25); bump("nostalgic", 0.2) }
                in 21..23 -> { bump("calm", 0.4); bump("romantic", 0.3); bump("nostalgic", 0.35) }
                else -> { bump("sleepy", 0.6); bump("calm", 0.4); bump("lonely", 0.2) }
            }
        }
        when (context.motion?.lowercase()) {
            "driving" -> { bump("confident", 0.5); bump("hype", 0.3); bump("nostalgic", 0.3) }
            "running" -> { bump("hype", 0.7); bump("motivated", 0.4) }
            "walking" -> { bump("happy", 0.35); bump("nostalgic", 0.25); bump("calm", 0.15) }
        }
        return boosts
    }

    private fun noteFor(matched: List<Pair<String, Double>>, context: MoodContext): String? {
        val bits = mutableListOf<String>()
        val words = matched.sortedByDescending { it.second }.map { it.first }.distinct().take(4)
        if (words.isNotEmpty()) bits += "heard \u201C${words.joinToString("\u201D, \u201C")}\u201D"
        context.hourOfDay?.let { bits += timeOfDay(it) }
        context.motion?.lowercase()?.takeIf { it != "stationary" }?.let { bits += "you're $it" }
        if (bits.isEmpty()) return null
        return bits.joinToString(" \u00b7 ").replaceFirstChar { it.uppercase() }
    }

    private fun timeOfDay(hour: Int): String = when (hour.coerceIn(0, 23)) {
        in 0..4 -> "late night"
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }
}
