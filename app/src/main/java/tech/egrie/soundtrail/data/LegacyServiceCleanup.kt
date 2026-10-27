package tech.egrie.soundtrail.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tech.egrie.soundtrail.integrations.MusicLinkParser
import tech.egrie.soundtrail.integrations.SecretStore

/**
 * What an upgrade away from the streaming services leaves behind, and the one-time removal of it.
 *
 * Soundtrail used to pin songs from Spotify, SoundCloud and YouTube. Those services are gone, so
 * a library saved by an older version can still hold rows whose only content was a link this app
 * can no longer resolve, play, or even name. Leaving them in place would show songs that can never
 * play and never explain why; this removes them once, on the first launch after the upgrade, and
 * says out loud how many went.
 *
 * Two rules keep the removal honest:
 *
 * 1. **A song the listener owns survives.** A playlist row that carries an attached audio file
 *    keeps that file and loses only the dead link, because the file is theirs and it still plays
 *    with no service at all. Only a row whose *sole* content was the dead link is removed.
 * 2. **Nothing is removed twice, and nothing is removed silently.** The pass runs once, is
 *    recorded in [DONE_FLAG], and returns a [Report] the UI turns into one message.
 *
 * Removals propagate to other devices on their own: the stores are rewritten here, and the sync
 * journal diffs the new snapshot against its baseline, so each vanished row becomes a tombstone in
 * the next encrypted push. An older device that still has the services will drop the tombstone's
 * row too, which is the same answer reached from the other direction.
 *
 * The counting helpers read the stored JSON rather than the domain objects on purpose: a pin's
 * `provider` is non-nullable, so a row with a dead link cannot be constructed at all — by the time
 * it is a [MusicPin] it has already disappeared. Counting has to happen where the dead link still
 * exists, which is the raw document. They are free of Android types so they are unit-tested.
 */
internal object LegacyServiceCleanup {

    /**
     * Where the "already done" flag lives.
     *
     * This file is deliberately not one of the cloud-backed-up preference files, so a backup
     * restored from an older version does not carry the flag with it: the device it lands on runs
     * the cleanup itself instead of skipping it because some other phone already did.
     */
    private const val SETTINGS = "soundtrail_settings"
    private const val DONE_FLAG = "legacy_service_cleanup_v1"

    /** Playlist ids the removed YouTube import minted; a listener's own lists never use them. */
    private const val IMPORT_PREFIX = "yt-pl:"

    private const val PINS_PREFS = "soundtrail_pins"
    private const val PINS_KEY = "pins"
    private const val PLAYLISTS_PREFS = "soundtrail_playlists"
    private const val PLAYLISTS_KEY = "playlists"

    /**
     * Encrypted values only the removed services wrote.
     *
     * These are names, not patterns: the list is exact so that nothing else in the store can be
     * caught by it. The Jellyfin access token (`jellyfin_token`), the cloud-sync token and
     * recovery key (`cloud_sync_token`, `cloud_sync_recovery_key`) and the mood provider API keys
     * are all named differently and are never touched. Note the two bare names — Spotify's token
     * and its pending sign-in state were stored under `token` and `pending`, which is exactly why
     * nothing else in this app may use those two keys.
     */
    private val REMOVED_SECRETS = listOf(
        "token", "pending",                                    // Spotify
        "sc_token", "sc_pending", "soundcloud_client_secret",  // SoundCloud
        "youtube_oauth_token", "youtube_oauth_pending",        // YouTube OAuth
        "youtube_data_account", "youtube_data_account_name", "youtube_data_account_type"
    )

    /** Plain-text client ids the removed services kept in the shared settings file. */
    private val REMOVED_SETTINGS = listOf(
        "spotify_client_id", "soundcloud_client_id", "youtube_oauth_client_id"
    )

    /** Preference files only the removed services wrote. */
    private val REMOVED_PREF_FILES = listOf("soundtrail_youtube", "soundtrail_google_auth")

    /** What went, for the one message the listener sees. Zeroes mean nothing needed removing. */
    internal data class Report(
        val pins: Int = 0,
        val songs: Int = 0,
        val keptOwnAudio: Int = 0,
        val playlists: Int = 0
    ) {
        val isEmpty: Boolean get() = pins == 0 && songs == 0 && keptOwnAudio == 0 && playlists == 0

        /**
         * The sentence shown once after the upgrade. It names what was removed and why, and it
         * separates the songs that survived on their own audio from the ones that did not, so
         * nobody has to guess whether a file they attached was thrown away.
         */
        fun message(): String = buildString {
            val removed = listOfNotNull(
                if (pins > 0) "$pins ${plural(pins, "pin", "pins")}" else null,
                if (songs > 0) "$songs playlist ${plural(songs, "song", "songs")}" else null
            )
            if (removed.isNotEmpty()) {
                append("Removed ")
                append(removed.joinToString(" and "))
                append(" that pointed at a music service this version no longer supports.")
            }
            if (keptOwnAudio > 0) {
                if (isNotEmpty()) append(" ")
                append("$keptOwnAudio ${plural(keptOwnAudio, "song", "songs")} kept the audio file " +
                    "you attached and lost only the dead link.")
            }
            if (playlists > 0) {
                if (isNotEmpty()) append(" ")
                append("Removed $playlists empty ${plural(playlists, "list", "lists")} the old " +
                    "import had created.")
            }
        }

        private fun plural(count: Int, one: String, many: String): String =
            if (count == 1) one else many
    }

