package tech.egrie.soundtrail.scrobble

/**
 * Pure listen-detection rules for ListenBrainz scrobbling.
 *
 * ListenBrainz counts a listen once the user has played half the track or four minutes,
 * whichever comes first. Kept free of Android types so it can be unit-tested on the JVM.
 */
object ScrobblePolicy {
    /** Four minutes: the most anyone has to play before a listen counts. */
    const val MAX_THRESHOLD_MS = 4L * 60L * 1000L
    /** Longest artist / title we send; the server rejects listens over 10 KB. */
    const val MAX_FIELD_LENGTH = 500
    /** Ticks larger than this are treated as a resume-from-background gap, not listening. */
    const val MAX_TICK_MS = 15_000L

    /** Play time needed before a listen counts. */
    fun thresholdMs(durationMs: Long): Long {
        if (durationMs <= 0L) return MAX_THRESHOLD_MS
        return minOf(durationMs / 2L, MAX_THRESHOLD_MS)
    }

    fun shouldScrobble(playedMs: Long, durationMs: Long): Boolean =
        playedMs > 0L && playedMs >= thresholdMs(durationMs)

    /**
     * Normalises a metadata string: strips NUL (rejected by the server), collapses whitespace,
     * trims and caps the length.
     */
    fun clean(value: String?): String {
        if (value == null) return ""
        var cleaned = value.replace("\u0000", "").trim().replace(Regex("\\s+"), " ")
        if (cleaned.length > MAX_FIELD_LENGTH) {
            cleaned = cleaned.substring(0, MAX_FIELD_LENGTH).trim()
        }
        return cleaned
    }

    /** ListenBrainz requires both artist_name and track_name to be non-empty. */
    fun isSubmittable(artist: String?, title: String?): Boolean =
        clean(artist).isNotEmpty() && clean(title).isNotEmpty()
}
