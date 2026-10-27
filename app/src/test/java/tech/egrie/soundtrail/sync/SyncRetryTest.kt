package tech.egrie.soundtrail.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What may be tried again, and how long to wait.
 *
 * The distinction under test is not arithmetic, it is consequence: a retry of a call the server
 * deduplicates is a re-delivery, and a retry of a call that creates something is a second attempt
 * to create it. Every case here is one of those two, and the wait is asserted exactly so a change
 * to the backoff shows up as a changed number rather than as a listener waiting longer.
 *
 * Nothing suspends: [SyncRetry.decide] returns a wait and the caller performs it, which is what
 * makes this testable without a clock or a coroutine.
 */
class SyncRetryTest {

    private val retryable = SyncRetry.Idempotence.RETRYABLE
    private val once = SyncRetry.Idempotence.ONCE

    private fun retry(decision: SyncRetry.Decision): Long =
        (decision as SyncRetry.Decision.Retry).waitMillis

    // ----- the call that must not be repeated -----

    @Test fun `a call that changes account state is never retried, whatever came back`() {
        // Sign-in, registration, token renewal and password change are all ONCE. A 429 on sign-in
        // goes back to the listener instead of being retried behind them, and an ambiguous
        // failure — no answer at all — is the case that must never be guessed about.
        listOf(429, 408, 500, 502, 503, 504, null).forEach { status ->
            assertEquals(
                "status $status",
                SyncRetry.Decision.GiveUp,
                SyncRetry.decide(status, retryAfterSeconds = 30, attempt = 1, idempotence = once, jitterMillis = 0)
            )
        }
    }

    // ----- rate limits -----

    @Test fun `a rate limit waits as long as the server asked, plus jitter`() {
        assertEquals(
            20_000L,
            retry(SyncRetry.decide(429, retryAfterSeconds = 20, attempt = 1, idempotence = retryable, jitterMillis = 0))
        )
        assertEquals(
            20_123L,
            retry(SyncRetry.decide(429, retryAfterSeconds = 20, attempt = 1, idempotence = retryable, jitterMillis = 123))
        )
    }

    @Test fun `a rate limit is capped at a minute however long the server asks for`() {
        assertEquals(
            SyncRetry.RETRY_AFTER_CAP_SECONDS * 1_000L,
            retry(SyncRetry.decide(429, retryAfterSeconds = 3_600, attempt = 1, idempotence = retryable, jitterMillis = 0))
        )
    }

    @Test fun `a rate limit with no usable header still waits a sane default`() {
        assertEquals(
            SyncRetry.DEFAULT_RETRY_AFTER_SECONDS * 1_000L,
            retry(SyncRetry.decide(429, retryAfterSeconds = null, attempt = 1, idempotence = retryable, jitterMillis = 0))
        )
        // Zero and negative values are not a wait the app should perform literally.
        assertEquals(
            1_000L,
            retry(SyncRetry.decide(429, retryAfterSeconds = 0, attempt = 1, idempotence = retryable, jitterMillis = 0))
        )
    }

    @Test fun `the rate limit message tells the listener how long, in the units they think in`() {
        assertEquals("Too many attempts. Try again in 45 seconds.", SyncRetry.rateLimitMessage(45))
        assertEquals("Too many attempts. Try again in 1 minute.", SyncRetry.rateLimitMessage(60))
        // Anything past the cap is reported as the cap, because that is how long the app will
        // actually wait — saying "2 minutes" and then retrying after one would be a lie.
        assertEquals("Too many attempts. Try again in 1 minute.", SyncRetry.rateLimitMessage(61))
        assertEquals("Too many attempts. Try again in 1 minute.", SyncRetry.rateLimitMessage(3_600))
        assertEquals("Too many attempts. Try again in 30 seconds.", SyncRetry.rateLimitMessage(null))
    }

    // ----- transient failures -----

    @Test fun `a stalled body and the gateway failures back off exponentially`() {
        listOf(408, 502, 503, 504).forEach { status ->
            assertEquals(
                "status $status attempt 1",
                1_000L,
                retry(SyncRetry.decide(status, null, attempt = 1, idempotence = retryable, jitterMillis = 0))
            )
            assertEquals(
                "status $status attempt 2",
                2_000L,
                retry(SyncRetry.decide(status, null, attempt = 2, idempotence = retryable, jitterMillis = 0))
            )
        }
    }

    @Test fun `no answer at all is retried only for a call the server deduplicates`() {
        // A timeout or a dropped connection is the ambiguous case: the request may have been acted
        // on. For an event push that is fine, because the event id makes a repeat a no-op.
        assertEquals(
            1_000L,
            retry(SyncRetry.decide(null, null, attempt = 1, idempotence = retryable, jitterMillis = 0))
        )
    }

    @Test fun `backoff doubles and then stops at the cap`() {
        assertEquals(1_000L, SyncRetry.backoffMillis(1))
        assertEquals(2_000L, SyncRetry.backoffMillis(2))
        assertEquals(4_000L, SyncRetry.backoffMillis(3))
        assertEquals(8_000L, SyncRetry.backoffMillis(4))
        assertEquals(8_000L, SyncRetry.backoffMillis(50))
        // A caller that counts from zero still gets a real wait, never a negative or a zero one.
        assertEquals(1_000L, SyncRetry.backoffMillis(0))
    }

    // ----- how many attempts -----

    @Test fun `at most two retries, so three attempts in total`() {
        assertEquals(3, SyncRetry.MAX_ATTEMPTS)
        assertTrue(
            SyncRetry.decide(503, null, attempt = 1, idempotence = retryable, jitterMillis = 0)
                is SyncRetry.Decision.Retry
        )
        assertTrue(
            SyncRetry.decide(503, null, attempt = 2, idempotence = retryable, jitterMillis = 0)
                is SyncRetry.Decision.Retry
        )
        assertEquals(
            SyncRetry.Decision.GiveUp,
            SyncRetry.decide(503, null, attempt = 3, idempotence = retryable, jitterMillis = 0)
        )
    }

    // ----- what is not transient -----

    @Test fun `a real answer about this request is not retried`() {
        // These are not "ask again later"; they are "that was wrong". Retrying cannot change them,
        // and retrying a 507 in particular would skip the compaction that is the actual fix.
        listOf(400, 401, 403, 404, 409, 413, 422, 500, 507).forEach { status ->
            assertEquals(
                "status $status",
                SyncRetry.Decision.GiveUp,
                SyncRetry.decide(status, null, attempt = 1, idempotence = retryable, jitterMillis = 0)
            )
        }
    }

    @Test fun `jitter is bounded, so it cannot become the wait`() {
        repeat(200) {
            val value = SyncRetry.jitter()
            assertTrue("jitter $value", value in 0..SyncRetry.MAX_JITTER_MILLIS)
        }
    }
}
