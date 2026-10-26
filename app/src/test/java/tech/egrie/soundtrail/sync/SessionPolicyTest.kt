package tech.egrie.soundtrail.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.egrie.soundtrail.MainSources

/**
 * The account-lifetime rules: when a session is worth renewing, what a password has to look like,
 * when a server that has fallen behind is a warning, and what the device list calls a row.
 *
 * These are the decisions that are easy to get subtly wrong and impossible to notice afterwards. A
 * renewal window that is too wide invalidates a working token on every launch; one that is too
 * narrow lets a session expire and turns into a sign-in prompt the listener did not expect. A
 * rollback test that treated "equal" as a rollback would warn after every ordinary sync. A password
 * rule stated once in the service and once in the form drifts, and the form is the copy the listener
 * reads.
 *
 * The last few tests read the shipped source rather than a returned value, because the guarantee
 * there is about wiring — that the form asks the policy, that the highest sequence is only ever
 * raised, that a session revoked by name still clears this device's credentials when it turns out
 * to be this device's. That is the same approach `BackupRulesTest` and `CredentialLoggingTest` take.
 */
class SessionPolicyTest {

    private val now = 1_800_000_000_000L
    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour

    // ----- renewal: at most once a day, and only when expiry is close (item 10) -----

    @Test fun `a session checked today is not checked again`() {
        // Renewal kills the token it replaces. Checking on every launch would invalidate a working
        // session for nothing, so the interval is what makes the check affordable.
        assertTrue(SessionPolicy.checkedRecently(now, now - hour))
        assertTrue(SessionPolicy.checkedRecently(now, now - 23 * hour))
    }

    @Test fun `a session checked more than a day ago is due for a check`() {
        assertFalse(SessionPolicy.checkedRecently(now, now - 25 * hour))
        assertFalse(SessionPolicy.checkedRecently(now, now - 3 * day))
    }

    @Test fun `a check that has never run is not recent`() {
        // lastCheckAt 0 is "never", and a device that has just signed in must not be told to wait
        // a day for its first check.
        assertFalse(SessionPolicy.checkedRecently(now, 0L))
    }

    @Test fun `the interval boundary is a day, not a day minus a millisecond`() {
        assertTrue(SessionPolicy.checkedRecently(SessionPolicy.RENEW_CHECK_INTERVAL_MILLIS - 1, 0L))
        assertFalse(SessionPolicy.checkedRecently(SessionPolicy.RENEW_CHECK_INTERVAL_MILLIS, 0L))
    }

    @Test fun `force ignores the interval`() {
        // A sync that has just failed on an expired session needs the answer now; waiting out the
        // day would leave the listener staring at a failure the app could already have resolved.
        assertFalse(SessionPolicy.checkedRecently(now, now, force = true))
        assertFalse(SessionPolicy.checkedRecently(now, now - hour, force = true))
    }

    @Test fun `a session expiring in more than a week is left alone`() {
        assertFalse(SessionPolicy.renewalDue(now, now + 8 * day))
        assertFalse(SessionPolicy.renewalDue(now, now + 30 * day))
    }

    @Test fun `a session expiring inside a week is renewed`() {
        assertTrue(SessionPolicy.renewalDue(now, now + 6 * day))
        assertTrue(SessionPolicy.renewalDue(now, now + hour))
    }

    @Test fun `exactly a week away still counts as close enough`() {
        assertTrue(SessionPolicy.renewalDue(now, now + SessionPolicy.RENEW_WITHIN_MILLIS))
        assertFalse(SessionPolicy.renewalDue(now, now + SessionPolicy.RENEW_WITHIN_MILLIS + 1))
    }

    @Test fun `an expiry the server never stated is treated as due`() {
        // The opposite reading — "no expiry recorded, so nothing to worry about" — would leave a
        // session to die on its own and be discovered by a failed sync.
        assertTrue(SessionPolicy.renewalDue(now, 0L))
        assertTrue(SessionPolicy.renewalDue(now, -1L))
    }

    @Test fun `an already expired session is due`() {
        assertTrue(SessionPolicy.renewalDue(now, now - 1L))
        assertTrue(SessionPolicy.renewalDue(now, now - 30 * day))
    }

    @Test fun `the interval and the window are the numbers the contract states`() {
        // A widened window is how a slow network gets "fixed" by renewing constantly, and each
        // renewal revokes the token it replaces.
        assertEquals(24L * 60L * 60L * 1000L, SessionPolicy.RENEW_CHECK_INTERVAL_MILLIS)
        assertEquals(7L * 24L * 60L * 60L * 1000L, SessionPolicy.RENEW_WITHIN_MILLIS)
    }

