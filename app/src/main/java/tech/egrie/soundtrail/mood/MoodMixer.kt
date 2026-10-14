package tech.egrie.soundtrail.mood

import tech.egrie.soundtrail.integrations.MusicProvider
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** One song from the listener's own library, with whatever the app knows about it. */
internal data class MoodCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val url: String?,
    val provider: MusicProvider?,
    /** Where it came from: "Pin" or the playlist's name. */
    val origin: String,
    val tags: List<String> = emptyList(),
    val valence: Double? = null,
    val energy: Double? = null,
    val tempo: Int? = null,
    /** "TheAudioDB" when the mood/genre tags came from a tag lookup. */
    val tagSource: String? = null,
    val createdAt: Long = 0L
)

/** A ranked song in a mood mix, with the reasons it was picked. */
internal data class MoodMixTrack(
    val candidate: MoodCandidate,
    val score: Double,
    val reasons: List<String>
) {
    val title: String get() = candidate.title
    val artist: String get() = candidate.artist
    val url: String? get() = candidate.url
    val provider: MusicProvider? get() = candidate.provider
    val origin: String get() = candidate.origin

    /** 0–100, for the small match badge on each row. */
    val matchPercent: Int get() = (score * 100).roundToInt().coerceIn(0, 100)

    /** A short "3 tracks" style identity, used to key lists and saved playlists. */
    val key: String get() = candidate.id
}

/**
 * Scores the local library against a [MoodProfile].
 *
 * Two things matter here beyond plain sorting. First, uncertainty is not punished: a song whose
 * genre and energy are unknown is scored at neutral (0.5) for those attributes rather than
 * being buried, so a library with no metadata at all still produces a usable mix. Second, the
 * result is explainable: every row carries the reasons it was picked, so the listener can see
 * why "Rainy Piano" matched "Calm" and correct it by tagging the track or by running a lookup.
 */
internal object MoodMixer {
    private const val BASE = 0.20
    private const val VALENCE_WEIGHT = 0.26
    private const val ENERGY_WEIGHT = 0.30
    private const val TEMPO_WEIGHT = 0.08
    private const val TAG_WEIGHT = 0.16

    fun build(
        profile: MoodProfile,
        candidates: List<MoodCandidate>,
        limit: Int = 20,
        perArtistCap: Int = 2
    ): List<MoodMixTrack> {
        val scored = candidates
            .distinctBy { candidate -> candidate.url ?: "${candidate.title}|${candidate.artist}".lowercase() }
            .map { candidate -> score(profile, candidate) }
            .sortedWith(
                compareByDescending<MoodMixTrack> { it.score }
                    .thenByDescending { it.candidate.createdAt }
                    .thenBy { it.title.lowercase() }
            )

        // Keep the mix varied: at most `perArtistCap` songs per artist up front, the rest after.
        val kept = mutableListOf<MoodMixTrack>()
        val deferred = mutableListOf<MoodMixTrack>()
        val seen = mutableMapOf<String, Int>()
        scored.forEach { track ->
            val artist = track.artist.trim().lowercase().ifBlank { "unknown" }
            val used = seen[artist] ?: 0
            if (used < perArtistCap) {
                seen[artist] = used + 1
                kept += track
            } else {
                deferred += track
            }
        }
        return (kept + deferred).take(limit)
    }

    /** Songs the app still knows nothing about, which a tag lookup would improve. */
    fun untagged(candidates: List<MoodCandidate>): List<MoodCandidate> = candidates.filter { candidate ->
        candidate.tags.isEmpty() && candidate.valence == null && candidate.energy == null &&
            TrackMoodTags.fromText(candidate.title, candidate.artist, candidate.origin).isEmpty
    }

    private fun score(profile: MoodProfile, candidate: MoodCandidate): MoodMixTrack {
        val textTags = TrackMoodTags.fromText(candidate.title, candidate.artist, candidate.origin)
        val tags = (candidate.tags + textTags.tags).map { it.lowercase() }.distinct()
        val valence = candidate.valence ?: textTags.valence
        val energy = candidate.energy ?: textTags.energy
        val tempo = candidate.tempo ?: textTags.tempo
        val reasons = mutableListOf<String>()

        val valenceFactor = valence?.let { known ->
            (1.0 - abs(known - profile.valence)).coerceIn(0.0, 1.0).also { factor ->
                if (factor > 0.82) reasons += "mood fits"
            }
        } ?: 0.5

        val energyFactor = energy?.let { known ->
            (1.0 - abs(known - profile.energy)).coerceIn(0.0, 1.0).also { factor ->
                if (factor > 0.82) reasons += "energy fits"
            }
        } ?: 0.5

        val tempoFactor = tempo?.let { known ->
            (1.0 - min(1.0, abs(known - profile.tempo) / 70.0)).coerceIn(0.0, 1.0)
        } ?: 0.5

        val overlap = tags.filter { profile.tags.contains(it) }
        val tagFactor = min(1.0, overlap.size / 2.0)
        if (overlap.isNotEmpty()) {
            val source = candidate.tagSource ?: textTags.origin
            reasons += overlap.take(3).joinToString(", ") + source?.let { " \u00b7 $it" }.orEmpty()
        }

        val score = (
            BASE +
                VALENCE_WEIGHT * valenceFactor +
                ENERGY_WEIGHT * energyFactor +
                TEMPO_WEIGHT * tempoFactor +
                TAG_WEIGHT * tagFactor
            ).coerceIn(0.0, 1.0)

        if (reasons.isEmpty()) {
            reasons += if (valence == null && energy == null) {
                "no sound tags yet"
            } else {
                "close on sound"
            }
        }
        return MoodMixTrack(candidate, score, reasons)
    }
}
