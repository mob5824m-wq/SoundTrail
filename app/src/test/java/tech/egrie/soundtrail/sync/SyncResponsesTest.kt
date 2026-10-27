package tech.egrie.soundtrail.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.egrie.soundtrail.integrations.HttpResponse

/**
 * What each status code the sync server can return means to the listener.
 *
 * This is the client half of the contract the server implements, written down as assertions so the
 * two sides can be reviewed against each other. Three things are pinned here that a string match
 * would have hidden:
 *
 * - the **status travels on the exception**, because `sync()` branches on `507` to compact the
 *   history and retry, and branching on wording breaks the moment wording improves;
 * - a **redirect is an exception**, never an answer, since `httpRequest` does not follow one and a
 *   followed redirect would carry the bearer token to a host nobody chose;
 * - the **registration `409` is actionable**, because "already exists" on its own leaves the
 *   listener looking at a form with no idea what to do next.
 */
class SyncResponsesTest {

    private fun response(code: Int, body: String = "", retryAfter: String? = null) = HttpResponse(
        code = code,
        body = body,
        headers = buildMap {
            if (retryAfter != null) put("retry-after", listOf(retryAfter))
        }
    )

    // ----- the status is the contract, not the wording -----

    @Test fun `the exception carries the status the server answered with`() {
        listOf(400, 401, 403, 404, 408, 409, 413, 422, 429, 500, 502, 503, 504, 507).forEach { code ->
            val failure = SyncResponses.failureFor(response(code), "/v1/events")
            assertEquals("status $code", code, failure.status)
            assertNotNull("status $code needs a message", failure.message)
            assertTrue("status $code message should not be blank", failure.message!!.isNotBlank())
        }
    }

    @Test fun `the quota is recognised by its status, whatever the server calls it`() {
        // The server's own wording, and a server that says nothing at all, both have to land on
        // 507 — that is what sync() branches on to compact the history and retry the push.
        val withDetail = SyncResponses.failureFor(
            response(507, """{"error":"This account has reached its encrypted sync storage limit."}"""),
            "/v1/events"
        )
        assertEquals(507, withDetail.status)
        assertEquals(SyncResponses.STATUS_INSUFFICIENT_STORAGE, withDetail.status)
        assertTrue(withDetail.message!!.contains("storage limit"))

        val bare = SyncResponses.failureFor(response(507), "/v1/events")
        assertEquals(507, bare.status)
        assertEquals(SyncResponses.QUOTA_MESSAGE, bare.message)
    }

    // ----- redirects -----

    @Test fun `a redirect becomes an exception, never a silent host change`() {
        listOf(301, 302, 303, 307, 308).forEach { code ->
            val failure = SyncResponses.failureFor(
                response(code, "", retryAfter = null),
                "/v1/events"
            )
            assertEquals("redirect $code", code, failure.status)
            assertTrue(
                "redirect $code should say it was not followed",
                failure.message!!.contains("does not follow")
            )
            assertTrue(
                "redirect $code should say why: the token",
                failure.message!!.contains("token")
            )
        }
    }

    @Test fun `a redirect is recognised as one`() {
        assertTrue(response(302).isRedirect)
        assertTrue(response(308).isRedirect)
        assertTrue(!response(200).isRedirect)
        assertTrue(!response(404).isRedirect)
    }

    // ----- rate limits -----

    @Test fun `a rate limit says how long to wait, using the server's own header`() {
        val failure = SyncResponses.failureFor(response(429, retryAfter = "90"), "/v1/events")
        assertEquals(429, failure.status)
        // 90 seconds is capped to the one-minute retry limit, then read out in minutes.
        assertEquals("Too many attempts. Try again in 1 minute.", failure.message)
    }

    @Test fun `a rate limit with no header still says how long`() {
        val failure = SyncResponses.failureFor(response(429), "/v1/sessions")
        assertEquals(429, failure.status)
        assertTrue(failure.message!!.startsWith("Too many attempts. Try again in "))
    }