    // ----- the password rule, stated once (item 11) -----

    @Test fun `a password in range and different from the current one is accepted`() {
        assertNull(SessionPolicy.passwordProblem("correct-horse", "battery-staple-long"))
    }

    @Test fun `the shortest and longest acceptable passwords are accepted`() {
        assertNull(SessionPolicy.passwordProblem("x", "a".repeat(SessionPolicy.PASSWORD_MIN_LENGTH)))
        assertNull(SessionPolicy.passwordProblem("x", "a".repeat(SessionPolicy.PASSWORD_MAX_LENGTH)))
    }

    @Test fun `one character either side of the range is refused, with the range in the sentence`() {
        val expected = "Use a password of 10 to 128 characters."
        assertEquals(expected, SessionPolicy.passwordProblem("x", "a".repeat(9)))
        assertEquals(expected, SessionPolicy.passwordProblem("x", "a".repeat(129)))
    }

    @Test fun `an empty password is refused rather than treated as a special case`() {
        // The form decides not to complain at an untouched field; the rule itself does not have to
        // know about forms, and a blank password is simply out of range.
        assertEquals(
            "Use a password of 10 to 128 characters.",
            SessionPolicy.passwordProblem("correct-horse", "")
        )
    }

    @Test fun `a password identical to the current one is refused`() {
        assertEquals(
            "The new password has to differ from the current one.",
            SessionPolicy.passwordProblem("battery-staple-long", "battery-staple-long")
        )
    }

    @Test fun `the range the server enforces is the range this file states`() {
        assertEquals(10, SessionPolicy.PASSWORD_MIN_LENGTH)
        assertEquals(128, SessionPolicy.PASSWORD_MAX_LENGTH)
    }

    @Test fun `the form asks the policy instead of keeping its own copy of the rule`() {
        // Two statements of one rule is how a form ends up accepting what the server refuses. The
        // hardcoded bounds this replaces were `passwordNext.length in 10..128`.
        val ui = MainSources.read("ui/MusicScreens.kt")
        assertTrue(
            "the change-password form must ask SessionPolicy",
            ui.contains("SessionPolicy.passwordProblem(passwordCurrent, passwordNext)")
        )
        assertFalse(
            "the form must not restate the length bounds",
            ui.contains("passwordNext.length in 10..128") || ui.contains("passwordNext.length < 10")
        )
        assertTrue(
            "the field cap must come from the policy too",
            ui.contains("SessionPolicy.PASSWORD_MAX_LENGTH")
        )
    }

    @Test fun `the service asks the same policy before it sends anything`() {
        val service = MainSources.bodyOf(
            MainSources.read("sync/CloudSyncService.kt"),
            "suspend fun changePassword("
        ) ?: throw AssertionError("could not find changePassword")
        assertTrue(
            "changePassword must check the rule locally before the round trip",
            service.contains("SessionPolicy.passwordProblem(currentPassword, newPassword)")
        )
    }

    // ----- a server that has fallen behind is a warning (item 7) -----

    @Test fun `a server behind this device is a warning`() {
        assertTrue(SessionPolicy.rollbackIsWarning(reportedSequence = 5, highestSeen = 10))
        assertTrue(SessionPolicy.rollbackIsWarning(reportedSequence = 0, highestSeen = 1))
    }

    @Test fun `a sequence equal to the highest seen is ordinary, not a rollback`() {
        // Equal is what an unchanged library looks like. Treating it as a rollback would warn after
        // every sync that appended nothing.
        assertFalse(SessionPolicy.rollbackIsWarning(reportedSequence = 10, highestSeen = 10))
    }

    @Test fun `a higher sequence is progress`() {
        assertFalse(SessionPolicy.rollbackIsWarning(reportedSequence = 11, highestSeen = 10))
    }

    @Test fun `the first sequence ever seen is not a rollback`() {
        assertFalse(SessionPolicy.rollbackIsWarning(reportedSequence = 3, highestSeen = -1L))
        assertFalse(SessionPolicy.rollbackIsWarning(reportedSequence = 0, highestSeen = -1L))
    }

    @Test fun `a response carrying no sequence is evidence of nothing`() {
        assertFalse(SessionPolicy.rollbackIsWarning(reportedSequence = -1L, highestSeen = 10))
        assertFalse(SessionPolicy.rollbackIsWarning(reportedSequence = -1L, highestSeen = -1L))
    }

