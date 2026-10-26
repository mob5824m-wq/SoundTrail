package tech.egrie.soundtrail.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tech.egrie.soundtrail.MainSources

/**
 * The gate between an account response and the credential store.
 *
 * The rule is that **nothing is persisted until the response has been proved to carry an account
 * id and a token**. A half-written sign-in is worse than a failed one: it leaves the app reporting
 * itself as connected while holding no usable token, and every later sync fails in a way that
 * looks like a server problem rather than a sign-in that never completed.
 *
 * `acceptAccount` does nothing until [SyncAccountResponse.parse] returns non-null, so this test is
 * the lock on that ordering.
 */
class SyncAccountResponseTest {

    private val complete = JSONObject()
        .put("accountId", "11111111-2222-3333-4444-555555555555")
        .put("email", "walker@example.net")
        .put("token", "a-session-token")
        .put("sessionId", "session-1")
        .put("expiresAt", 1_800_000_000L)

    @Test fun `a complete response is accepted whole`() {
        val account = SyncAccountResponse.parse(complete, "typed@example.net")
        assertNotNull(account)
        assertEquals("11111111-2222-3333-4444-555555555555", account!!.accountId)
        assertEquals("walker@example.net", account.email)
        assertEquals("a-session-token", account.token)
        assertEquals("session-1", account.sessionId)
        assertEquals(1_800_000_000L, account.expiresAt)
    }

    @Test fun `no response at all is refused`() {
        assertNull(SyncAccountResponse.parse(null, "typed@example.net"))
    }

    @Test fun `an empty response is refused`() {
        assertNull(SyncAccountResponse.parse(JSONObject(), "typed@example.net"))
    }

    @Test fun `a token with no account id is refused`() {
        assertNull(SyncAccountResponse.parse(JSONObject().put("token", "t"), "typed@example.net"))
        assertNull(
            SyncAccountResponse.parse(
                JSONObject().put("accountId", "").put("token", "t"), "typed@example.net"
            )
        )
        assertNull(
            SyncAccountResponse.parse(
                JSONObject().put("accountId", "   ").put("token", "t"), "typed@example.net"
            )
        )
    }

    @Test fun `an account id with no token is refused`() {
        // This is the case that matters most: an account may now exist on the server while this
        // device holds nothing that can speak for it. Storing the id anyway would make the app
        // claim a connection it cannot use.
        assertNull(
            SyncAccountResponse.parse(JSONObject().put("accountId", "abc-123"), "typed@example.net")
        )
        assertNull(
            SyncAccountResponse.parse(
                JSONObject().put("accountId", "abc-123").put("token", ""), "typed@example.net"
            )
        )
        assertNull(
            SyncAccountResponse.parse(
                JSONObject().put("accountId", "abc-123").put("token", "   "), "typed@example.net"
            )
        )
    }

    @Test fun `the typed email is the fallback when the server omits its own copy`() {
        val account = SyncAccountResponse.parse(
            JSONObject().put("accountId", "abc-123").put("token", "t"),
            "typed@example.net"
        )
        assertNotNull(account)
        assertEquals("typed@example.net", account!!.email)
    }

    @Test fun `the server's own email wins over the typed one`() {
        // The server lower-cases and trims; its copy is the one the account is keyed by.
        val account = SyncAccountResponse.parse(
            JSONObject().put("accountId", "a").put("token", "t").put("email", "normalized@example.net"),
            "  Typed@Example.NET "
        )
        assertEquals("normalized@example.net", account!!.email)
    }

    @Test fun `a session id is optional, and blank means absent`() {
        assertNull(
            SyncAccountResponse.parse(
                JSONObject().put("accountId", "a").put("token", "t"), "e@example.net"
            )!!.sessionId
        )
        assertNull(
            SyncAccountResponse.parse(
                JSONObject().put("accountId", "a").put("token", "t").put("sessionId", "  "),
                "e@example.net"
            )!!.sessionId
        )
        assertEquals(
            "s-1",
            SyncAccountResponse.parse(
                JSONObject().put("accountId", "a").put("token", "t").put("sessionId", " s-1 "),
                "e@example.net"
            )!!.sessionId
        )
    }

    @Test fun `an unusable expiry reads as unknown rather than as expired in 1970`() {
        // 0 means "the server did not say", and the caller treats that as unknown — which is why a
        // negative or absent value must not survive as a timestamp.
        assertEquals(
            0L,
            SyncAccountResponse.parse(
                JSONObject().put("accountId", "a").put("token", "t"), "e@example.net"
            )!!.expiresAt
        )
        assertEquals(
            0L,
            SyncAccountResponse.parse(
                JSONObject().put("accountId", "a").put("token", "t").put("expiresAt", -5L),
                "e@example.net"
            )!!.expiresAt
        )
    }

    @Test fun `a non-string token is refused rather than coerced`() {
        assertNull(
            SyncAccountResponse.parse(
                JSONObject().put("accountId", "a").put("token", JSONObject()), "e@example.net"
            )
        )
    }

    // ----- and the ordering the tests above only imply -----

    @Test fun `nothing is written until the response has been validated`() {
        // Refusing a bad response is half the requirement; leaving nothing behind is the other
        // half. A sign-in that wrote the owner id and then failed on the token would report itself
        // as connected while holding nothing usable, and every later sync would look like a server
        // problem. parse() is the first statement in acceptAccount() and every write is below it,
        // so a response that does not parse cannot have touched the stores on its way out.
        val body = MainSources.bodyOf(
            MainSources.read("sync/CloudSyncService.kt"),
            "private fun acceptAccount("
        ) ?: fail("could not find acceptAccount") as Nothing
        val gate = body.indexOf("SyncAccountResponse.parse(response, email)")
        assertTrue("acceptAccount must gate on the parsed response", gate >= 0)
        listOf(
            "settings.edit()" to "the account identity",
            "storeSession(" to "the token and session id",
            "secrets.put(" to "the recovery key",
            "journal.record(" to "the key-check event"
        ).forEach { (write, what) ->
            val at = body.indexOf(write)
            assertTrue("acceptAccount must still write $what", at >= 0)
            assertTrue("$what must be written only after the response has been validated", at > gate)
        }
        assertTrue(
            "and a refused response must throw rather than return quietly",
            body.contains("?: throw CloudSyncException(\"The sync server returned an incomplete sign-in.\")")
        )
    }
}
