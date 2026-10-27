package tech.egrie.soundtrail.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this app accepts as a song link.
 *
 * One shape is accepted: a reference to an item on the listener's own Jellyfin server. Every
 * other shape is refused, and that includes the Spotify, SoundCloud, YouTube and YouTube Music
 * links an older version stored — a link this app cannot resolve, stream or hand to another app
 * is not a song, and pretending otherwise is how a library fills up with rows that never play.
 *
 * The refusal cases below are the load-bearing ones: [MusicLinkParser.isUnplayable] is what the
 * upgrade cleanup and all three read paths use to decide a stored row's fate, so a link shape
 * that quietly started parsing again would quietly stop being cleaned up.
 */
class MusicLinkParserTest {
    /** A plausible Jellyfin item id: 16–64 of [A-Za-z0-9-]. */
    private val itemId = "4cOdK2wGLETKBW3PvgPWqT"
    private val link = MusicLink(MusicProvider.JELLYFIN, "jellyfin:item:$itemId")

    // ----- what is accepted -----

    @Test fun `a jellyfin item reference is the one accepted link`() {
        assertEquals(link, MusicLinkParser.parse("jellyfin:item:$itemId"))
        assertEquals(link, MusicLinkParser.fromText("jellyfin:item:$itemId"))
    }

    @Test fun `the reference is read case-insensitively and trimmed`() {
        assertEquals(link, MusicLinkParser.parse("  JELLYFIN:ITEM:$itemId  "))
        assertEquals(link, MusicLinkParser.parse("Jellyfin:Item:$itemId"))
    }

    @Test fun `the stored link is canonical, so the same song is the same string everywhere`() {
        // Two spellings in, one spelling out: pins, playlists, backups and sync all compare the
        // stored url as text, so a link that round-tripped differently would look like an edit.
        assertEquals("jellyfin:item:$itemId", MusicLinkParser.parse(" jellyfin:item:$itemId ")?.url)
        assertEquals(MusicProvider.JELLYFIN, MusicLinkParser.parse("JELLYFIN:ITEM:$itemId")?.provider)
    }

    // ----- what is refused -----

    @Test fun `a malformed item reference is refused rather than guessed at`() {
        assertNull(MusicLinkParser.parse("jellyfin:item:"))
        assertNull(MusicLinkParser.parse("jellyfin:item:short"))
        assertNull(MusicLinkParser.parse("jellyfin:playlist:$itemId"))
        assertNull(MusicLinkParser.parse("jellyfin:item:$itemId/../../Users"))
        assertNull(MusicLinkParser.parse("https://jellyfin.example.com/web/index.html#!/details?id=$itemId"))
    }

    @Test fun `plain text is not a link`() {
        assertNull(MusicLinkParser.parse("Just a song title"))
        assertNull(MusicLinkParser.fromText("Sunsets by The xx"))
        assertNull(MusicLinkParser.parse(""))
        assertNull(MusicLinkParser.parse("   "))
    }

    /**
     * The links an older version of this app stored. Each one is refused now, which is what makes
     * the upgrade cleanup able to find them: [MusicLinkParser.isUnplayable] is true for exactly
     * these, and the rows that held them are stripped instead of being shown as unplayable.
     */
    @Test fun `links from the removed streaming services are refused`() {
        val spotifyId = "4uLU6hMCjMI75M1A2tKUQC"
        val videoId = "dQw4w9WgXcQ"
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

    @Test fun `shared text no longer yields a link from another service`() {
        // fromText used to scan a pasted sentence for any supported share URL. With one link
        // shape left, a sentence about a streaming service has nothing in it for this app.
        assertNull(MusicLinkParser.fromText("Listen to this: https://open.spotify.com/track/x!"))
        assertNull(MusicLinkParser.fromText("Check it out https://youtu.be/dQw4w9WgXcQ)"))
        assertEquals(link, MusicLinkParser.fromText("jellyfin:item:$itemId"))
    }

    // ----- the rule the upgrade cleanup is built on -----

    @Test fun `isUnplayable is false for a link that plays and for no link at all`() {
        assertFalse(MusicLinkParser.isUnplayable("jellyfin:item:$itemId"))
        // A playlist row with no link is a song the listener typed in, or one that plays from an
        // attached file. It has nothing to do with a removed service, so it is never "unplayable".
        assertFalse(MusicLinkParser.isUnplayable(null))
    }

    @Test fun `isUnplayable is true for a dead link, whatever else it looks like`() {
        assertTrue(MusicLinkParser.isUnplayable("https://open.spotify.com/track/abc"))
        assertTrue(MusicLinkParser.isUnplayable("jellyfin:item:short"))
        assertTrue(MusicLinkParser.isUnplayable("not a link"))
    }
}
