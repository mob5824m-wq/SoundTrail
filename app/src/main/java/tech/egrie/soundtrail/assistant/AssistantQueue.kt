package tech.egrie.soundtrail.assistant

import tech.egrie.soundtrail.playback.LibraryMedia
import tech.egrie.soundtrail.playback.QueueEntry

/**
 * One answer row as something the player can start.
 *
 * A hit from the listener's own library keeps the stored item's id, so its offline copy plays and
 * the row that is playing is the same row in the lists and in the car. A hit from a search keeps
 * the id of its link and the link itself.
 */
internal fun AssistantHit.toQueueEntry(): QueueEntry = QueueEntry(
    mediaId = LibraryMedia.song(itemId ?: url.orEmpty()),
    title = title,
    artist = artist,
    detail = detail,
    provider = provider,
    url = url,
    streamUrl = streamUrl,
    localFile = localFile
)
