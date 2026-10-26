package tech.egrie.soundtrail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the recovery key is protected while it is on screen, in the clipboard, and on the way back
 * out of storage.
 *
 * None of this can be exercised on a JVM: `FLAG_SECURE` is a window flag, `ClipDescription`
 * extras are a platform bundle, and `BiometricPrompt` needs a real authenticator. So these are
 * source-inspection tests in the same style as [tech.egrie.soundtrail.integrations.CredentialLoggingTest]
 * — lint with a failure attached. What they protect against is quiet removal: every one of these
 * lines is invisible in a functional test, the app works identically without it, and the only
 * difference is that a key which decrypts the whole synced library becomes screenshotable,
 * previewable in the clipboard, or readable by whoever picks the phone up.
 *
 * The rules being held to:
 *
 * 1. while the key is visible the window is `FLAG_SECURE`, and it stops being secure the moment the
 *    key is hidden *and* when the screen is left;
 * 2. the clipboard copy is marked sensitive on Android 13+, still works below it, and is cleared
 *    again — but only while it still holds this key;
 * 3. revealing a stored key again requires the device unlock, while the one shown at creation does
 *    not, because it was just generated on this device and has never been anywhere else.
 */
class RecoveryKeyProtectionTest {

    private val main = MainSources.read("MainActivity.kt")
    private val viewModel = MainSources.read("MainViewModel.kt")

    private fun body(source: String, declaration: String, what: String): String =
        MainSources.bodyOf(source, declaration)
            ?: throw AssertionError("could not find $what ($declaration)")

    // ----- 1. no screenshot, no recording, no thumbnail in recents -----

    @Test fun `the secure flag is keyed to the key being visible`() {
        // Keyed to the state, not to Unit: a DisposableEffect(Unit) would run once at composition
        // and never notice the key being hidden again.
        assertTrue(
            "the effect must re-run when the key's visibility changes",
            main.contains("DisposableEffect(recoveryKeyVisible)")
        )
        assertTrue(
            "visibility must be read from the state that holds the key",
            main.contains("val recoveryKeyVisible = state.syncRecoveryKey != null")
        )
    }

    @Test fun `the window is secure while the key is on screen`() {
        val effect = body(main, "DisposableEffect(recoveryKeyVisible)", "the secure-flag effect")
        assertTrue(
            "the flag must be set when the key is visible",
            effect.contains("if (recoveryKeyVisible)") &&
                effect.contains("window.setFlags(") &&
                effect.contains("WindowManager.LayoutParams.FLAG_SECURE")
        )
    }

    @Test fun `the window stops being secure when the key is hidden and when the screen is left`() {
        val effect = body(main, "DisposableEffect(recoveryKeyVisible)", "the secure-flag effect")
        // Twice: once for the key being hidden while the screen stays open, once on dispose. A
        // single clear would leave either the rest of the app un-screenshotable or the key's
        // thumbnail in recents after navigating away.
        val clears = Regex("window\\.clearFlags\\(WindowManager\\.LayoutParams\\.FLAG_SECURE\\)")
            .findAll(effect).count()
        assertEquals("the flag must be cleared on hide and on dispose", 2, clears)
        assertTrue(
            "one of those clears must be the dispose callback",
            effect.contains("onDispose {")
        )
    }

    // ----- 2. the clipboard copy -----

    @Test fun `the copy is marked sensitive where Android supports it`() {
        val copy = body(main, "private fun copyRecoveryKey(", "copyRecoveryKey")
        val guard = body(
            copy,
            "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)",
            "the Android 13 guard"
        )
        assertTrue(
            "the sensitive flag must be set on the clip's description",
            guard.contains("ClipDescription.EXTRA_IS_SENSITIVE") &&
                guard.contains("putBoolean(")
        )
        assertTrue(
            "a platform change must not take the copy down with it",
            guard.contains("runCatching")
        )
    }

    @Test fun `the copy still works below Android 13`() {
        // The flag is an enhancement, not a precondition: on Android 12 and earlier there is no
        // such extra, and refusing to copy would leave the listener with no way to move the key.
        val copy = body(main, "private fun copyRecoveryKey(", "copyRecoveryKey")
        val guard = body(
            copy,
            "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)",
            "the Android 13 guard"
        )
        assertFalse(
            "the copy itself must not be inside the version guard",
            guard.contains("setPrimaryClip")
        )
        assertTrue(
            "the clip must be put on the clipboard whatever the version",
            copy.contains("clipboard.setPrimaryClip(clip)")
        )
    }

