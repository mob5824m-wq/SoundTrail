package tech.egrie.soundtrail.mood

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The consent record is the part of the destination prompt with actual rules in it: one approval
 * at a time, matched to the exact provider and endpoint, withdrawable, and never carried over to
 * a destination the listener has not seen. Those rules are tested here, off any device.
 */
class MoodConsentRecordTest {
    /** The whole storage contract: a map, with null meaning "remove the key". */
    private class MemoryStorage : MoodConsentStorage {
        val values = mutableMapOf<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private val ollamaHere = MoodAiDestination(MoodAiProvider.OLLAMA, "http://192.168.0.20:11434")
    private val ollamaThere = MoodAiDestination(MoodAiProvider.OLLAMA, "http://192.168.0.21:11434")
    private val gemini = MoodAiDestination(MoodAiProvider.GEMINI, GEMINI_API_BASE)

    @Test
    fun `nothing is approved until the listener approves something`() {
        val record = MoodConsentRecord(MemoryStorage())

        assertFalse(record.isApproved(ollamaHere))
        assertFalse(record.isApproved(gemini))
        assertNull(record.approvedAt())
    }

    @Test
    fun `approving one destination approves exactly that one`() {
        val record = MoodConsentRecord(MemoryStorage())

        record.remember(ollamaHere, atMs = 1_700_000_000_000)

        assertTrue(record.isApproved(ollamaHere))
        assertFalse(record.isApproved(ollamaThere))
        assertFalse(record.isApproved(gemini))
        assertEquals(1_700_000_000_000L, record.approvedAt())
    }

    @Test
    fun `moving the address asks again rather than reusing the old answer`() {
        val record = MoodConsentRecord(MemoryStorage())
        record.remember(ollamaHere, atMs = 1_700_000_000_000)

        record.remember(ollamaThere, atMs = 1_700_000_100_000)

        assertFalse(record.isApproved(ollamaHere))
        assertTrue(record.isApproved(ollamaThere))
        assertEquals(1_700_000_100_000L, record.approvedAt())
    }

    @Test
    fun `a different provider is never satisfied by another provider's approval`() {
        val record = MoodConsentRecord(MemoryStorage())
        // The same endpoint string is not enough: the provider is part of the destination.
        val asGemini = MoodAiDestination(MoodAiProvider.GEMINI, ollamaHere.endpoint)
        record.remember(ollamaHere, atMs = 1)

        assertFalse(record.isApproved(asGemini))
        assertTrue(record.isApproved(ollamaHere))
    }

    @Test
    fun `withdrawing clears both the approval and its timestamp`() {
        val storage = MemoryStorage()
        val record = MoodConsentRecord(storage)
        record.remember(ollamaHere, atMs = 1_700_000_000_000)

        record.forget()

        assertFalse(record.isApproved(ollamaHere))
        assertNull(record.approvedAt())
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun `withdrawing twice is harmless`() {
        val record = MoodConsentRecord(MemoryStorage())
        record.forget()
        record.forget()

        assertFalse(record.isApproved(ollamaHere))
        assertNull(record.approvedAt())
    }

    @Test
    fun `a timestamp without its approval is not an approval in force`() {
        // What an interrupted or partially restored write could leave behind.
        val storage = MemoryStorage()
        storage.values[MoodConsentRecord.KEY_APPROVED_AT] = "1700000000000"

        val record = MoodConsentRecord(storage)

        assertNull(record.approvedAt())
        assertFalse(record.isApproved(ollamaHere))
    }

    @Test
    fun `an unreadable timestamp reports no time but still honours the approval`() {
        val storage = MemoryStorage()
        val record = MoodConsentRecord(storage)
        record.remember(ollamaHere, atMs = 1_700_000_000_000)
        storage.values[MoodConsentRecord.KEY_APPROVED_AT] = "not-a-number"

        // The approval itself is intact; only the "approved at" line is unavailable.
        assertTrue(record.isApproved(ollamaHere))
        assertNull(record.approvedAt())
    }

    @Test
    fun `a negative timestamp is treated as no time`() {
        val storage = MemoryStorage()
        val record = MoodConsentRecord(storage)
        record.remember(ollamaHere, atMs = -1)

        assertTrue(record.isApproved(ollamaHere))
        assertNull(record.approvedAt())
    }

    @Test
    fun `one approval is stored as one fingerprint, not a growing list`() {
        val storage = MemoryStorage()
        val record = MoodConsentRecord(storage)

        record.remember(ollamaHere, atMs = 1)
        record.remember(ollamaThere, atMs = 2)
        record.remember(gemini, atMs = 3)

        // The fingerprint and its timestamp — nothing accumulates as destinations change.
        assertEquals(2, storage.values.size)
        assertTrue(record.isApproved(gemini))
        assertFalse(record.isApproved(ollamaHere))
        assertFalse(record.isApproved(ollamaThere))
    }
}
