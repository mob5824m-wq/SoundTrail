package tech.egrie.soundtrail.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrobblePolicyTest {
    @Test fun `threshold is half duration capped at four minutes`() {
        assertEquals(30_000L, ScrobblePolicy.thresholdMs(60_000L))
        assertEquals(120_000L, ScrobblePolicy.thresholdMs(240_000L))
        assertEquals(240_000L, ScrobblePolicy.thresholdMs(600_000L))
        assertEquals(240_000L, ScrobblePolicy.thresholdMs(0L))
    }

    @Test fun `scrobble when played enough`() {
        assertFalse(ScrobblePolicy.shouldScrobble(29_999L, 60_000L))
        assertTrue(ScrobblePolicy.shouldScrobble(30_000L, 60_000L))
        assertTrue(ScrobblePolicy.shouldScrobble(240_000L, 600_000L))
        assertFalse(ScrobblePolicy.shouldScrobble(0L, 60_000L))
    }

    @Test fun `clean normalizes and caps`() {
        assertEquals("A B", ScrobblePolicy.clean("  A\u0000  B  "))
        assertTrue(ScrobblePolicy.isSubmittable("Artist", "Title"))
        assertFalse(ScrobblePolicy.isSubmittable("", "Title"))
        assertFalse(ScrobblePolicy.isSubmittable("Artist", ""))
    }
}
