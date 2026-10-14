package tech.egrie.soundtrail.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether a failed refresh keeps a saved Google login or asks for a new
 * sign-in. They are the difference between "one tap, once" and "sign in again", so they are
 * pinned down here.
 */
class GoogleAuthPolicyTest {
    private val day = GoogleAuthPolicy.DAY_MS

    @Test fun `only google's own rejection loses the saved grant`() {
        assertTrue(
            GoogleAuthPolicy.isPermanentGrantFailure(
                400, "invalid_grant", "Token has been expired or revoked."
            )
        )
        assertTrue(GoogleAuthPolicy.isPermanentGrantFailure(401, "invalid_client", null))
        assertTrue(GoogleAuthPolicy.isPermanentGrantFailure(400, "unauthorized_client", null))
        // A 403 is deliberately treated as transient: the resource APIs use it for things like a
        // disabled API (accessNotConfigured), which is a configuration problem, not a lost grant.
        assertFalse(GoogleAuthPolicy.isPermanentGrantFailure(403, "access_denied", "The user denied access"))
        // Google's quirk: a dead refresh token can come back as invalid_grant with "Bad Request".
        assertTrue(GoogleAuthPolicy.isPermanentGrantFailure(400, "invalid_grant", "Bad Request"))
    }

    @Test fun `outages, throttling, and unknown errors keep the saved grant`() {
        val cases: List<Triple<Int, String?, String?>> = listOf(
            Triple(429, "rateLimitExceeded", "Too many requests"),
            Triple(500, "backendError", "Internal error"),
            Triple(503, "backendError", "Try again later"),
            Triple(400, "invalid_request", "Bad Request"),
            Triple(400, null, null),
            Triple(200, null, null)
        )
        cases.forEach { (code, error, description) ->
            assertFalse(
                "HTTP $code / $error must not clear a login",
                GoogleAuthPolicy.isPermanentGrantFailure(code, error, description)
            )
        }
    }

    @Test fun `a grant that dies about a week after issue points at testing mode`() {
        val now = 1_700_000_000_000L
        assertTrue(GoogleAuthPolicy.testingExpirySuspected(now - 7 * day, now))
        assertTrue(GoogleAuthPolicy.testingExpirySuspected(now - (7 * day + day / 2), now))
        assertFalse(GoogleAuthPolicy.testingExpirySuspected(now - 2 * day, now))
        assertFalse(GoogleAuthPolicy.testingExpirySuspected(now - 40 * day, now))
        assertFalse(GoogleAuthPolicy.testingExpirySuspected(null, now))
        assertFalse(GoogleAuthPolicy.testingExpirySuspected(now + day, now))
    }

    @Test fun `the reconnect hint names the real cause when testing mode is the suspect`() {
        val hint = GoogleAuthPolicy.helpText(
            state = GoogleAuthState.NEEDS_RECONNECT,
            method = "Client ID (browser)",
            clientIdRemembered = true,
            testingExpirySuspected = true
        ).orEmpty()
        assertTrue(hint.contains("7 days"))
        assertTrue(hint.contains("Client ID is remembered"))

        val plain = GoogleAuthPolicy.helpText(
            state = GoogleAuthState.NEEDS_RECONNECT,
            method = null,
            clientIdRemembered = false,
            testingExpirySuspected = false
        ).orEmpty()
        assertTrue(plain.contains("Connect the account again"))
        assertFalse(plain.contains("7 days"))
    }

    @Test fun `an offline or unreadable connection is never presented as a lost login`() {
        val offline = GoogleAuthPolicy.helpText(GoogleAuthState.OFFLINE, "Client ID (browser)", true, false)
        assertTrue(offline.orEmpty().contains("intact"))
        val unreadable = GoogleAuthPolicy.helpText(GoogleAuthState.UNREADABLE, "Client ID (browser)", true, false)
        assertTrue(unreadable.orEmpty().contains("kept"))
        assertNull(GoogleAuthPolicy.helpText(GoogleAuthState.CONNECTED, "Client ID (browser)", true, false))
    }

    @Test fun `connection health asks for attention only when the listener must act`() {
        assertFalse(GoogleAuthHealth(GoogleAuthState.CONNECTED).needsAttention)
        assertFalse(GoogleAuthHealth(GoogleAuthState.OFFLINE).needsAttention)
        assertFalse(GoogleAuthHealth(GoogleAuthState.NOT_CONNECTED).needsAttention)
        assertTrue(GoogleAuthHealth(GoogleAuthState.NEEDS_RECONNECT).needsAttention)
        assertTrue(GoogleAuthHealth(GoogleAuthState.UNREADABLE).needsAttention)
    }

    @Test fun `age stamps read like a person wrote them`() {
        val now = 1_700_000_000_000L
        assertEquals("just now", GoogleAuthPolicy.describeAge(now, now - 20_000L))
        assertEquals("5 min ago", GoogleAuthPolicy.describeAge(now, now - 5 * 60_000L))
        assertEquals("3 h ago", GoogleAuthPolicy.describeAge(now, now - 3 * 3_600_000L))
        assertEquals("2 d ago", GoogleAuthPolicy.describeAge(now, now - 2 * day))
        assertNull(GoogleAuthPolicy.describeAge(now, null))
        assertNull(GoogleAuthPolicy.describeAge(now, now + day))
    }
}
