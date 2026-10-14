package tech.egrie.soundtrail.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.Playlist
import tech.egrie.soundtrail.data.PlaylistItem
import tech.egrie.soundtrail.integrations.MusicProvider
import tech.egrie.soundtrail.playback.LibraryMedia

class AutoTreeTest {
    private val trackUrl = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"

    private fun pin(id: String, title: String, place: String = "Lookout point") = MusicPin(
        id = id, title = title, artist = "An artist", place = place, note = "",
        url = trackUrl, provider = MusicProvider.SPOTIFY,
        point = GeoPoint(43.65, -79.38), createdAt = 1L
    )

    private fun item(id: String, title: String, url: String? = trackUrl, localFile: String? = null) =
        PlaylistItem(
            id = id, title = title, artist = "An artist", url = url,
            provider = url?.let { MusicProvider.SPOTIFY }, createdAt = 1L, localFile = localFile
        )

    private val pins = listOf(pin("pin-1", "Nightcall"), pin("pin-2", "Midnight City"))
    private val playlists = listOf(
        Playlist(
            id = "list-1", name = "Road trip", createdAt = 1L,
            items = listOf(item("song-1", "Ribs"), item("song-2", "Motion Sickness"))
        ),
        Playlist(
            id = "list-2", name = "Ideas", createdAt = 2L,
            items = listOf(item("song-3", "Humming"), item("song-4", "Faded demo", url = null))
        )
    )

    @Test fun `the root is the pins list followed by every playlist`() {
        val rows = AutoTree.rows(LibraryMedia.ROOT, pins, playlists)
        assertEquals(listOf(LibraryMedia.PINS, "st:playlist:list-1", "st:playlist:list-2"), rows.map { it.mediaId })
        assertEquals("Pins", rows[0].title)
        assertEquals("2 songs", rows[0].subtitle)
        assertEquals("Road trip", rows[1].title)
        assertEquals("2 songs", rows[1].subtitle)
        // A list is something to open, never something to play.
        assertTrue(rows.all { it.browsable && !it.playable })
    }

    @Test fun `a playlist only shows the songs that can actually play`() {
        val children = AutoTree.rows(LibraryMedia.playlist("list-2"), pins, playlists)
        assertEquals(listOf("st:song:song-3"), children.map { it.mediaId })
        assertEquals("An artist", children.single().subtitle)
        assertTrue(children.single().playable)
        val empty = AutoTree.rows(LibraryMedia.playlist("gone"), pins, playlists)
        assertTrue(empty.isEmpty())
        assertTrue(AutoTree.rows(LibraryMedia.ROOT + "-nonsense", pins, playlists).isEmpty())
    }

    @Test fun `the pins list shows the trail the app saved`() {
        val children = AutoTree.rows(LibraryMedia.PINS, pins, playlists)
        assertEquals(listOf("st:song:pin-1", "st:song:pin-2"), children.map { it.mediaId })
        assertEquals("Nightcall", children.first().title)
    }

    @Test fun `a tapped playlist song queues the whole playlist at that song`() {
        val selection = AutoTree.selection("st:song:song-2", pins, playlists)
        assertEquals(2, selection?.entries?.size)
        assertEquals(1, selection?.index)
        assertEquals("st:song:song-2", selection?.entries?.get(1)?.mediaId)
        // Each row knows which list it came from, under the artist.
        assertEquals("Spotify · Road trip", selection?.entries?.first()?.detail)
        assertEquals("An artist", selection?.entries?.first()?.subtitle)
    }

    @Test fun `a tapped pin queues the pins list at that pin`() {
        val selection = AutoTree.selection("st:song:pin-2", pins, playlists)
        assertEquals(2, selection?.entries?.size)
        assertEquals(1, selection?.index)
        assertEquals("Midnight City", selection?.entries?.get(1)?.title)
    }

    @Test fun `ids that are not songs, or that have left the library, are refused`() {
        assertNull(AutoTree.selection(LibraryMedia.ROOT, pins, playlists))
        assertNull(AutoTree.selection(LibraryMedia.PINS, pins, playlists))
        assertNull(AutoTree.selection("st:song:gone", pins, playlists))
        assertNull(AutoTree.row("st:song:gone", pins, playlists))
        assertEquals("st:song:pin-1", AutoTree.row("st:song:pin-1", pins, playlists)?.mediaId)
    }

    @Test fun `search matches every word against the song, the artist and the place`() {
        assertEquals("st:song:pin-1", AutoTree.search("nightcall", pins, playlists)?.entries?.first()?.mediaId)
        assertEquals("st:song:song-1", AutoTree.search("artist ribs", pins, playlists)?.entries?.first()?.mediaId)
        assertEquals("st:song:pin-2", AutoTree.search("midnight lookout", pins, playlists)?.entries?.first()?.mediaId)
        assertEquals("st:song:song-3", AutoTree.search("humming", pins, playlists)?.entries?.first()?.mediaId)
        // A song that cannot play here is not a result, however well its name matches.
        assertNull(AutoTree.search("faded demo", pins, playlists))
        assertNull(AutoTree.search("something nobody saved", pins, playlists))
        assertNull(AutoTree.search("   ", pins, playlists))
    }

    @Test fun `search results keep the order the library has them in`() {
        val selection = AutoTree.search("song", pins, playlists)
        assertNull(selection)
        val both = AutoTree.search("artist", pins, playlists)
        assertEquals(listOf("st:song:pin-1", "st:song:pin-2", "st:song:song-1", "st:song:song-2", "st:song:song-3"),
            both?.entries?.map { it.mediaId })
        assertEquals(0, both?.index)
    }

    @Test fun `pressing play starts the first song of the first list that has one`() {
        assertEquals("st:song:song-1", AutoTree.firstSelection(pins, playlists)?.entries?.first()?.mediaId)
        // Without playlists it falls back to the trail, and without either it has nothing to say.
        assertEquals("st:song:pin-1", AutoTree.firstSelection(pins, emptyList())?.entries?.first()?.mediaId)
        assertNull(AutoTree.firstSelection(emptyList(), emptyList()))
    }
}
