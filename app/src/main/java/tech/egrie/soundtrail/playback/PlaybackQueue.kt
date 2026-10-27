package tech.egrie.soundtrail.playback

import tech.egrie.soundtrail.integrations.MusicProvider

/**
 * One thing the player can be asked to start: a pin, a playlist song, or a mood-mix row.
 *
 * [mediaId] is the identity every layer agrees on — the in-app bar shows [title], the media
 * session's queue is built from the same ids, and Android Auto plays entries by id. A link
 * or an attached file is the *source*; [hasSource] is what makes an entry worth playing.
 */
internal data class QueueEntry(
    val mediaId: String,
    val title: String,
    val artist: String = "",
    /** Where the song came from, for the second line of a row: a place, or a playlist name. */
    val detail: String = "",
    val provider: MusicProvider? = null,
    /** A provider link (`jellyfin:item:<id>`), if any. */
    val url: String? = null,
    /** The name of an attached offline copy in private storage, if any. */
    val localFile: String? = null
) {
    val hasSource: Boolean get() = localFile != null || url != null

    /** What a hop from the car's list shows under the title. */
    val subtitle: String get() = artist.ifBlank { detail }
}

/**
 * The playback queue and its cursor: what is playing, what comes next, and what came before.
 *
 * Deliberately free of Android types, so the rules that decide where "next" and "previous"
 * land are unit-tested rather than discovered in a car. The player itself never sees this —
 * [PlaybackHub] hands one entry at a time to the app's single [JellyfinPlayer].
 */
internal class PlaybackQueue {
    private var items: List<QueueEntry> = emptyList()
    private var cursor = -1

    val entries: List<QueueEntry> get() = items
    val index: Int get() = cursor
    val current: QueueEntry? get() = items.getOrNull(cursor)

    val isNotEmpty: Boolean get() = items.isNotEmpty()

    fun hasNext(): Boolean = cursor < items.lastIndex

    fun hasPrevious(): Boolean = cursor > 0

    /** Installs a queue and moves to [startIndex], clamped into range. Returns where we landed. */
    fun start(newItems: List<QueueEntry>, startIndex: Int): QueueEntry? {
        items = newItems
        cursor = if (newItems.isEmpty()) -1 else startIndex.coerceIn(0, newItems.lastIndex)
        return current
    }

    /** Moves the cursor straight to [index], or stays put and answers null when it is out of range. */
    fun moveTo(index: Int): QueueEntry? {
        if (index !in items.indices) return null
        cursor = index
        return current
    }

    fun next(): QueueEntry? = moveTo(cursor + 1)

    fun previous(): QueueEntry? = moveTo(cursor - 1)

    /** Jumps to a song by id; null (and no movement) when that song is not in this queue. */
    fun jumpTo(mediaId: String): QueueEntry? = moveTo(items.indexOfFirst { it.mediaId == mediaId })

    /**
     * Updates the songs but keeps playing the same one where it still exists — how the queue
     * follows an edit in the app without interrupting what is coming out of the speaker.
     */
    fun replace(newItems: List<QueueEntry>) {
        val playingId = current?.mediaId
        items = newItems
        cursor = if (playingId == null) -1 else newItems.indexOfFirst { it.mediaId == playingId }
    }

    fun clear() {
        items = emptyList()
        cursor = -1
    }
}

/** What the UI and the car need to know about the queue without touching the queue itself. */
internal data class QueueState(
    val entries: List<QueueEntry> = emptyList(),
    val index: Int = -1
) {
    val current: QueueEntry? get() = entries.getOrNull(index)
}
