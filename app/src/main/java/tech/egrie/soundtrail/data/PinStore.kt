package tech.egrie.soundtrail.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tech.egrie.soundtrail.integrations.MusicLinkParser

/** Private pin store; optional cloud sync exports only encrypted, user-authorized snapshots. */
class PinStore(
    context: Context,
    private val onChanged: (() -> Unit)? = null
) {
    private val prefs = context.getSharedPreferences("soundtrail_pins", Context.MODE_PRIVATE)

    fun all(): List<MusicPin> {
        val array = runCatching { JSONArray(prefs.getString("pins", "[]")) }.getOrNull()
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val link = MusicLinkParser.parse(item.getString("url")) ?: return@runCatching null
                val point = GeoPoint(item.getDouble("lat"), item.getDouble("lon"))
                if (!point.isValid()) return@runCatching null
                MusicPin(
                    id = item.getString("id"),
                    title = item.getString("title"),
                    artist = item.optString("artist"),
                    place = item.optString("place", "Somewhere special"),
                    note = item.optString("note"),
                    url = link.url,
                    provider = link.provider,
                    point = point,
                    createdAt = item.getLong("createdAt")
                )
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    fun add(pin: MusicPin) = replaceAll(all() + pin)

    /** Replace/insert one pin by stable id (used when applying a remote sync winner). */
    fun upsert(pin: MusicPin) = replaceAll(all().filterNot { it.id == pin.id } + pin)

    /** Applies a complete caller snapshot without extra reads or sync-journal events. */
    fun replaceAll(pins: List<MusicPin>) = save(pins)

    fun remove(id: String) = replaceAll(all().filterNot { it.id == id })

    /**
     * Restores pins from a backup: ids already on this device are kept as they are, so
     * importing is additive and can never overwrite a local edit. Returns how many were added.
     */
    fun merge(incoming: List<MusicPin>): Int {
        val have = all()
        val ids = have.mapTo(HashSet()) { it.id }
        val fresh = incoming.filterNot { it.id in ids }
        if (fresh.isNotEmpty()) save(have + fresh)
        return fresh.size
    }

    private fun save(pins: List<MusicPin>) {
        val array = JSONArray()
        pins.sortedByDescending { it.createdAt }.forEach { pin ->
            array.put(JSONObject().apply {
                put("id", pin.id)
                put("title", pin.title)
                put("artist", pin.artist)
                put("place", pin.place)
                put("note", pin.note)
                put("url", pin.url)
                put("lat", pin.point.latitude)
                put("lon", pin.point.longitude)
                put("createdAt", pin.createdAt)
            })
        }
        prefs.edit().putString("pins", array.toString()).apply()
        onChanged?.invoke()
    }
}