    @Test fun `the highest sequence is only ever raised, never lowered`() {
        // Lowering it would let one old response erase the evidence, and the next ordinary sync
        // would look like progress again.
        val body = MainSources.bodyOf(
            MainSources.read("sync/CloudSyncService.kt"),
            "private fun detectRollback("
        ) ?: throw AssertionError("could not find detectRollback")
        assertTrue(
            "detectRollback must ask the policy for the comparison",
            body.contains("SessionPolicy.rollbackIsWarning(reported, seen)")
        )
        assertTrue(
            "the stored highest sequence must only be written when it rises",
            body.contains("if (reported > seen)")
        )
        assertTrue(
            "and the warning must be decided before that write, not by it",
            body.indexOf("SessionPolicy.rollbackIsWarning") < body.indexOf("if (reported > seen)")
        )
    }

    @Test fun `the warning is worded as a warning and says the library is intact`() {
        val service = MainSources.read("sync/CloudSyncService.kt")
        assertTrue(
            "ROLLBACK_WARNING must say the local library survived",
            service.contains("Your library here is intact")
        )
        assertTrue(
            "ROLLBACK_WARNING must offer the way out rather than just reporting the problem",
            service.contains("re-push it to put the ") && service.contains("missing events back")
        )
    }

    // ----- the device list (item 9) -----

    private fun session(
        id: String = "abc123",
        createdAt: Long = 0L,
        expiresAt: Long = 0L,
        current: Boolean = false
    ) = SyncDeviceSession(id = id, createdAt = createdAt, expiresAt = expiresAt, current = current)

    @Test fun `the session the server calls current is named as this device`() {
        assertEquals("This device", session(current = true).label(isThisDevice = false))
    }

    @Test fun `the stored session id names this device even when the server does not`() {
        // Two sources of truth, either of which is enough: the server's `current` flag, and the id
        // this device stored when it signed in. A list that got this wrong would offer a Sign out
        // button beside the session the listener is standing on.
        assertEquals("This device", session(current = false).label(isThisDevice = true))
    }

    @Test fun `another device is named by when it signed in`() {
        val createdAt = now - 3 * day
        assertEquals(
            "Signed in ${formatTimestamp(createdAt)}",
            session(createdAt = createdAt).label(isThisDevice = false)
        )
    }

    @Test fun `no device name is invented`() {
        // The server has only ever seen a token, an IP and a time. A row that said "Pixel 7" would
        // be a guess dressed as a fact, so the label is the date and nothing else.
        val label = session(createdAt = now).label(isThisDevice = false)
        assertTrue(label.startsWith("Signed in "))
        assertEquals("Signed in ${formatTimestamp(now)}", label)
    }

    @Test fun `a session with no creation date is not given one`() {
        assertEquals("Another device", session(createdAt = 0L).label(isThisDevice = false))
        assertEquals("Another device", session(createdAt = -1L).label(isThisDevice = false))
    }

    @Test fun `this device wins over a missing date`() {
        assertEquals("This device", session(createdAt = 0L, current = true).label(isThisDevice = false))
        assertEquals("This device", session(createdAt = 0L).label(isThisDevice = true))
    }

    @Test fun `a session revoked by name still clears this device when it was this device's`() {
        // `DELETE /v1/sessions/<id>` answers `signedOut: true` when the id turned out to be the
        // caller's own. Handling that like any other row would leave a revoked token stored, and
        // every later request would fail with no idea why.
        val body = MainSources.bodyOf(
            MainSources.read("sync/CloudSyncService.kt"),
            "suspend fun signOutSession("
        ) ?: throw AssertionError("could not find signOutSession")
        assertTrue(
            "signOutSession must read the server's signedOut flag",
            body.contains("optBoolean(\"signedOut\"")
        )
        assertTrue(
            "and must clear this device's credentials when it is set",
            body.contains("forgetSession()")
        )
        assertTrue(
            "so the caller can tell the two outcomes apart",
            body.contains("SessionSignOut.SignedOutThisDevice") &&
                body.contains("SessionSignOut.RevokedOther")
        )
    }

    @Test fun `a session id that belongs to nobody is reported the same as one that belongs to someone else`() {
        // The server answers 404 for another account's id exactly as it does for an id that never
        // existed, so this cannot be used to learn whether a session exists elsewhere.
        val body = MainSources.bodyOf(
            MainSources.read("sync/CloudSyncService.kt"),
            "suspend fun signOutSession("
        ) ?: throw AssertionError("could not find signOutSession")
        assertTrue(
            "404 and 400 must produce one refusal, not two different claims",
            body.contains("e.status == 404 || e.status == 400")
        )
    }
}
