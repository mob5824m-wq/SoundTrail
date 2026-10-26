package tech.egrie.soundtrail.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tech.egrie.soundtrail.MainSources

/**
 * No credential material reaches a log, and nothing starts logging by accident.
 *
 * This app currently has **no logging at all** — no `Log.*`, no `println`, no stack traces to
 * stderr. That is the strongest form of this guarantee and it is cheap to keep, so the first test
 * below asserts it wholesale: the audit is not "these five places do not log a token", it is
 * "nothing anywhere writes anything to a log".
 *
 * The rest of the file exists for the day someone adds a diagnostic line, which is the only
 * realistic way this regresses: a debug build that prints a request, and an `Authorization` header
 * in it. `logcat` is readable by any process with the right permission on older devices, survives
 * in bug reports, and a bearer token in it is a credential leak with no expiry the listener can
 * see. So the rules are asserted here, and [redactAuthorization] is the safe default to reach for.
 */
class CredentialLoggingTest {

    private val loggingCalls = listOf(
        "Log.v(", "Log.d(", "Log.i(", "Log.w(", "Log.e(", "Log.wtf(",
        "println(", "printStackTrace(", "System.out", "System.err"
    )

    /** Identifiers that hold, or are built from, something secret. */
    private val credentialNames = listOf(
        "authorization", "token", "accessToken", "password", "currentPassword", "newPassword",
        "recoveryKey", "encodedKey", "clientSecret", "apiKey", "Authorization"
    )

    @Test fun `nothing in the app logs anything at all`() {
        val offenders = mutableListOf<String>()
        MainSources.kotlinFiles().forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val code = line.substringBefore("//")
                loggingCalls.forEach { call ->
                    if (code.contains(call)) {
                        offenders += "${file.name}:${index + 1} $call"
                    }
                }
            }
        }
        assertTrue(
            "This app keeps no logs, which is how no credential can ever be logged. If a " +
                "diagnostic is genuinely needed, guard it with BuildConfig.DEBUG, redact every " +
                "credential with redactAuthorization(), and update this test deliberately: " +
                offenders,
            offenders.isEmpty()
        )
    }

    @Test fun `no logging call and a credential name share a line`() {
        // Redundant with the test above today, and the one that still has teeth the day a guarded
        // debug line is added on purpose: whatever else is allowed, a credential may not be in it.
        MainSources.kotlinFiles().forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val logs = loggingCalls.any { line.contains(it) }
                val credential = credentialNames.any { line.contains(it) }
                assertFalse(
                    "${file.name}:${index + 1} logs something credential-bearing: $line",
                    logs && credential
                )
            }
        }
    }

    @Test fun `httpRequest sends its authorization argument to the header and nowhere else`() {
        val source = MainSources.read("integrations/Http.kt")
        val body = MainSources.bodyOf(source, "internal suspend fun httpRequest(")
            ?: fail("could not find httpRequest in Http.kt") as Nothing

        val uses = body.lines()
            .map { it.trim() }
            .filter { it.contains("authorization") && !it.startsWith("*") && !it.startsWith("//") }

        assertTrue("httpRequest should still use its authorization argument", uses.isNotEmpty())
        uses.forEach { line ->
            assertTrue(
                "the authorization argument may only be set as a request header, but found: $line",
                line.contains("setRequestProperty(\"Authorization\"")
            )
        }
    }

    @Test fun `a request body is never assembled into a diagnostic string`() {
        val body = MainSources.bodyOf(
            MainSources.read("integrations/Http.kt"), "internal suspend fun httpRequest("
        ) ?: return
        // The body is written to the output stream and read back as a response; it is never
        // concatenated into anything else, which is what would put a password or a payload in a
        // log or a crash report.
        val writes = body.lines().filter { it.contains("body") && it.contains("toByteArray") }
        assertEquals(1, writes.size)
        assertFalse(body.contains("\"\"\""))
    }

    // ----- the safe default, tested as a function rather than as a hope -----

    @Test fun `a bearer token is reduced to its scheme`() {
        assertEquals("Bearer ***", redactAuthorization("Bearer 8a1f-token-that-must-not-leak"))
    }

    @Test fun `jellyfin's own header shape is reduced to its scheme`() {
        val header = "MediaBrowser Client=\"Soundtrail\", Device=\"Android\", " +
            "DeviceId=\"abc\", Version=\"1.0.0\", Token=\"secret\""
        val redacted = redactAuthorization(header)
        assertEquals("MediaBrowser ***", redacted)
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("abc"))
    }

    @Test fun `a bare credential with no scheme leaks nothing, not even its kind`() {
        assertEquals("***", redactAuthorization("sk-live-51H8xampleKey"))
        assertFalse(redactAuthorization("sk-live-51H8xampleKey").contains("51H8"))
    }

    @Test fun `an absent credential is said to be absent, not printed as empty`() {
        assertEquals("(none)", redactAuthorization(null))
        assertEquals("(none)", redactAuthorization(""))
        assertEquals("(none)", redactAuthorization("   "))
    }

    @Test fun `redaction never returns the value it was given`() {
        listOf(
            "Bearer abc", "abc", "MediaBrowser Token=\"x\"", "sk-123", "Bearer", " "
        ).forEach { value ->
            val redacted = redactAuthorization(value)
            assertFalse("redacting \"$value\" gave \"$redacted\"", redacted == value.trim())
            assertFalse("redacting \"$value\" leaked it", redacted.contains(value.trim()) && value.trim().length > 3)
        }
    }
}