    @Test fun `the clipboard is cleared again, and only while it still holds this key`() {
        val copy = body(main, "private fun copyRecoveryKey(", "copyRecoveryKey")
        assertTrue(
            "the clear must be delayed, so the listener has time to paste it somewhere",
            copy.contains("postDelayed(") && copy.contains("CLIPBOARD_CLEAR_AFTER_MS")
        )
        val delayed = body(copy, "window.decorView.postDelayed(", "the delayed clear")
        assertTrue(
            "the clear must compare against the key it copied",
            delayed.contains("current == key")
        )
        assertTrue(
            "and only then replace the clip, so it cannot wipe something copied afterwards",
            delayed.indexOf("current == key") < delayed.indexOf("setPrimaryClip")
        )
    }

    @Test fun `the listener is told the copy is temporary`() {
        val copy = body(main, "private fun copyRecoveryKey(", "copyRecoveryKey")
        assertTrue(
            "the message must say the key leaves the clipboard",
            copy.contains("it leaves the clipboard in a minute")
        )
    }

    // ----- 3. re-display requires the device unlock -----

    @Test fun `revealing a stored key again goes through the unlock`() {
        // Exactly one call site, and it is the gated one. A second, ungated call would make the
        // prompt decorative.
        val mentions = main.lines().filter { it.contains("showCloudRecoveryKey") }
        assertEquals("there must be one way back to a stored key", 1, mentions.size)
        assertTrue(
            "and it must be inside requireUnlock",
            mentions.single().contains("requireUnlock(")
        )
    }

    @Test fun `the key shown at creation needs no prompt`() {
        // It was generated on this device a moment ago and has never been anywhere else, so asking
        // for an unlock to read it back would be theatre. This is the one display that is ungated,
        // and it is the account-creation path.
        assertTrue(
            "creation must still show the key it just made",
            viewModel.contains("syncRecoveryKey = recoveryKey")
        )
        assertTrue(
            "and must tell the listener to save it before leaving this device",
            viewModel.contains("Save the recovery key shown on the Connect tab")
        )
    }

    @Test fun `the unlock accepts a strong biometric or the device credential`() {
        val unlock = body(main, "private fun requireUnlock(", "requireUnlock")
        assertTrue(
            "BIOMETRIC_STRONG, so a face-only device does not silently downgrade the gate",
            unlock.contains("BiometricManager.Authenticators.BIOMETRIC_STRONG")
        )
        assertTrue(
            "DEVICE_CREDENTIAL, so a phone with no enrolled biometric can still show the key",
            unlock.contains("BiometricManager.Authenticators.DEVICE_CREDENTIAL")
        )
        assertTrue(unlock.contains("prompt.authenticate("))
    }

    @Test fun `a device with no screen lock fails closed`() {
        val unlock = body(main, "private fun requireUnlock(", "requireUnlock")
        val refused = body(
            unlock,
            "if (BiometricManager.from(this).canAuthenticate(authenticators) !=",
            "the no-lock branch"
        )
        assertTrue(
            "the refusal must explain what to set up",
            refused.contains("has no screen lock") && refused.contains("model.notify(")
        )
        assertFalse(
            "and must not run the action anyway",
            refused.contains("action()")
        )
        assertTrue(
            "the function must return there rather than falling through to the prompt",
            refused.removeSuffix("}").trimEnd().endsWith("return")
        )
    }

    @Test fun `the prompt sets no negative button beside the device credential`() {
        // Combining setNegativeButtonText with DEVICE_CREDENTIAL throws at runtime, and the
        // credential prompt brings its own cancel. This is a crash, not a style question.
        val unlock = body(main, "private fun requireUnlock(", "requireUnlock")
        assertFalse(
            "setNegativeButtonText must not be used with DEVICE_CREDENTIAL",
            unlock.contains("setNegativeButtonText")
        )
    }

    @Test fun `backing out of the prompt is not reported as a failure`() {
        val unlock = body(main, "private fun requireUnlock(", "requireUnlock")
        listOf("ERROR_USER_CANCELED", "ERROR_NEGATIVE_BUTTON", "ERROR_CANCELED").forEach { code ->
            assertTrue(
                "$code is a decision, not an error the listener needs told about",
                unlock.contains(code)
            )
        }
        assertTrue(
            "a genuine failure is still reported",
            unlock.contains("Could not unlock this device")
        )
    }

    @Test fun `the unlock says what it is protecting`() {
        // A prompt that does not name the thing it gates reads as the app demanding a fingerprint
        // for no reason, which is how people learn to dismiss them.
        val unlock = body(main, "private fun requireUnlock(", "requireUnlock")
        assertTrue(unlock.contains("Confirm it is you to show \$what."))
        assertNotNull(main.lines().firstOrNull { it.contains("\"your saved recovery key\"") })
    }
}
