package tech.egrie.soundtrail.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.Playlist
import tech.egrie.soundtrail.data.PlaylistItem
import tech.egrie.soundtrail.integrations.MusicProvider

/** Answering a request from the music already on the phone, with no network involved. */
class AssistantLibraryTest {

    private fun pin(id: String, title: String, artist: String, place: String) = MusicPin(
        id = id,
        title = title,
        artist = artist,
        place = place,
        note = "",
        url = "https://open.spotify.com/track/${"a".repeat(22)}",
        provider = MusicProvider.SPOTIFY,
        point = GeoPoint(43.45, -80.49),
        createdAt = 0L
    )

    private fun item(id: String, title: String, artist: String, offline: String? = null) = PlaylistItem(
        id = id,
        title = title,
        artist = artist,
        url = "https://soundcloud.com/someone/$title",
        provider = MusicProvider.SOUNDCLOUD,
        createdAt = 0L,
        localFile = offline
    )

    private val pins = listOf(
        pin("p1", "Sunsets", "The xx", "Kitchener"),
        pin("p2", "Midnight City", "M83", "Toronto")
    )
    private val playlists = listOf(
        Playlist(
            id = "l1",
            name = "Road trip",
            items = listOf(
                item("s1", "Sunsets", "The xx", offline = "sunsets.mp3"),
                item("s2", "Feels Like We Only Go Backwards", "Tame Impala")
            ),
            createdAt = 0L
        )
    )

    private fun search(said: String) =
        AssistantLibrary.search(AssistantParser.parse(said)!!, pins, playlists)

    @Test
    fun `a song in the library is found without a search`() {
        val hits = search("Play Sunsets")
        // The pin and the playlist copy are both the same song here; both are offered, and the
        // pin comes first because it was put somewhere on purpose.
        assertTrue(hits.isNotEmpty())
        assertEquals("p1", hits.first().itemId)
        assertTrue(hits.any { it.itemId == "s1" })
    }

    @Test
    fun `an offline copy is named as one`() {
        val copy = search("Play Sunsets").first { it.itemId == "s1" }
        assertTrue(copy.detail.contains("Offline copy"))
        assertTrue(copy.detail.contains("Road trip"))
        assertTrue(copy.playable)
    }

    @Test
    fun `every word has to match, so a stray word does not answer with the library`() {
        assertEquals(emptyList<AssistantHit>(), search("Play Sunsets by someone else entirely"))
        assertTrue(search("Play Midnight City").any { it.itemId == "p2" })
    }

    @Test
    fun `the place a pin was saved to can be what was asked for`() {
        val hits = search("Play the song from Toronto")
        assertTrue(hits.any { it.itemId == "p2" })
    }

    @Test
    fun `a playlist name can be what was asked for`() {
        val hits = search("Play Feels Like We Only Go Backwards from Road trip")
        assertEquals("s2", hits.first().itemId)
    }

    @Test
    fun `nothing in the library means no hits, not a wrong one`() {
        assertEquals(emptyList<AssistantHit>(), search("Play something by a band nobody saved"))
    }

    @Test
    fun `an answer lists the library before anything else`() {
        val mine = search("Play Sunsets")
        val merged = AssistantAnswer.merge(mine, listOf(hit("Sunsets", "The xx")))
        // The search result is the same song as the pin, so it is dropped rather than repeated.
        assertEquals(mine.size, merged.size)
        assertEquals("p1", merged.first().itemId)
    }

    @Test
    fun `a search result that is not in the library is kept`() {
        val merged = AssistantAnswer.merge(search("Play Sunsets"), listOf(hit("Islands", "The xx")))
        assertTrue(merged.any { it.title == "Islands" })
        assertEquals("p1", merged.first().itemId)
    }

    @Test
    fun `a cover is not mistaken for the song already in the library`() {
        val merged = AssistantAnswer.merge(
            search("Play Sunsets"),
            listOf(hit("Sunsets", "Someone Else"))
        )
        assertTrue(merged.any { it.artist == "Someone Else" })
    }

    @Test
    fun `the sentence says what is playing and how much is behind it`() {
        val hits = listOf(hit("Sunsets", "The xx"), hit("Islands", "The xx"), hit("VCR", "The xx"))
        assertEquals("Playing Sunsets by The xx, then 2 more.", AssistantAnswer.sentence(hits, true, null))
    }

    @Test
    fun `one song alone is not followed by a count`() {
        assertEquals(
            "Playing Sunsets by The xx.",
            AssistantAnswer.sentence(listOf(hit("Sunsets", "The xx")), true, null)
        )
    }

    @Test
    fun `an empty answer explains itself differently with and without a search`() {
        val connected = AssistantAnswer.sentence(emptyList(), searched = true, error = null)
        val offline = AssistantAnswer.sentence(emptyList(), searched = false, error = null)
        assertTrue(connected.contains("Nothing matched"))
        assertTrue(offline.contains("Connect your Google account"))
    }

    @Test
    fun `a search that failed is only worth mentioning when it found nothing`() {
        val withSongs = AssistantAnswer.sentence(listOf(hit("Sunsets", "The xx")), true, "YouTube said no.")
        assertEquals("Playing Sunsets by The xx.", withSongs)
        val alone = AssistantAnswer.sentence(emptyList(), searched = true, error = "YouTube said no.")
        assertEquals("YouTube said no.", alone)
    }

    @Test
    fun `a song that cannot play is listed but not played, and said so`() {
        val silent = AssistantHit(
            title = "Sunsets", artist = "The xx", provider = null, detail = "Your library"
        )
        assertTrue(AssistantAnswer.playable(listOf(silent)).isEmpty())
        assertEquals(
            "Found 1 song, but it cannot play on this phone.",
            AssistantAnswer.sentence(listOf(silent), searched = false, error = null)
        )
    }

    @Test
    fun `several songs that cannot play are one sentence`() {
        val silent = List(3) { index ->
            AssistantHit(
                title = "Song $index", artist = "", provider = null, detail = "Your library"
            )
        }
        assertEquals(
            "Found 3 songs, but none of them can play on this phone.",
            AssistantAnswer.sentence(silent, searched = false, error = null)
        )
    }

    private fun hit(title: String, artist: String) = AssistantHit(
        title = title,
        artist = artist,
        provider = MusicProvider.YOUTUBE_MUSIC,
        detail = "YouTube Music",
        url = "https://music.youtube.com/watch?v=abcdefghijk"
    )
}
