package tech.egrie.soundtrail.scrobble

import org.json.JSONArray
import org.json.JSONObject

/**
 * One ListenBrainz listen. Persisted as JSON so listens detected while offline survive a process
 * death and can be submitted later with listen_type=import.
 */
data class Scrobble(
    /** Playback start time in unix seconds — what ListenBrainz expects. */
    var listenedAtSec: Long = 0L,
    var artist: String = "",
    var title: String = "",
    var release: String = "",
    /** ListenBrainz music_service domain supplied by the provider, e.g. "music.youtube.com". */
    var musicService: String = "",
    /** ListenBrainz origin_url supplied by the provider (where the track is available). */
    var originUrl: String = "",
    var durationMs: Long = 0L,
    /** How long the user actually listened, for additional_info.duration_played_ms. */
    var playedMs: Long = 0L
) {
    fun isSubmittable(): Boolean = artist.isNotEmpty() && title.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("at", listenedAtSec)
        put("ar", artist)
        put("ti", title)
        put("al", release)
        put("svc", musicService)
        put("url", originUrl)
        put("dur", durationMs)
        put("played", playedMs)
    }

    companion object {
        fun from(
            artist: String?, title: String?, album: String?,
            listenedAtSec: Long, durationMs: Long, playedMs: Long,
            musicService: String?, originUrl: String?
        ): Scrobble = Scrobble(
            listenedAtSec = listenedAtSec,
            artist = ScrobblePolicy.clean(artist),
            title = ScrobblePolicy.clean(title),
            release = ScrobblePolicy.clean(album),
            musicService = musicService?.trim().orEmpty(),
            originUrl = originUrl?.trim().orEmpty(),
            durationMs = maxOf(0L, durationMs),
            playedMs = maxOf(0L, playedMs)
        )

        fun fromJson(o: JSONObject?): Scrobble {
            if (o == null) return Scrobble()
            return Scrobble(
                listenedAtSec = o.optLong("at", 0L),
                artist = o.optString("ar", ""),
                title = o.optString("ti", ""),
                release = o.optString("al", ""),
                musicService = o.optString("svc", ""),
                originUrl = o.optString("url", ""),
                durationMs = o.optLong("dur", 0L),
                playedMs = o.optLong("played", 0L)
            )
        }

        fun toJsonArray(listens: List<Scrobble>): JSONArray {
            val array = JSONArray()
            for (listen in listens) {
                if (listen.isSubmittable()) array.put(listen.toJson())
            }
            return array
        }

        fun fromJsonArray(array: JSONArray?): List<Scrobble> {
            if (array == null) return emptyList()
            val out = ArrayList<Scrobble>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val listen = fromJson(o)
                if (listen.isSubmittable()) out.add(listen)
            }
            return out
        }
    }
}
