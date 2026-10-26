package tech.egrie.soundtrail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tech.egrie.soundtrail.automation.AutomationRule
import tech.egrie.soundtrail.automation.MotionKind
import tech.egrie.soundtrail.integrations.MusicProvider

class BackupFormatTest {
    private val pin = MusicPin(
        id = "pin-1", title = "Night Swim", artist = "The Marías", place = "Hanlan's Point",
        note = "Sunset swim, phone in a bag", url = "jellyfin:item:4cOdK2wGLETKBW3PvgPWqT",
        provider = MusicProvider.JELLYFIN, point = GeoPoint(43.6259, -79.4047), createdAt = 1_700_000_000_000L
    )
    private val playlist = Playlist(
        id = "pl-1", name = "Island ferries", createdAt = 1_700_000_100_000L,
        items = listOf(
            PlaylistItem(
                id = "it-1", title = "Ribs", artist = "Lorde", url = "jellyfin:item:4cOdK2wGLETKBW3PvgPWqT",
                provider = MusicProvider.JELLYFIN, createdAt = 1_700_000_200_000L, localFile = "123.mp3"
            ),
            PlaylistItem(
                id = "it-2", title = "Handwritten", artist = "The Bathrels", url = null,
                provider = null, createdAt = 1_700_000_300_000L
            )
        )
    )
    private val rule = AutomationRule(
        id = "rule-1", name = "Morning ferry", playlistId = "pl-1", enabled = false,
        place = GeoPoint(43.6451, -79.3821), radiusMeters = 350.0, activity = MotionKind.DRIVING,
        fromMinute = 7 * 60, toMinute = 9 * 60 + 30, cooldownMinutes = 45L
    )

    @Test fun `round-trips pins, playlists, and automations`() {
        val text = BackupFormat.export(listOf(pin), listOf(playlist), listOf(rule))
        val back = BackupFormat.parse(text)

        assertEquals(1, back.pins.size)
        assertEquals(pin, back.pins.single())

        assertEquals(1, back.playlists.size)
        assertEquals(playlist, back.playlists.single())

        assertEquals(1, back.automations.size)
        assertEquals(rule, back.automations.single())
    }

    @Test fun `labels the document so a non-backup file is refused`() {
        assertTrue(BackupFormat.export(emptyList(), emptyList(), emptyList()).contains(BackupFormat.FORMAT))
        val notABackup = """{"pins": [], "playlists": []}"""
        try {
            BackupFormat.parse(notABackup)
            fail("a JSON file without the format marker must be refused")
        } catch (expected: BackupException) {
            assertTrue(expected.message.orEmpty().contains("not a Soundtrail backup"))
        }
    }

    @Test fun `refuses plain text and newer backup versions`() {
        listOf("", "not json at all", "[1,2,3]").forEach { junk ->
            try {
                BackupFormat.parse(junk)
                fail("junk must be refused: $junk")
            } catch (expected: BackupException) {
                // expected
            }
        }
        val future = """{"format":"${BackupFormat.FORMAT}","version":${BackupFormat.VERSION + 1}}"""
        try {
            BackupFormat.parse(future)
            fail("a newer version must be refused, not misread")
        } catch (expected: BackupException) {
            assertTrue(expected.message.orEmpty().contains("newer"))
        }
    }

    @Test fun `drops entries the stores would refuse`() {
        val text = """
            {
              "format": "soundtrail-backup",
              "version": 1,
              "pins": [
                {"id":"ok","title":"Song","place":"P","note":"","artist":"",
                 "url":"jellyfin:item:4cOdK2wGLETKBW3PvgPWqT",
                 "lat":10.0,"lon":20.0,"createdAt":5},
                {"id":"bad","title":"Unsupported link","url":"https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
                 "lat":10.0,"lon":20.0,"createdAt":6},
                {"id":"bad2","title":"Off-world","url":"jellyfin:item:4cOdK2wGLETKBW3PvgPWqT",
                 "lat":91.0,"lon":20.0,"createdAt":7}
              ],
              "playlists": [],
              "automations": []
            }
        """.trimIndent()
        val back = BackupFormat.parse(text)
        assertEquals(listOf("ok"), back.pins.map { it.id })
        assertTrue(back.playlists.isEmpty())
        assertTrue(back.automations.isEmpty())
    }

    @Test fun `an empty backup parses to empty lists`() {
        val back = BackupFormat.parse(BackupFormat.export(emptyList(), emptyList(), emptyList()))
        assertTrue(back.pins.isEmpty())
        assertTrue(back.playlists.isEmpty())
        assertTrue(back.automations.isEmpty())
    }

    @Test fun `playlist songs keep link-less entries and their local file hints`() {
        val back = BackupFormat.parse(BackupFormat.export(emptyList(), listOf(playlist), emptyList()))
        val items = back.playlists.single().items
        assertEquals(2, items.size)
        assertNull(items[1].url)
        assertNull(items[1].provider)
        assertEquals("123.mp3", items[0].localFile)
        assertEquals(MusicProvider.JELLYFIN, items[0].provider)
    }

    @Test fun `automations without a place keep motion and time conditions`() {
        val ruleOnly = AutomationRule(
            id = "rule-2", name = "Runs", playlistId = "pl-1",
            activity = MotionKind.RUNNING, fromMinute = 6 * 60, toMinute = 7 * 60
        )
        val back = BackupFormat.parse(BackupFormat.export(emptyList(), emptyList(), listOf(ruleOnly)))
        val parsed = back.automations.single()
        assertNull(parsed.place)
        assertEquals(MotionKind.RUNNING, parsed.activity)
        assertEquals(6 * 60, parsed.fromMinute)
        assertTrue(parsed.enabled)
        assertEquals(15L, parsed.cooldownMinutes)
    }

    @Test fun `night-wrap time windows survive the file`() {
        val night = rule.copy(fromMinute = 22 * 60, toMinute = 2 * 60, place = null, activity = null)
        val back = BackupFormat.parse(BackupFormat.export(emptyList(), emptyList(), listOf(night)))
        assertEquals(22 * 60, back.automations.single().fromMinute)
        assertEquals(2 * 60, back.automations.single().toMinute)
    }
}
