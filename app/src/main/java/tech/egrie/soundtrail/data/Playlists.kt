package tech.egrie.soundtrail.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tech.egrie.soundtrail.integrations.MusicLinkParser
import tech.egrie.soundtrail.integrations.MusicProvider

/**
 * One song inside a playlist. A song may be link-less until a supported link is added,
 * and may carry a locally imported audio file that plays offline.
 */
data class PlaylistItem(
    val id: String,
    val title: String,
    val artist: String,
    val url: String?,
    val provider: MusicProvider?,
    val createdAt: Long,
    val localFile: String? = null
)

data class Playlist(
    val id: String,
    val name: String,
    val items: List<PlaylistItem>,
    val createdAt: Long
)

/** Result of merging a backup's playlists into what a device already has. */
internal data class PlaylistMerge(
    val lists: List<Playlist>,
    val addedPlaylists: Int,
    val addedItems: Int
)

/**
 * Pure restore rules for playlists: unknown playlists arrive whole, known ones only gain
 * songs whose ids they do not already carry (so a re-import is a no-op), and nothing that
 * exists locally is ever overwritten. Kept free of Android types so it is unit-testable.
 */
internal fun mergePlaylists(existing: List<Playlist>, incoming: List<Playlist>): PlaylistMerge {
    var lists = existing
    var addedPlaylists = 0
    var addedItems = 0
    incoming.forEach { brought ->
        val known = lists.firstOrNull { it.id == brought.id }
        if (known == null) {
            lists = lists + brought
            addedPlaylists += 1
            addedItems += brought.items.size
        } else {
            val ids = known.items.mapTo(HashSet()) { it.id }
            val fresh = brought.items.filterNot { it.id in ids }
            if (fresh.isNotEmpty()) {
                lists = lists.map {
                    if (it.id == brought.id) it.copy(items = it.items + fresh) else it
                }
                addedItems += fresh.size
            }
        }
    }
    return PlaylistMerge(lists, addedPlaylists, addedItems)
}

/** Private playlist store; optional sync sends only encrypted metadata, never audio files. */
class PlaylistStore(
    context: Context,
    private val onChanged: (() -> Unit)? = null
) {
    private val prefs = context.getSharedPreferences("soundtrail_playlists", Context.MODE_PRIVATE)

    fun all(): List<Playlist> {
        val array = runCatching { JSONArray(prefs.getString("playlists", "[]")) }.getOrNull()
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val entry = array.getJSONObject(index)
                val items = entry.optJSONArray("items") ?: JSONArray()
                Playlist(
                    id = entry.getString("id"),
                    name = entry.getString("name"),
                    items = (0 until items.length()).mapNotNull { itemIndex ->
                        runCatching {
                            val item = items.getJSONObject(itemIndex)
                            val storedUrl = item.optString("url").takeIf { it.isNotBlank() && it != "null" }
                            val localFile = item.optString("localFile")
                                .takeIf { it.isNotBlank() && it != "null" }
                            // A link from a service this version no longer supports is not a
                            // source. The song survives on its own attached audio file; without
                            // one there is nothing left that could ever play, so the row goes.
                            val dead = MusicLinkParser.isUnplayable(storedUrl)
                            if (dead && localFile == null) return@runCatching null
                            val link = if (dead) null else storedUrl?.let(MusicLinkParser::parse)
                            PlaylistItem(
                                id = item.getString("id"),
                                title = item.getString("title"),
                                artist = item.optString("artist"),
                                url = link?.url,
                                provider = link?.provider,
                                createdAt = item.getLong("createdAt"),
                                localFile = localFile
                            )
                        }.getOrNull()
                    },
                    createdAt = entry.getLong("createdAt")
                )
            }.getOrNull()
        }.sortedBy { it.createdAt }
    }

    fun find(id: String): Playlist? = all().firstOrNull { it.id == id }

    fun save(playlist: Playlist) {
        replaceAll(all().filterNot { it.id == playlist.id } + playlist)
    }

    /** Applies a complete caller snapshot without extra reads or sync-journal events. */
    fun replaceAll(playlists: List<Playlist>) = write(playlists.sortedBy { it.createdAt })

    fun remove(id: String) = replaceAll(all().filterNot { it.id == id })

    /**
     * Restores playlists from a backup. Unknown playlists arrive whole; known ones only
     * gain the songs they do not have yet (matched by id), so a re-import changes nothing.
     * Returns (playlists added, songs added).
     */
    fun merge(incoming: List<Playlist>): Pair<Int, Int> {
        val before = all()
        val merged = mergePlaylists(before, incoming)
        if (merged.addedPlaylists > 0 || merged.addedItems > 0) write(merged.lists.sortedBy { it.createdAt })
        return merged.addedPlaylists to merged.addedItems
    }

    fun addItem(playlistId: String, item: PlaylistItem): Boolean {
        val current = all()
        val index = current.indexOfFirst { it.id == playlistId }
        if (index < 0) return false
        replaceAll(current.mapIndexed { currentIndex, playlist ->
            if (currentIndex == index) playlist.copy(items = playlist.items + item) else playlist
        })
        return true
    }

    fun removeItem(playlistId: String, itemId: String): Boolean {
        val current = all()
        val index = current.indexOfFirst { it.id == playlistId }
        if (index < 0) return false
        if (current[index].items.none { it.id == itemId }) return true
        replaceAll(current.mapIndexed { currentIndex, playlist ->
            if (currentIndex == index) {
                playlist.copy(items = playlist.items.filterNot { it.id == itemId })
            } else playlist
        })
        return true
    }

    fun replaceItem(playlistId: String, itemId: String, replacement: PlaylistItem): Boolean {
        val current = all()
        val index = current.indexOfFirst { it.id == playlistId }
        if (index < 0 || current[index].items.none { it.id == itemId }) return false
        replaceAll(current.mapIndexed { currentIndex, playlist ->
            if (currentIndex == index) {
                playlist.copy(items = playlist.items.map {
                    if (it.id == itemId) replacement else it
                })
            } else playlist
        })
        return true
    }

    private fun write(lists: List<Playlist>) {
        val array = JSONArray()
        lists.forEach { playlist ->
            val items = JSONArray()
            playlist.items.forEach { item ->
                items.put(JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("artist", item.artist)
                    item.url?.let { put("url", it) }
                    item.provider?.let { put("provider", it.name) }
                    item.localFile?.let { put("localFile", it) }
                    put("createdAt", item.createdAt)
                })
            }
            array.put(JSONObject().apply {
                put("id", playlist.id)
                put("name", playlist.name)
                put("items", items)
                put("createdAt", playlist.createdAt)
            })
        }
        prefs.edit().putString("playlists", array.toString()).apply()
        onChanged?.invoke()
    }
}
