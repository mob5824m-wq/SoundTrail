package tech.egrie.soundtrail.playback

import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.PlaylistItem
import tech.egrie.soundtrail.integrations.MusicLinkParser
import tech.egrie.soundtrail.integrations.MusicProvider
import tech.egrie.soundtrail.mood.MoodMixTrack

/**
 * The ids one queue entry carries, so that the same song is the same thing in the in-app
 * bar, in the media session's queue, and in the tree Android Auto browses.
 *
 * A song id is the store's own item id (a pin id or a playlist item id) with a small
 * prefix — never a server address, a stream URL, or a token. Playlists and the pins list
 * get their own ids so the car can open them as folders.
 */
internal object LibraryMedia {
    /** The car's top level: the pins list and every playlist. */
    const val ROOT = "st:root"

    /** Every pin, newest first. */
    const val PINS = "st:pins"

    private const val PLAYLIST_PREFIX = "st:playlist:"
    private const val SONG_PREFIX = "st:song:"

    fun playlist(playlistId: String): String = PLAYLIST_PREFIX + playlistId

    fun song(itemId: String): String = SONG_PREFIX + itemId

    /** The playlist a browsable id points at, or null when it is not a playlist. */
    fun playlistIdOf(mediaId: String): String? = mediaId
        .takeIf { it.startsWith(PLAYLIST_PREFIX) }
        ?.removePrefix(PLAYLIST_PREFIX)
        ?.takeIf(String::isNotBlank)

    /** The stored item id inside a song id, or null when the id is not a song. */
    fun itemIdOf(mediaId: String): String? = mediaId
        .takeIf { it.startsWith(SONG_PREFIX) }
        ?.removePrefix(SONG_PREFIX)
        ?.takeIf(String::isNotBlank)
}

/** A pin plays like any other song: its link, or the copy attached to it. */
internal fun MusicPin.toQueueEntry(): QueueEntry = QueueEntry(
    mediaId = LibraryMedia.song(id),
    title = title.ifBlank { "Untitled song" },
    artist = artist,
    detail = place,
    provider = provider,
    url = url
)

/** A playlist song keeps its provider link and, when it has one, its offline copy. */
internal fun PlaylistItem.toQueueEntry(playlistName: String = ""): QueueEntry {
    val link = url?.let(MusicLinkParser::parse)
    return QueueEntry(
        mediaId = LibraryMedia.song(id),
        title = title.ifBlank { "Untitled song" },
        artist = artist,
        detail = listOfNotNull(
            (provider ?: link?.provider)?.label,
            playlistName.takeIf(String::isNotBlank)
        ).joinToString(" · "),
        provider = provider ?: link?.provider,
        url = url ?: link?.url,
        localFile = localFile
    )
}

/** A mood mix row points at the very same pin or playlist song it was scored from. */
internal fun MoodMixTrack.toQueueEntry(): QueueEntry = QueueEntry(
    mediaId = LibraryMedia.song(key),
    title = title.ifBlank { "Untitled song" },
    artist = artist,
    detail = origin,
    provider = provider,
    url = url
)

/** The source of what is playing, for the session's album line and the notification. */
internal fun MusicProvider?.sourceLabel(): String = when (this) {
    MusicProvider.JELLYFIN -> "Jellyfin"
    // A song with no provider at all is one the listener attached themselves.
    null -> "Soundtrail"
}
