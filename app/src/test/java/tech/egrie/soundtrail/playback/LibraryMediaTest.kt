package tech.egrie.soundtrail.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.PlaylistItem
import tech.egrie.soundtrail.integrations.MusicProvider

class LibraryMediaTest {
    private val trackUrl = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"

    @Test fun `song and playlist ids round-trip through their prefixes`() {
        val song = LibraryMedia.song("a-b-c")
        assertEquals("a-b-c", LibraryMedia.itemIdOf(song))
        assertNull(LibraryMedia.playlistIdOf(song))

        val playlist = LibraryMedia.playlist("list-1")
        assertEquals("list-1", LibraryMedia.playlistIdOf(playlist))
        assertNull(LibraryMedia.itemIdOf(playlist))

        // Anything that is not one of ours is refused rather than guessed at.
        assertNull(LibraryMedia.itemIdOf(LibraryMedia.ROOT))
        assertNull(LibraryMedia.itemIdOf(LibraryMedia.PINS))
        assertNull(LibraryMedia.itemIdOf("st:song:"))
        assertNull(LibraryMedia.playlistIdOf("st:playlist:"))
        assertNull(LibraryMedia.itemIdOf("https://example.com/track"))
    }

    @Test fun `a pin carries its link and the place it was saved to`() {
        val pin = MusicPin(
            id = "pin-1", title = "Nightcall", artist = "Kavinsky", place = "Lookout point",
            note = "", url = trackUrl, provider = MusicProvider.SPOTIFY,
            point = GeoPoint(43.65, -79.38), createdAt = 5L
        )
        val entry = pin.toQueueEntry()
        assertEquals(LibraryMedia.song("pin-1"), entry.mediaId)
        assertEquals("Nightcall", entry.title)
        assertEquals("Kavinsky", entry.subtitle)
        assertEquals("Lookout point", entry.detail)
        assertEquals(trackUrl, entry.url)
        assertTrue(entry.hasSource)
    }

    @Test fun `a playlist song keeps its provider, its playlist and its offline copy`() {
        val item = PlaylistItem(
            id = "song-1", title = "Motion Sickness", artist = "Phoebe Bridgers",
            url = trackUrl, provider = MusicProvider.SPOTIFY, createdAt = 9L,
            localFile = "offline-9.mp3"
        )
        val entry = item.toQueueEntry("Road trip")
        assertEquals(LibraryMedia.song("song-1"), entry.mediaId)
        assertEquals(MusicProvider.SPOTIFY, entry.provider)
        assertEquals(trackUrl, entry.url)
        assertEquals("offline-9.mp3", entry.localFile)
        assertEquals("Spotify · Road trip", entry.detail)
        assertTrue(entry.hasSource)
    }

    @Test fun `a song without a link or a copy cannot play`() {
        val item = PlaylistItem(
            id = "song-2", title = "Untitled idea", artist = "", url = null,
            provider = null, createdAt = 1L
        )
        assertFalse(item.toQueueEntry().hasSource)
    }

    @Test fun `a song with a blank title still shows something in a car`() {
        val item = PlaylistItem(
            id = "song-3", title = "   ", artist = "", url = null, provider = null, createdAt = 1L
        )
        assertEquals("Untitled song", item.toQueueEntry().title)
    }
}