    @Test fun `retry-after is read in whole seconds and ignored when unusable`() {
        assertEquals(45, response(429, retryAfter = "45").retryAfterSeconds)
        assertEquals(0, response(429, retryAfter = "0").retryAfterSeconds)
        // An HTTP-date form, a negative, and nonsense are all "no usable value" rather than a
        // guess about how long to wait.
        assertNull(response(429, retryAfter = "Wed, 21 Oct 2026 07:28:00 GMT").retryAfterSeconds)
        assertNull(response(429, retryAfter = "-30").retryAfterSeconds)
        assertNull(response(429, retryAfter = "soon").retryAfterSeconds)
        assertNull(response(429).retryAfterSeconds)
    }

    // ----- registration -----

    @Test fun `a duplicate registration says what to do instead`() {
        val failure = SyncResponses.failureFor(
            response(409, """{"error":"An account with that email already exists."}"""),
            "/v1/accounts"
        )
        assertEquals(409, failure.status)
        assertEquals(
            "That email already has an account on this server. Sign in instead.",
            failure.message
        )
    }

    @Test fun `the server's own 409 wording is kept for every other endpoint`() {
        val failure = SyncResponses.failureFor(
            response(409, """{"error":"An event id cannot be reused with a different payload."}"""),
            "/v1/events"
        )
        assertEquals(409, failure.status)
        assertEquals("An event id cannot be reused with a different payload.", failure.message)
    }

    // ----- the rest of the contract -----

    @Test fun `an expired session says to sign in again`() {
        val failure = SyncResponses.failureFor(response(401), "/v1/events")
        assertEquals(401, failure.status)
        assertEquals("Sign-in expired or was refused. Sign in again.", failure.message)
    }

    @Test fun `the server's own explanation wins when it sends one`() {
        val failure = SyncResponses.failureFor(
            response(400, """{"error":"Send between 1 and 100 events per request."}"""),
            "/v1/events"
        )
        assertEquals(400, failure.status)
        assertEquals("Send between 1 and 100 events per request.", failure.message)
    }

    @Test fun `a nested error object and an error_description are both read`() {
        assertEquals(
            "invalid_grant",
            SyncResponses.failureFor(response(400, """{"error":"invalid_grant"}"""), "/v1/sessions").message
        )
        assertEquals(
            "Code expired",
            SyncResponses.failureFor(
                response(400, """{"error":"invalid_grant","error_description":"Code expired"}"""),
                "/v1/sessions"
            ).message
        )
        assertEquals(
            "Too big",
            SyncResponses.failureFor(
                response(413, """{"error":{"message":"Too big"}}"""), "/v1/events"
            ).message
        )
    }

    @Test fun `a status with no explanation still names itself`() {
        val failure = SyncResponses.failureFor(response(418), "/v1/events")
        assertEquals(418, failure.status)
        assertEquals("Sync server returned HTTP 418.", failure.message)
    }

    @Test fun `a gateway failure says the server could not answer`() {
        listOf(500, 502, 503, 504).forEach { code ->
            val message = SyncResponses.failureFor(response(code), "/v1/events").message!!
            assertTrue("status $code: $message", message.contains("HTTP $code"))
            assertTrue("status $code: $message", message.contains("Try again shortly"))
        }
    }

    // ----- the ambiguous cases, which have no status at all -----

    @Test fun `an unanswered registration offers both ways out`() {
        val message = SyncResponses.REGISTRATION_AMBIGUOUS_MESSAGE
        // It must not read as a credential failure: nothing was refused, nothing arrived.
        assertTrue(message, !message.contains("wrong password", ignoreCase = true))
        assertTrue(message, !message.contains("incorrect", ignoreCase = true))
        // Path one: sign in — the account may exist, and an empty account can still adopt a key.
        assertTrue(message, message.contains("Try signing in"))
        // Path two: another address.
        assertTrue(message, message.contains("different"))
        assertTrue(message, message.contains("email"))
        // And it says plainly that the key never arrived, which is the whole hazard.
        assertTrue(message, message.contains("recovery key never"))
        assertTrue(message, message.contains("may or may not have been created"))
    }

    @Test fun `an ordinary unreachable server is reported as unreachable`() {
        assertTrue(SyncResponses.UNREACHABLE_MESSAGE.contains("Could not reach the sync server"))
        assertTrue(SyncResponses.UNREACHABLE_MESSAGE.contains("HTTPS"))
    }
}
