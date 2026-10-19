package tech.egrie.soundtrail.data

import org.json.JSONArray
import org.json.JSONObject
import tech.egrie.soundtrail.automation.AutomationRule
import tech.egrie.soundtrail.automation.MotionKind
import tech.egrie.soundtrail.integrations.MusicLinkParser

/** Raised when a picked file is not a Soundtrail backup this version can read. */
class BackupException(message: String) : Exception(message)

/** Everything one backup file carried, before it is merged into the on-device stores. */
internal data class BackupContents(
    val pins: List<MusicPin>,
    val playlists: List<Playlist>,
    val automations: List<AutomationRule>
)

/**
 * One versioned JSON document holding every kind of local data Soundtrail keeps:
 * pins, playlists (with their songs), and automation rules.
 *
 * The point is that a reinstall or a new phone must not be a data-loss event. Account logins
 * are deliberately *not* included: those tokens live in Android Keystore-encrypted storage and
 * are re-entered by connecting again. Imported audio files are also excluded; they belong to the device
 * they were copied on, so a backup carries only the song entry (with its local-file name
 * kept as a hint for same-device restores).
 *
 * Parsing mirrors the on-device stores: a malformed entry is dropped, never fatal, so
 * one bad row cannot block restoring everything else. The document itself, however, must
 * identify as a Soundtrail backup of a readable version or the whole file is refused.
 */
internal object BackupFormat {
    const val FORMAT = "soundtrail-backup"
    const val VERSION = 1

    /** Largest text we will even try to parse; a real backup is orders of magnitude smaller. */
    const val MAX_CHARS = 20_000_000

    fun export(
        pins: List<MusicPin>,
        playlists: List<Playlist>,
        automations: List<AutomationRule>
    ): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("pins", JSONArray().also { array -> pins.forEach { array.put(pinJson(it)) } })
        root.put("playlists", JSONArray().also { array -> playlists.forEach { array.put(playlistJson(it)) } })
        root.put("automations", JSONArray().also { array -> automations.forEach { array.put(ruleJson(it)) } })
        return root.toString(2)
    }

    fun parse(text: String): BackupContents {
        if (text.length > MAX_CHARS) {
            throw BackupException("That file is too large to be a Soundtrail backup.")
        }
        val root = runCatching { JSONObject(text) }.getOrElse {
            throw BackupException("That file is not readable as a Soundtrail backup.")
        }
        if (root.optString("format") != FORMAT) {
            throw BackupException("That file is not a Soundtrail backup.")
        }
        val version = runCatching { root.getInt("version") }.getOrElse {
            throw BackupException("That backup has no usable version number.")
        }
        if (version < 1) {
            throw BackupException("That backup has no usable version number.")
        }
        if (version > VERSION) {
            throw BackupException("That backup was made by a newer Soundtrail — update the app, then retry.")
        }
        return BackupContents(
            pins = parseList(root, "pins", ::parsePin),
            playlists = parseList(root, "playlists", ::parsePlaylist),
            automations = parseList(root, "automations", ::parseRule)
        )
    }

    /** Skips entries that do not validate, exactly as the live stores do. */
    private fun <T> parseList(root: JSONObject, key: String, parseEntry: (JSONObject) -> T?): List<T> {
        val array = root.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching { parseEntry(array.getJSONObject(index)) }.getOrNull()
        }
    }

    // ----- Pins -----

    internal fun pinJson(pin: MusicPin): JSONObject = JSONObject().apply {
        put("id", pin.id)
        put("title", pin.title)
        put("artist", pin.artist)
        put("place", pin.place)
        put("note", pin.note)
        put("url", pin.url)
        put("lat", pin.point.latitude)
        put("lon", pin.point.longitude)
        put("createdAt", pin.createdAt)
    }

    internal fun parsePin(entry: JSONObject): MusicPin? {
        val link = MusicLinkParser.parse(entry.getString("url")) ?: return null
        val point = GeoPoint(entry.getDouble("lat"), entry.getDouble("lon"))
        if (!point.isValid()) return null
        return MusicPin(
            id = entry.getString("id"),
            title = entry.getString("title"),
            artist = entry.optString("artist"),
            place = entry.optString("place", "Somewhere special"),
            note = entry.optString("note"),
            url = link.url,
            provider = link.provider,
            point = point,
            createdAt = entry.getLong("createdAt")
        )
    }

    // ----- Playlists -----

    private fun playlistJson(playlist: Playlist): JSONObject = JSONObject().apply {
        put("id", playlist.id)
        put("name", playlist.name)
        put("createdAt", playlist.createdAt)
        put("items", JSONArray().also { array -> playlist.items.forEach { array.put(playlistItemJson(it)) } })
    }

    private fun playlistItemJson(item: PlaylistItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("title", item.title)
        put("artist", item.artist)
        item.url?.let { put("url", it) }
        item.localFile?.let { put("localFile", it) }
        put("createdAt", item.createdAt)
    }

    private fun parsePlaylist(entry: JSONObject): Playlist? {
        val items = entry.optJSONArray("items") ?: JSONArray()
        return Playlist(
            id = entry.getString("id"),
            name = entry.getString("name"),
            createdAt = entry.getLong("createdAt"),
            items = (0 until items.length()).mapNotNull { index ->
                runCatching {
                    val item = items.getJSONObject(index)
                    // A song with an unusable link is still a song: links are optional on
                    // playlist entries, exactly as when they were created by hand.
                    val url = item.optString("url").takeIf { it.isNotBlank() && it != "null" }
                    PlaylistItem(
                        id = item.getString("id"),
                        title = item.getString("title"),
                        artist = item.optString("artist"),
                        url = url,
                        provider = url?.let { MusicLinkParser.parse(it)?.provider },
                        createdAt = item.getLong("createdAt"),
                        localFile = item.optString("localFile")
                            .takeIf { it.isNotBlank() && it != "null" }
                    )
                }.getOrNull()
            }
        )
    }

    // ----- Automations -----

    internal fun ruleJson(rule: AutomationRule): JSONObject = JSONObject().apply {
        put("id", rule.id)
        put("name", rule.name)
        put("playlistId", rule.playlistId)
        put("enabled", rule.enabled)
        rule.place?.let { put("place", JSONObject().put("lat", it.latitude).put("lon", it.longitude)) }
        put("radius", rule.radiusMeters)
        rule.activity?.let { put("activity", it.name) }
        rule.fromMinute?.let { put("fromMinute", it) }
        rule.toMinute?.let { put("toMinute", it) }
        put("cooldown", rule.cooldownMinutes)
    }

    internal fun parseRule(entry: JSONObject): AutomationRule? {
        val place = entry.optJSONObject("place")?.let {
            GeoPoint(it.getDouble("lat"), it.getDouble("lon"))
        }?.takeIf(GeoPoint::isValid)
        val activity = entry.optString("activity").takeIf { it.isNotBlank() && it != "null" }
            ?.let { name -> MotionKind.entries.firstOrNull { it.name == name } }
        return AutomationRule(
            id = entry.getString("id"),
            name = entry.getString("name"),
            playlistId = entry.getString("playlistId"),
            enabled = entry.optBoolean("enabled", true),
            place = place,
            radiusMeters = entry.optDouble("radius", 150.0),
            activity = activity,
            fromMinute = entry.optInt("fromMinute", -1).takeIf { it >= 0 },
            toMinute = entry.optInt("toMinute", -1).takeIf { it >= 0 },
            cooldownMinutes = entry.optLong("cooldown", AutomationRule.DEFAULT_COOLDOWN_MINUTES)
        )
    }
}