    /**
     * How many stored pins this version cannot play.
     *
     * A pin's link is what makes it a pin, so an unplayable link means the whole row goes. A row
     * with no link at all is not counted: it was already broken before the services were removed,
     * and blaming this upgrade for it would be a lie.
     */
    fun countUnplayablePins(pinsJson: String): Int =
        rows(pinsJson).count { entry ->
            MusicLinkParser.isUnplayable(storedLink(entry))
        }

    /**
     * How many playlist rows go, and how many survive on their own attached audio.
     *
     * @return removed rows to rows that keep their file but lose the dead link
     */
    fun countUnplayablePlaylistItems(playlistsJson: String): Pair<Int, Int> {
        var removed = 0
        var keptOwnAudio = 0
        rows(playlistsJson).forEach { playlist ->
            val items = runCatching { playlist.optJSONArray("items") }.getOrNull() ?: return@forEach
            (0 until items.length()).forEach { index ->
                val item = runCatching { items.getJSONObject(index) }.getOrNull() ?: return@forEach
                if (!MusicLinkParser.isUnplayable(storedLink(item))) return@forEach
                if (hasOwnAudio(item)) keptOwnAudio++ else removed++
            }
        }
        return removed to keptOwnAudio
    }

    /**
     * True when a playlist was created by the removed import and nothing is left in it.
     *
     * A list the listener named themselves is kept even when empty — it is theirs, and they may be
     * about to fill it. Only an app-minted import list with no songs in it is spent.
     */
    fun isSpentImport(playlistId: String, itemCount: Int): Boolean =
        itemCount == 0 && playlistId.startsWith(IMPORT_PREFIX)

    /**
     * Runs the removal once. Returns null when it has already run on this device, so the caller
     * can tell "nothing to report" from "already reported".
     *
     * Safe to call from a background thread; it reads and writes private preferences only, and
     * never touches the network. A failure part-way through leaves the flag unset, so the next
     * launch tries again — every step here is idempotent.
     */
    fun runOnce(context: Context, pins: PinStore, playlists: PlaylistStore): Report? {
        val settings = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        if (settings.getBoolean(DONE_FLAG, false)) return null

        val pinJson = context.getSharedPreferences(PINS_PREFS, Context.MODE_PRIVATE)
            .getString(PINS_KEY, "[]").orEmpty()
        val playlistJson = context.getSharedPreferences(PLAYLISTS_PREFS, Context.MODE_PRIVATE)
            .getString(PLAYLISTS_KEY, "[]").orEmpty()
        val removedPins = countUnplayablePins(pinJson)
        val (removedSongs, keptOwnAudio) = countUnplayablePlaylistItems(playlistJson)

        // The stores already refuse a dead link when they read, so writing back what they return
        // is what actually removes it from disk — and what makes the sync journal see the row go.
        if (removedPins > 0) pins.replaceAll(pins.all())
        val before = playlists.all()
        val kept = before.filterNot { isSpentImport(it.id, it.items.size) }
        if (removedSongs > 0 || keptOwnAudio > 0 || kept.size != before.size) {
            playlists.replaceAll(kept)
        }

        forgetServiceCredentials(context)

        // commit(), not apply(): this is the one flag that must not be lost, or the listener gets
        // the same message on every launch and the stores are rewritten every time.
        settings.edit().putBoolean(DONE_FLAG, true).commit()

        return Report(
            pins = removedPins,
            songs = removedSongs,
            keptOwnAudio = keptOwnAudio,
            playlists = before.size - kept.size
        )
    }

    /**
     * Deletes the credentials the removed services left in this device's encrypted store, their
     * plain-text client ids, and the two preference files only they wrote.
     *
     * An OAuth token nobody can revoke from inside the app any more is a live credential sitting
     * in encrypted storage with no UI to sign it out, so it goes. The Keystore key that wrapped
     * them is deliberately left alone: it is the same alias every other secret in the store uses.
     */
    private fun forgetServiceCredentials(context: Context) {
        val secrets = SecretStore(context)
        REMOVED_SECRETS.forEach(secrets::remove)

        val editor = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit()
        REMOVED_SETTINGS.forEach(editor::remove)
        editor.commit()

        REMOVED_PREF_FILES.forEach { name ->
            runCatching { context.deleteSharedPreferences(name) }
        }
    }

    /** The stored rows of one JSON array; a document that will not parse holds nothing to remove. */
    private fun rows(json: String): List<JSONObject> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching { array.getJSONObject(index) }.getOrNull()
        }
    }

    /** The link as it was stored, or null when the row never had one. */
    private fun storedLink(entry: JSONObject): String? =
        runCatching { entry.optString("url") }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "null" }

    /** True when the listener attached an audio file of their own to this row. */
    private fun hasOwnAudio(entry: JSONObject): Boolean =
        runCatching { entry.optString("localFile") }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "null" } != null
}
