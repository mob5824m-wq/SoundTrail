package tech.egrie.soundtrail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the one-time upgrade cleanup is built on.
 *
 * These are the decisions that touch somebody's library, so they are pinned down here rather than
 * discovered on a phone: which stored rows are dead, which survive because the listener attached
 * audio of their own, and which playlists were minted by an import that no longer exists. The
 * counting helpers read the stored JSON because that is the only place a dead link still exists —
 * by the time a row is a [MusicPin] it has already been refused and dropped.
 */
class LegacyServiceCleanupTest {

    private val live = "jellyfin:item:4cOdK2wGLETKBW3PvgPWqT"
    private val deadSpotify = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"
    private val deadYouTube = "https://music.youtube.com/watch?v=dQw4w9WgXcQ"
    private val deadSoundCloud = "https://soundcloud.com/matas/hobnotropic"

    private fun pin(id: String, url: String?) = buildString {
        append("{\"id\":\"$id\",\"title\":\"T\",\"artist\":\"A\",\"place\":\"P\",\"note\":\"\",")
        if (url != null) append("\"url\":\"$url\",")
        append("\"lat\":43.6,\"lon\":-79.4,\"createdAt\":1}")
    }

    private fun item(id: String, url: String?, localFile: String? = null) = buildString {
        append("{\"id\":\"$id\",\"title\":\"T\",\"artist\":\"A\",")
        if (url != null) append("\"url\":\"$url\",")
        if (localFile != null) append("\"localFile\":\"$localFile\",")
        append("\"createdAt\":1}")
    }

    private fun playlist(id: String, vararg items: String) =
        "{\"id\":\"$id\",\"name\":\"List\",\"createdAt\":1,\"items\":[${items.joinToString(",")}]}"

    // ----- pins -----

    @Test fun `a pin whose link belonged to a removed service is counted`() {
        val json = "[${pin("a", deadSpotify)},${pin("b", deadYouTube)},${pin("c", deadSoundCloud)}]"
        assertEquals(3, LegacyServiceCleanup.countUnplayablePins(json))
    }

    @Test fun `a pin that still plays is not counted`() {
        assertEquals(0, LegacyServiceCleanup.countUnplayablePins("[${pin("a", live)}]"))
    }

    @Test fun `a pin that never had a usable link is not blamed on the upgrade`() {
        // A row with no url at all was already broken before the services were removed. Counting
        // it here would tell the listener this upgrade took something it did not take.
        assertEquals(0, LegacyServiceCleanup.countUnplayablePins("[${pin("a", null)}]"))
        assertEquals(0, LegacyServiceCleanup.countUnplayablePins("[]"))
        assertEquals(0, LegacyServiceCleanup.countUnplayablePins("not json"))
    }

    @Test fun `a mixed library counts only the dead rows`() {
        val json = "[${pin("keep", live)},${pin("gone", deadSpotify)},${pin("keep2", live)}]"
        assertEquals(1, LegacyServiceCleanup.countUnplayablePins(json))
    }

    // ----- playlist songs -----

    @Test fun `a song whose only content was a dead link is removed`() {
        val json = "[${playlist("p1", item("s1", deadSpotify), item("s2", deadYouTube))}]"
        val (removed, keptOwnAudio) = LegacyServiceCleanup.countUnplayablePlaylistItems(json)
        assertEquals(2, removed)
        assertEquals(0, keptOwnAudio)
    }

    @Test fun `a song the listener attached audio to survives and loses only the dead link`() {
        // The file is theirs and it plays with no service at all, so removing the row would take
        // away audio this upgrade has no business taking.
        val json = "[${playlist("p1", item("s1", deadSpotify, localFile = "own.mp3"))}]"
        val (removed, keptOwnAudio) = LegacyServiceCleanup.countUnplayablePlaylistItems(json)
        assertEquals(0, removed)
        assertEquals(1, keptOwnAudio)
    }

    @Test fun `a song that still plays and a song with no link are both left alone`() {
        val json = "[${
            playlist(
                "p1",
                item("live", live),
                item("typed", null),
                item("typed-with-audio", null, localFile = "own.mp3")
            )
        }]"
        assertEquals(0 to 0, LegacyServiceCleanup.countUnplayablePlaylistItems(json))
    }

    @Test fun `counting spans every playlist and ignores a document that will not parse`() {
        val json = "[${playlist("p1", item("s1", deadSpotify))}," +
            "${playlist("p2", item("s2", deadYouTube, localFile = "own.mp3"), item("s3", live))}]"
        assertEquals(1 to 1, LegacyServiceCleanup.countUnplayablePlaylistItems(json))
        assertEquals(0 to 0, LegacyServiceCleanup.countUnplayablePlaylistItems("not json"))
        assertEquals(0 to 0, LegacyServiceCleanup.countUnplayablePlaylistItems("[]"))
        // A playlist with no items array at all is not a failure, it is an empty list.
        assertEquals(
            0 to 0,
            LegacyServiceCleanup.countUnplayablePlaylistItems("[{\"id\":\"p\",\"name\":\"n\",\"createdAt\":1}]")
        )
    }

    // ----- playlists the import minted -----

    @Test fun `an import list with nothing left in it is spent`() {
        assertTrue(LegacyServiceCleanup.isSpentImport("yt-pl:PL123", 0))
    }

    @Test fun `a list the listener named themselves is kept even when empty`() {
        assertFalse(LegacyServiceCleanup.isSpentImport("pl-mine", 0))
        assertFalse(LegacyServiceCleanup.isSpentImport("uuid-list", 0))
    }

    @Test fun `an import list that still has songs is kept`() {
        assertFalse(LegacyServiceCleanup.isSpentImport("yt-pl:PL123", 1))
        assertFalse(LegacyServiceCleanup.isSpentImport("yt-pl:PL123", 40))
    }

    // ----- what the listener is told -----

    @Test fun `the report says nothing when nothing was removed`() {
        assertTrue(LegacyServiceCleanup.Report().isEmpty)
        assertEquals("", LegacyServiceCleanup.Report().message())
    }

    @Test fun `the report names what went and why`() {
        val message = LegacyServiceCleanup.Report(pins = 3, songs = 12).message()
        assertTrue(message, message.contains("Removed 3 pins"))
        assertTrue(message, message.contains("12 playlist songs"))
        assertTrue(message, message.contains("no longer supports"))
    }

    @Test fun `the report separates songs that survived on their own audio`() {
        val message = LegacyServiceCleanup.Report(songs = 2, keptOwnAudio = 1).message()
        assertTrue(message, message.contains("Removed 2 playlist songs"))
        assertTrue(message, message.contains("1 song kept the audio file you attached"))
        assertTrue(message, message.contains("lost only the dead link"))
    }

    @Test fun `the report counts one of anything in the singular`() {
        val message = LegacyServiceCleanup.Report(pins = 1, songs = 1, playlists = 1).message()
        assertTrue(message, message.contains("1 pin "))
        assertTrue(message, message.contains("1 playlist song "))
        assertTrue(message, message.contains("1 empty list "))
        assertFalse(message, message.contains("1 pins"))
    }

    @Test fun `the report mentions spent import lists`() {
        val message = LegacyServiceCleanup.Report(playlists = 2).message()
        assertTrue(message, message.contains("2 empty lists"))
        assertTrue(message, message.contains("the old import had created"))
    }
}
