package tech.egrie.soundtrail.auto

import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.Playlist
import tech.egrie.soundtrail.playback.LibraryMedia
import tech.egrie.soundtrail.playback.QueueEntry
import tech.egrie.soundtrail.playback.toQueueEntry

/** One row Android Auto can show: a list the driver can open, or a song they can play. */
internal data class AutoRow(
    val mediaId: String,
    val title: String,
    val subtitle: String,
    val browsable: Boolean,
    val playable: Boolean
)

/** The songs a request means, and which of them was picked. */
internal data class AutoSelection(val entries: List<QueueEntry>, val index: Int)

/**
 * The tree the car browses, built from what the phone already stores: the pins list, every
 * playlist, and the songs inside them. Nothing is fetched and nothing is uploaded — the car
 * sees the same library the app's own Lists tab shows.
 *
 * Songs that cannot play here (no link, no attached copy) are left out rather than shown
 * greyed out: in a car, a row that does nothing is worse than a row that is not there.
 *
 * Pure functions over the stores' own data, so the shape the car sees is unit-tested instead
 * of discovered behind the wheel.
 */
internal object AutoTree {

    /** Rows under [parentId]; the root is the pins list followed by every playlist. */
    fun rows(parentId: String, pins: List<MusicPin>, playlists: List<Playlist>): List<AutoRow> = when {
        parentId == LibraryMedia.ROOT -> rootRows(pins, playlists)
        parentId == LibraryMedia.PINS -> pins.mapNotNull(::pinRow)
        LibraryMedia.playlistIdOf(parentId) != null -> {
            val playlist = playlists.firstOrNull { it.id == LibraryMedia.playlistIdOf(parentId) }
            playlist?.items?.mapNotNull { item -> row(item.toQueueEntry(playlist.name)) }.orEmpty()
        }
        else -> emptyList()
    }

    /** One row by id, for a host that asks about a single item. */
    fun row(mediaId: String, pins: List<MusicPin>, playlists: List<Playlist>): AutoRow? {
        val itemId = LibraryMedia.itemIdOf(mediaId) ?: return null
        pins.firstOrNull { it.id == itemId }?.let { pin -> return pinRow(pin) }
        playlists.forEach { playlist ->
            playlist.items.firstOrNull { it.id == itemId }?.let { item ->
                return row(item.toQueueEntry(playlist.name))
            }
        }
        return null
    }

    /**
     * What tapping [mediaId] should play. A playlist song queues the rest of its playlist and
     * a pin queues the pins list, so "next" in the car has somewhere to go — which is also
     * what fills the car's now-playing list.
     */
    fun selection(mediaId: String, pins: List<MusicPin>, playlists: List<Playlist>): AutoSelection? {
        val itemId = LibraryMedia.itemIdOf(mediaId) ?: return null
        select(pins.map { it.toQueueEntry() }, itemId)?.let { return it }
        playlists.forEach { playlist ->
            val entries = playlist.items.map { it.toQueueEntry(playlist.name) }
            select(entries, itemId)?.let { return it }
        }
        return null
    }

    /** Matches a spoken query against this phone's songs. Never sent to any server. */
    fun search(query: String, pins: List<MusicPin>, playlists: List<Playlist>): AutoSelection? {
        val terms = query.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        if (terms.isEmpty()) return null
        val matches = LinkedHashMap<String, QueueEntry>()
        fun consider(entry: QueueEntry, also: String) {
            if (!entry.hasSource) return
            val haystack = "${entry.title} ${entry.artist} ${entry.detail} $also".lowercase()
            if (terms.all(haystack::contains)) matches.putIfAbsent(entry.mediaId, entry)
        }
        pins.forEach { consider(it.toQueueEntry(), it.place) }
        playlists.forEach { playlist ->
            playlist.items.forEach { consider(it.toQueueEntry(playlist.name), playlist.name) }
        }
        if (matches.isEmpty()) return null
        return AutoSelection(matches.values.toList(), 0)
    }

    /** What pressing play should start when nothing has been chosen: the first playable song. */
    fun firstSelection(pins: List<MusicPin>, playlists: List<Playlist>): AutoSelection? {
        playlists.forEach { playlist ->
            val entries = playlist.items.map { it.toQueueEntry(playlist.name) }.filter { it.hasSource }
            if (entries.isNotEmpty()) return AutoSelection(entries, 0)
        }
        val pinEntries = pins.map { it.toQueueEntry() }.filter { it.hasSource }
        if (pinEntries.isNotEmpty()) return AutoSelection(pinEntries, 0)
        return null
    }

    /** A list of songs, with the tapped one located inside it. */
    private fun select(entries: List<QueueEntry>, itemId: String): AutoSelection? {
        val playable = entries.filter { it.hasSource }
        val index = playable.indexOfFirst { LibraryMedia.itemIdOf(it.mediaId) == itemId }
        if (index < 0) return null
        return AutoSelection(playable, index)
    }

    private fun rootRows(pins: List<MusicPin>, playlists: List<Playlist>): List<AutoRow> {
        val rows = mutableListOf(
            AutoRow(
                mediaId = LibraryMedia.PINS,
                title = "Pins",
                subtitle = songCount(pins.count { it.toQueueEntry().hasSource }),
                browsable = true,
                playable = false
            )
        )
        playlists.forEach { playlist ->
            rows += AutoRow(
                mediaId = LibraryMedia.playlist(playlist.id),
                title = playlist.name,
                subtitle = songCount(playlist.items.count { it.toQueueEntry().hasSource }),
                browsable = true,
                playable = false
            )
        }
        return rows
    }

    private fun pinRow(pin: MusicPin): AutoRow? = row(pin.toQueueEntry())

    private fun row(entry: QueueEntry): AutoRow? {
        if (!entry.hasSource) return null
        return AutoRow(
            mediaId = entry.mediaId,
            title = entry.title,
            subtitle = entry.subtitle,
            browsable = false,
            playable = true
        )
    }

    private fun songCount(count: Int): String = when (count) {
        0 -> "No songs yet"
        1 -> "1 song"
        else -> "$count songs"
    }
}
