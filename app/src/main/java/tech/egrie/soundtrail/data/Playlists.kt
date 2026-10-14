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
/**
 * The one list likes live in.
 *
 * An import brings the account's liked videos, and listeners who also keep their own "Liked" list
 * end up with two lists holding the same kind of thing — one named by them, one named by the
 * import. This is the name the app converges on, and the names it recognises as "this is that
 * list" so an import lands in the list that already exists instead of making a second one. What
 * is *in* a liked list is left alone: a row keeps the provider it really came from.
 */
internal object LikedSongs {
    /** What the list is called once the app has touched it. */
    const val NAME = "Liked songs"

    private val names = setOf(
        "liked", "liked songs", "liked music", "liked videos", "songs liked", "my likes",
        "youtube liked", "youtube liked videos", "youtube music liked", "youtube music likes",
        "youtube music liked songs", "favorites", "favourites"
    )

    /** True when a playlist name means "the likes list" rather than some other list. */
    fun isLikedName(name: String): Boolean = name.trim().lowercase() in names

    /**
     * True when a name came from an import rather than from the listener. A name the listener
     * chose is theirs to keep; one the app made is the app's to converge.
     */
    fun isImportedName(name: String): Boolean {
        val lower = name.trim().lowercase()
        return lower == "liked videos" || lower.startsWith("youtube liked") ||
            lower.startsWith("youtube music liked")
    }
}

/**
 * Folds every app-created likes list into the listener's own, so there is one list holding
 * likes rather than one per import. Nothing is dropped: a song that is already in the target
 * under the same link is not added twice, and [PlaylistItem]s that have an offline copy keep it,
 * because the item is moved rather than copied.
 *
 * Returns the new list of playlists and how many songs moved.
 */
internal fun consolidateLikes(lists: List<Playlist>, targetId: String): Pair<List<Playlist>, Int> {
    val target = lists.firstOrNull { it.id == targetId } ?: return lists to 0
    val sources = lists.filter {
        it.id != targetId && it.id.startsWith("yt-pl:") && LikedSongs.isLikedName(it.name)
    }
    if (sources.isEmpty()) return lists to 0

    val seenLinks = target.items.mapNotNullTo(HashSet()) { it.url }
    val seenIds = target.items.mapTo(HashSet()) { it.id }
    val moved = mutableListOf<PlaylistItem>()
    sources.forEach { source ->
        source.items.forEach { item ->
            val link = item.url
            if (item.id in seenIds || (link != null && link in seenLinks)) return@forEach
            moved += item
            seenIds += item.id
            if (link != null) seenLinks += link
        }
    }

    val kept = lists
        .filterNot { list -> sources.any { it.id == list.id } }
        .map { if (it.id == targetId) it.copy(items = it.items + moved) else it }
    return kept to moved.size
}

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
class PlaylistStore(context: Context) {
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
    }
}
