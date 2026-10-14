package tech.egrie.soundtrail.auto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoClientPolicyTest {
    private val own = "tech.egrie.soundtrail"
    private val nothingPrivileged: (String) -> Boolean = { false }

    @Test fun `the app itself may browse its own library`() {
        assertTrue(AutoClientPolicy.isTrusted(listOf(own), own, nothingPrivileged))
    }

    @Test fun `a car, the assistant and the system's media UI are allowed`() {
        assertTrue(AutoClientPolicy.isTrusted(listOf("com.google.android.projection.gearhead"), own, nothingPrivileged))
        assertTrue(AutoClientPolicy.isTrusted(listOf("com.google.android.googlequicksearchbox"), own, nothingPrivileged))
        assertTrue(AutoClientPolicy.isTrusted(listOf("com.android.systemui"), own, nothingPrivileged))
        assertTrue(AutoClientPolicy.isTrusted(listOf("com.android.car.media"), own, nothingPrivileged))
    }

    @Test fun `anything else on the phone is refused`() {
        assertFalse(AutoClientPolicy.isTrusted(listOf("com.example.someotherplayer"), own, nothingPrivileged))
        assertFalse(AutoClientPolicy.isTrusted(emptyList(), own, nothingPrivileged))
        // A shared uid with one unknown package is still unknown.
        assertFalse(AutoClientPolicy.isTrusted(listOf("com.example.tracker"), own, nothingPrivileged))
    }

    @Test fun `a caller the platform already trusts with media control is allowed`() {
        val privileged: (String) -> Boolean = { it == "com.example.trusted" }
        assertTrue(AutoClientPolicy.isTrusted(listOf("com.example.trusted"), own, privileged))
        // ...and the check is per package, so a uid that mixes a trusted app with an untrusted
        // one still passes on the strength of the trusted one it shares a uid with.
        assertTrue(AutoClientPolicy.isTrusted(listOf("com.example.tracker", "com.example.trusted"), own, privileged))
    }
}
