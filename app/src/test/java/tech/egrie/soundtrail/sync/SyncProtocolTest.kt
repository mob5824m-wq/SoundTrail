package tech.egrie.soundtrail.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.egrie.soundtrail.automation.AutomationRule
import tech.egrie.soundtrail.automation.MotionKind
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.Playlist
import tech.egrie.soundtrail.data.PlaylistItem
import tech.egrie.soundtrail.integrations.MusicProvider

class SyncProtocolTest {
    @Test fun `recovery key is random url safe 256 bit material`() {
        val key = SyncCrypto.newRecoveryKey()
        val encoded = SyncCrypto.encodeRecoveryKey(key)
        assertEquals(32, SyncCrypto.decodeRecoveryKey(encoded).size)
        assertEquals(43, encoded.length)
        assertTrue(encoded.matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    @Test fun `event payload encrypts and decrypts with authenticated event id`() {
        val key = SyncCrypto.newRecoveryKey()
        val record = SyncRecord(SYNC_KIND_PIN, "pin-1", 7L, "device-a", false, "{\"title\":\"Night Swim\"}")
        val id = "a07f25fb-9d7b-40ed-b3d2-2b92244edb6f"
        val encrypted = SyncCrypto.encrypt(record, key, id)
        assertFalse(encrypted.ciphertext.contains("Night Swim"))
        assertEquals(record, SyncCrypto.decrypt(encrypted, key))
        val swapped = encrypted.copy(id = "9f55afc6-05e4-4e26-87d8-44ce66f9acb3")
        try {
            SyncCrypto.decrypt(swapped, key)
            throw AssertionError("event id must be authenticated")
        } catch (_: Exception) {
            // expected: GCM's authenticated additional data no longer matches
        }
    }

    @Test fun `wrong key and malformed key fail closed`() {
        val record = SyncRecord(SYNC_KIND_PIN, "pin-1", 1L, "d1", false, "{}")
        val encrypted = SyncCrypto.encrypt(record, SyncCrypto.newRecoveryKey(), "event-1")
        try {
            SyncCrypto.decrypt(encrypted, SyncCrypto.newRecoveryKey())
            throw AssertionError("wrong key must not reveal payload")
        } catch (_: Exception) {
            // expected
        }
        try {
            SyncCrypto.decodeRecoveryKey("too-short")
            throw AssertionError("short key must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun `encrypted snapshots round trip and bind their cursor to the ciphertext`() {
        val key = SyncCrypto.newRecoveryKey()
        val record = SyncRecord(SYNC_KIND_PIN, "pin-1", 7L, "device-a", false, "{\"title\":\"Night Swim\"}")
        val snapshot = SyncCrypto.encryptSnapshot(
            listOf(record), key, "a07f25fb-9d7b-40ed-b3d2-2b92244edb6f", throughCursor = 42L
        )
        assertEquals(listOf(record), SyncCrypto.decryptSnapshot(snapshot, key))
        try {
            SyncCrypto.decryptSnapshot(snapshot.copy(throughCursor = 43L), key)
            throw AssertionError("snapshot cursor must be authenticated")
        } catch (_: Exception) {
            // expected: the cursor is part of GCM's additional authenticated data
        }
    }

    @Test fun `merge picks a deterministic last write and keeps tombstones`() {
        val first = SyncRecord(SYNC_KIND_PIN, "p", 4L, "device-a", false, "{\"title\":\"old\"}")
        val newer = first.copy(clock = 5L, payload = "{\"title\":\"new\"}")
        val delete = newer.copy(clock = 6L, deleted = true, payload = null)
        val state = SyncMerge.merge(emptyMap(), listOf(first, newer, delete))
        assertEquals(delete, state[delete.key])
        assertTrue(state.getValue(delete.key).deleted)

        val tieA = first.copy(clock = 7L, deviceId = "a", payload = "{\"v\":1}")
        val tieB = first.copy(clock = 7L, deviceId = "b", payload = "{\"v\":2}")
        assertEquals(tieB, SyncMerge.merge(mapOf(tieA.key to tieA), listOf(tieB))[tieA.key])
    }

    @Test fun `playlist metadata and songs become separate sync entities without audio files`() {
        val pin = MusicPin(
            "p1", "Night Swim", "The Marías", "Beach", "note",
            "jellyfin:item:4cOdK2wGLETKBW3PvgPWqT",
            MusicProvider.JELLYFIN, GeoPoint(43.6, -79.4), 100L
        )
        val playlist = Playlist("list-1", "Walk", listOf(
            PlaylistItem("song-1", "Ribs", "Lorde", null, null, 2L, localFile = "secret-device-file.mp3"),
            PlaylistItem("song-2", "Green Light", "Lorde", null, null, 3L)
        ), 1L)
        val rule = AutomationRule("rule-1", "Run", "list-1", activity = MotionKind.RUNNING)
        val records = SyncPayloads.snapshot(listOf(pin), listOf(playlist), listOf(rule))
        assertEquals(5, records.size) // pin + playlist + two songs + automation
        assertEquals(2, records.count { it.kind == SYNC_KIND_PLAYLIST_ITEM })
        assertTrue(records.none { it.payload.orEmpty().contains("secret-device-file") })

        val restored = SyncPayloads.restore(records.associateBy { it.key }, emptyList()) { false }
        assertEquals(listOf(pin), restored.pins)
        assertEquals(listOf("song-1", "song-2"), restored.playlists.single().items.map { it.id })
        assertNull(restored.playlists.single().items.first().localFile)
        assertEquals(listOf(rule), restored.automations)
    }

    @Test fun `restoration rejects a record whose id differs from its encrypted payload id`() {
        val pin = MusicPin(
            "pin-1", "Night Swim", "The Marías", "Beach", "note",
            "jellyfin:item:4cOdK2wGLETKBW3PvgPWqT",
            MusicProvider.JELLYFIN, GeoPoint(43.6, -79.4), 100L
        )
        val record = SyncPayloads.snapshot(listOf(pin), emptyList(), emptyList()).single()
        val mismatched = record.copy(entityId = "pin-2")

        val restored = SyncPayloads.restore(mapOf(mismatched.key to mismatched), emptyList()) { false }
        assertTrue(restored.pins.isEmpty())
    }

    @Test fun `same local audio files are retained when remote metadata changes`() {
        val playlist = Playlist(
            "list-1", "Walk", listOf(
                PlaylistItem("song-1", "Ribs", "Lorde", null, null, 2L, localFile = "keep.mp3")
            ), 1L
        )
        val remote = playlist.copy(name = "New name")
        val records = SyncPayloads.snapshot(emptyList(), listOf(remote), emptyList())
        val restored = SyncPayloads.restore(records.associateBy { it.key }, listOf(playlist)) { it == "keep.mp3" }
        assertEquals("New name", restored.playlists.single().name)
        assertEquals("keep.mp3", restored.playlists.single().items.single().localFile)
    }
}
