package tech.egrie.soundtrail.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's input limits, asserted locally before an upload.
 *
 * These numbers are the server's own constants — 100 events per request, a 12-byte nonce, 1 MiB
 * per encrypted event, 8 MiB per encrypted checkpoint. Checking them here means a too-large
 * payload is refused with a sentence the listener can act on, instead of being uploaded and
 * answered with a `400` or `413` that says only "too big" after the bytes have already crossed.
 *
 * Nothing here re-implements or inspects the encryption: [SyncCrypto] is deliberately untouched,
 * and these helpers only measure what it produced.
 */
class SyncEnvelopeLimitsTest {

    /** 16 base64url characters decode to exactly 12 bytes, which is the nonce size GCM needs. */
    private val goodIv = "A".repeat(16)

    private fun envelope(iv: String = goodIv, ciphertext: String = "A".repeat(64)) = JSONObject()
        .put("id", "11111111-1111-1111-1111-111111111111")
        .put("iv", iv)
        .put("ciphertext", ciphertext)

    // ----- the limits match the server's -----

    @Test fun `the limits are the server's limits`() {
        assertEquals(100, SyncEnvelopeLimits.MAX_EVENTS_PER_REQUEST)
        assertEquals(1024 * 1024, SyncEnvelopeLimits.MAX_EVENT_BYTES)
        assertEquals(8 * 1024 * 1024, SyncEnvelopeLimits.MAX_SNAPSHOT_BYTES)
        assertEquals(12, SyncEnvelopeLimits.IV_BYTES)
    }

    // ----- base64url size arithmetic -----

    @Test fun `a decoded size is computed, not guessed`() {
        assertEquals(12, SyncEnvelopeLimits.decodedSize("A".repeat(16)))
        assertEquals(3, SyncEnvelopeLimits.decodedSize("AAAA"))
        assertEquals(6, SyncEnvelopeLimits.decodedSize("AAAAAAAA"))
        // Unpadded base64url is what this app and the server both use.
        assertEquals(5, SyncEnvelopeLimits.decodedSize("AAAAAAA"))
    }

    @Test fun `a value that is not base64url has no decoded size`() {
        assertNull(SyncEnvelopeLimits.decodedSize(""))
        assertNull(SyncEnvelopeLimits.decodedSize("not base64!"))
        assertNull(SyncEnvelopeLimits.decodedSize("with space"))
        // Standard base64's `+` and `/` are not base64url, and the server's own validator agrees.
        assertNull(SyncEnvelopeLimits.decodedSize("ab+cd/ef=="))
    }

    // ----- one encrypted event -----

    @Test fun `a well-formed event passes`() {
        assertNull(SyncEnvelopeLimits.eventProblem(envelope()))
    }

    @Test fun `a nonce that is not 12 bytes is refused with the sizes in the message`() {
        val problem = SyncEnvelopeLimits.eventProblem(envelope(iv = "A".repeat(12)))
        assertNotNull(problem)
        assertTrue(problem!!, problem.contains("9-byte"))
        assertTrue(problem, problem.contains("12"))

        val tooLong = SyncEnvelopeLimits.eventProblem(envelope(iv = "A".repeat(24)))
        assertNotNull(tooLong)
        assertTrue(tooLong!!, tooLong.contains("18-byte"))
    }

    @Test fun `a nonce that is not base64url at all is refused`() {
        val problem = SyncEnvelopeLimits.eventProblem(envelope(iv = "not a nonce!!"))
        assertNotNull(problem)
        assertTrue(problem!!, problem.contains("could not be prepared"))
    }

    @Test fun `a missing field is refused rather than read as empty`() {
        assertNotNull(SyncEnvelopeLimits.eventProblem(JSONObject().put("id", "x")))
        assertNotNull(SyncEnvelopeLimits.eventProblem(JSONObject()))
    }

    @Test fun `an event over 1 MiB is refused locally`() {
        // 1,400,000 base64url characters decode to 1,050,000 bytes: over the server's 1 MiB.
        val problem = SyncEnvelopeLimits.eventProblem(envelope(ciphertext = "A".repeat(1_400_000)))
        assertNotNull(problem)
        assertTrue(problem!!, problem.contains("over the server's"))
        assertTrue(problem, problem.contains("1024 KiB"))
    }

    @Test fun `an event just under 1 MiB is allowed`() {
        // 1,398,000 characters decode to 1,048,500 bytes: under the limit, so no false refusal.
        assertNull(SyncEnvelopeLimits.eventProblem(envelope(ciphertext = "A".repeat(1_398_000))))
    }

    // ----- one encrypted checkpoint -----

    @Test fun `a well-formed checkpoint passes`() {
        assertNull(SyncEnvelopeLimits.snapshotProblem(envelope()))
    }

    @Test fun `a checkpoint with a bad nonce is refused`() {
        assertNotNull(SyncEnvelopeLimits.snapshotProblem(envelope(iv = "A".repeat(8))))
        assertNotNull(SyncEnvelopeLimits.snapshotProblem(envelope(iv = "###")))
    }

    @Test fun `a checkpoint over 8 MiB is refused locally`() {
        // 11,200,000 characters decode to 8,400,000 bytes: over the server's 8 MiB checkpoint cap.
        val problem = SyncEnvelopeLimits.snapshotProblem(envelope(ciphertext = "A".repeat(11_200_000)))
        assertNotNull(problem)
        assertTrue(problem!!, problem.contains("over"))
        assertTrue(problem, problem.contains("8 MiB"))
        // And it says what to do about it, which a bare 413 never would.
        assertTrue(problem, problem.contains("Sync more often"))
    }

    @Test fun `a 1 MiB event is not confused with the checkpoint limit`() {
        // The same payload is over the event limit but well under the checkpoint limit, which is
        // the point of having two numbers: an event is a change, a checkpoint is the whole state.
        val ciphertext = "A".repeat(1_400_000)
        assertNotNull(SyncEnvelopeLimits.eventProblem(envelope(ciphertext = ciphertext)))
        assertNull(SyncEnvelopeLimits.snapshotProblem(envelope(ciphertext = ciphertext)))
    }
}
