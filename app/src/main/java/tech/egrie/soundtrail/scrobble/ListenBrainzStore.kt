package tech.egrie.soundtrail.scrobble

import android.content.Context

/**
 * ListenBrainz scrobbling settings. The user token lives in private SharedPreferences; never log it,
 * never put it in a URL, and mask it in the UI.
 */
class ListenBrainzStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Master switch. Off by default: scrobbling sends listening history off-device. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    /** ListenBrainz user token (from listenbrainz.org/settings). Never log this value. */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "")?.trim().orEmpty()
        set(value) {
            val clean = value.trim()
            prefs.edit().putString(KEY_TOKEN, clean).apply()
            if (clean.isEmpty()) prefs.edit().putString(KEY_USER, "").apply()
        }

    /** MusicBrainz user name cached from the last successful validate-token call. */
    var userName: String
        get() = prefs.getString(KEY_USER, "").orEmpty()
        set(value) { prefs.edit().putString(KEY_USER, value).apply() }

    /** Whether to send a playing_now update when a track starts (on by default). */
    var announceNowPlaying: Boolean
        get() = prefs.getBoolean(KEY_NOW_PLAYING, true)
        set(value) { prefs.edit().putBoolean(KEY_NOW_PLAYING, value).apply() }

    /** True when scrobbling is switched on and a token is present. */
    fun isConfigured(): Boolean = enabled && token.isNotEmpty()

    /** Masked token for UI display, e.g. "••••••1a2b". */
    fun maskedToken(): String {
        val t = token
        if (t.isEmpty()) return ""
        val tail = if (t.length <= 4) t else t.substring(t.length - 4)
        return "••••••$tail"
    }

    companion object {
        private const val PREFS = "soundtrail_listenbrainz"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "user_name"
        private const val KEY_NOW_PLAYING = "announce_now_playing"
    }
}
