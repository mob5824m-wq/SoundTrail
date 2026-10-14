package tech.egrie.soundtrail.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a catalogue search page, and reading a YouTube title.
 *
 * Both are pure, and both decide what a listener sees and presses in a car or on the phone, so
 * the rules are pinned down here instead of being discovered with a real account.
 */
class YouTubeCatalogueTest {

    private fun page(vararg rows: String): String =
        """{"items":[${rows.joinToString(",")}]}"""

    private fun row(
        title: String,
        channel: String,
        videoId: String = "dQw4w9WgXcQ",
        idField: String = "\"videoId\":\"$videoId\""
    ): String = """
        {"kind":"youtube#searchResult",
         "id":{$idField},
         "snippet":{"title":${quote(title)},"channelTitle":${quote(channel)},
                    "videoOwnerChannelTitle":${quote(channel)}}}
    """.trimIndent()

    private fun quote(value: String) = "\"${value.replace("\"", "\\\"")}\""

    @Test
    fun `a result becomes the song it is, on youtube music`() {
        val tracks = YouTubeApiParser.catalogueTracks(
            page(row("Sunsets", "The xx - Topic")),
            limit = 5
        )
        assertEquals(1, tracks.size)
        assertEquals("Sunsets", tracks[0].title)
        // The " - Topic" suffix is what auto-generated music channels carry; it is not the artist.
        assertEquals("The xx", tracks[0].artist)
        assertEquals(MusicProvider.YOUTUBE_MUSIC, tracks[0].provider)
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", tracks[0].url)
    }

    @Test
    fun `production noise is off the title, but a different recording is not`() {
        assertEquals(
            "Sunsets",
            YouTubeApiParser.catalogueTracks(page(row("Sunsets (Official Video)", "The xx")), 5)[0].title
        )
        assertEquals(
            "Sunsets",
            YouTubeApiParser.catalogueTracks(page(row("Sunsets [Official Audio]", "The xx")), 5)[0].title
        )
        assertEquals(
            "Sunsets",
            YouTubeApiParser.catalogueTracks(page(row("Sunsets (Lyric Video)", "The xx")), 5)[0].title
        )
        // A live take, a remix or a cover is a different thing to play, so the name stays.
        assertEquals(
            "Sunsets (Live)",
            YouTubeApiParser.catalogueTracks(page(row("Sunsets (Live)", "The xx")), 5)[0].title
        )
        assertEquals(
            "Sunsets (Kygo Remix)",
            YouTubeApiParser.catalogueTracks(page(row("Sunsets (Kygo Remix)", "The xx")), 5)[0].title
        )
        assertEquals(
            "Sunsets (Acoustic)",
            YouTubeApiParser.catalogueTracks(page(row("Sunsets (Acoustic)", "The xx")), 5)[0].title
        )
    }

    @Test
    fun `an artist in the title is used when the channel says nothing`() {
        val track = YouTubeApiParser.catalogueTracks(
            page(row("The xx - Sunsets", "")),
            5
        ).first()
        assertEquals("Sunsets", track.title)
        assertEquals("The xx", track.artist)
        assertTrue(track.artist.isNotBlank())
    }

    @Test
    fun `rows that cannot play are dropped rather than offered`() {
        val body = page(
            row("Sunsets", "The xx", videoId = "too-short"),
            row("Private video", "The xx"),
            row("Deleted video", "The xx"),
            row("Sunsets", "The xx")
        )
        val tracks = YouTubeApiParser.catalogueTracks(body, 5)
        assertEquals(1, tracks.size)
        // The same video twice is still one row.
        assertEquals(
            1,
            YouTubeApiParser.catalogueTracks(page(row("Sunsets", "The xx"), row("Sunsets", "The xx")), 5)
                .size
        )
    }

    @Test
    fun `a limit is a limit, and nonsense in a body is not a crash`() {
        val many = page(*(1..20).map { index -> row("Song $index", "Someone", videoId = "abcdefghij$index".take(11)) }.toTypedArray())
        assertEquals(3, YouTubeApiParser.catalogueTracks(many, 3).size)
        assertTrue(YouTubeApiParser.catalogueTracks("{}", 5).isEmpty())
        assertTrue(YouTubeApiParser.catalogueTracks("not json at all", 5).isEmpty())
    }

    @Test
    fun `a title is cleaned without being emptied`() {
        assertEquals("Sunsets", YouTubeTitles.clean("Sunsets (Official Video)"))
        assertEquals("Sunsets", YouTubeTitles.clean("  Sunsets   (Official Music Video)  "))
        assertEquals("Sunsets", YouTubeTitles.clean("Sunsets (Official Video) (HD)"))
        // Nothing left to read would be worse than the noise.
        assertEquals("(Official Video)", YouTubeTitles.clean("(Official Video)"))
        assertEquals("Song 2", YouTubeTitles.clean("Song 2"))
    }

    @Test
    fun `an artist is only read out of a title that offers one`() {
        assertEquals("The xx", YouTubeTitles.artistFromTitle("The xx - Sunsets (Official Video)"))
        assertNull(YouTubeTitles.artistFromTitle("Sunsets"))
        // A sentence is not an artist.
        assertNull(YouTubeTitles.artistFromTitle("the best song ever made in the world today - sure"))
    }

    @Test
    fun `a video id is read out of a link, and turned back into a youtube music one`() {
        assertNull(MusicLinkParser.youtubeVideoId("https://music.youtube.com/watch?v=too-short"))
        assertEquals(
            "dQw4w9WgXcQ",
            MusicLinkParser.youtubeVideoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            YouTubeSearch.watchUrl("dQw4w9WgXcQ")
        )
    }
}
