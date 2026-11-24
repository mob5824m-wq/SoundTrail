package tech.egrie.soundtrail.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this app accepts as a song link.
 *
 * Accepted shapes are opaque item references: a Jellyfin item on the listener's own server
 * (`jellyfin:item:<id>`) and a YouTube Music track (`ytmusic:track:<videoId>`). Every other
 * shape is refused, including https web links an older version stored for removed streaming
 * services — a link this app cannot resolve or play is not a song.
 *
 * The refusal cases below are load-bearing: [MusicLinkParser.isUnplayable] is what the upgrade
 * cleanup and read paths use to decide a stored row's fate.
 */
class MusicLinkParserTest {
    /** A plausible Jellyfin item id: 16–64 of [A-Za-z0-9-]. */
    private val itemId = "4cOdK2wGLETKBW3PvgPWqT"
    private val jellyfinLink = MusicLink(MusicProvider.JELLYFIN, "jellyfin:item:$itemId")
    private val videoId = "dQw4w9WgXcQ"
    private val ytLink = MusicLink(MusicProvider.YOUTUBE_MUSIC, "ytmusic:track:$videoId")

    // ----- what is accepted -----

    @Test fun `a jellyfin item reference is accepted`() {
        assertEquals(jellyfinLink, MusicLinkParser.parse("jellyfin:item:$itemId"))
        assertEquals(jellyfinLink, MusicLinkParser.fromText("jellyfin:item:$itemId"))
    }

    @Test fun `a youtube music track reference is accepted`() {
        assertEquals(ytLink, MusicLinkParser.parse("ytmusic:track:$videoId"))
        assertEquals(ytLink, MusicLinkParser.fromText("ytmusic:track:$videoId"))
    }

    @Test fun `references are read case-insensitively and trimmed`() {
        assertEquals(jellyfinLink, MusicLinkParser.parse("  JELLYFIN:ITEM:$itemId  "))
        assertEquals(jellyfinLink, MusicLinkParser.parse("Jellyfin:Item:$itemId"))
        assertEquals(ytLink, MusicLinkParser.parse("  YTMUSIC:TRACK:$videoId  "))
        assertEquals(ytLink, MusicLinkParser.parse("YtMusic:Track:$videoId"))
    }

    @Test fun `the stored link is canonical, so the same song is the same string everywhere`() {
        assertEquals("jellyfin:item:$itemId", MusicLinkParser.parse(" jellyfin:item:$itemId ")?.url)
        assertEquals(MusicProvider.JELLYFIN, MusicLinkParser.parse("JELLYFIN:ITEM:$itemId")?.provider)
        assertEquals("ytmusic:track:$videoId", MusicLinkParser.parse(" YTMUSIC:TRACK:$videoId ")?.url)
        assertEquals(MusicProvider.YOUTUBE_MUSIC, MusicLinkParser.parse("YTMUSIC:TRACK:$videoId")?.provider)
    }

    // ----- what is refused -----

    @Test fun `a malformed item reference is refused rather than guessed at`() {
        assertNull(MusicLinkParser.parse("jellyfin:item:"))
        assertNull(MusicLinkParser.parse("jellyfin:item:short"))
        assertNull(MusicLinkParser.parse("jellyfin:playlist:$itemId"))
        assertNull(MusicLinkParser.parse("jellyfin:item:$itemId/../../Users"))
        assertNull(MusicLinkParser.parse("https://jellyfin.example.com/web/index.html#!/details?id=$itemId"))
        assertNull(MusicLinkParser.parse("ytmusic:track:"))
        assertNull(MusicLinkParser.parse("ytmusic:track:short"))
        assertNull(MusicLinkParser.parse("ytmusic:track:$videoId/../x"))
        assertNull(MusicLinkParser.parse("ytmusic:video:$videoId"))
    }

    @Test fun `plain text is not a link`() {
        assertNull(MusicLinkParser.parse("Just a song title"))
        assertNull(MusicLinkParser.fromText("Sunsets by The xx"))
        assertNull(MusicLinkParser.parse(""))
        assertNull(MusicLinkParser.parse("   "))
    }

    /**
     * Links an older version of this app stored as web URLs. Each one is refused now, which is what
     * makes the upgrade cleanup able to find them: [MusicLinkParser.isUnplayable] is true for these.
     */
    @Test fun `legacy web links from removed streaming services are refused`() {
        val spotifyId = "4uLU6hMCjMI75M1A2tKUQC"
        listOf(
            "https://open.spotify.com/track/$spotifyId",
            "https://open.spotify.com/track/$spotifyId?si=tracking",
            "spotify:track:$spotifyId",
            "https://soundcloud.com/matas/hobnotropic",
            "https://www.soundcloud.com/matas/hobnotropic?si=tracking",
            "https://m.soundcloud.com/matas/hobnotropic?utm_source=share",
            "https://soundcloud.com/artist/track/s-Secret9",
            "https://on.soundcloud.com/9xk2mQaB",
            "https://soundcloud.app.goo.gl/AbC_123",
            "https://music.youtube.com/watch?v=$videoId",
            "https://music.youtube.com/watch?v=$videoId&si=tracking",
            "https://www.youtube.com/watch?v=$videoId",
            "https://youtube.com/watch?v=$videoId",
            "https://m.youtube.com/watch?v=$videoId",
            "https://youtu.be/$videoId",
            "https://music.youtube.com/playlist?list=PL123"
        ).forEach { removed ->
            assertNull("should refuse $removed", MusicLinkParser.parse(removed))
            assertNull("should refuse $removed in text", MusicLinkParser.fromText("Listen: $removed"))
            assertTrue("should be unplayable: $removed", MusicLinkParser.isUnplayable(removed))
        }
    }

    @Test fun `shared text no longer yields a link from a legacy web url`() {
        assertNull(MusicLinkParser.fromText("Listen to this: https://open.spotify.com/track/x!"))
        assertNull(MusicLinkParser.fromText("Check it out https://youtu.be/dQw4w9WgXcQ)"))
        assertEquals(jellyfinLink, MusicLinkParser.fromText("jellyfin:item:$itemId"))
        assertEquals(ytLink, MusicLinkParser.fromText("ytmusic:track:$videoId"))
    }

    // ----- the rule the upgrade cleanup is built on -----

    @Test fun `isUnplayable is false for a link that plays and for no link at all`() {
        assertFalse(MusicLinkParser.isUnplayable("jellyfin:item:$itemId"))
        assertFalse(MusicLinkParser.isUnplayable("ytmusic:track:$videoId"))
        assertFalse(MusicLinkParser.isUnplayable(null))
    }

    @Test fun `isUnplayable is true for a dead link, whatever else it looks like`() {
        assertTrue(MusicLinkParser.isUnplayable("https://open.spotify.com/track/abc"))
        assertTrue(MusicLinkParser.isUnplayable("jellyfin:item:short"))
        assertTrue(MusicLinkParser.isUnplayable("ytmusic:track:short"))
        assertTrue(MusicLinkParser.isUnplayable("not a link"))
    }
}
