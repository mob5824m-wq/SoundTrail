package tech.egrie.soundtrail.mood

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke test for the consent record that [MoodStore] keeps.
 *
 * The rules themselves are unit-tested in MoodConsentRecordTest. What this adds is the wiring
 * the unit test cannot see: that the record is really written to the dedicated preferences file,
 * that it survives a new [MoodStore] instance, and that it is not mixed into the mood-settings
 * file — which is the property the Android backup rules depend on.
 */
@RunWith(AndroidJUnit4::class)
class MoodConsentStoreSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: MoodStore

    private val here = MoodAiDestination(MoodAiProvider.OLLAMA, "http://192.168.0.20:11434")
    private val there = MoodAiDestination(MoodAiProvider.OLLAMA, "http://192.168.0.21:11434")

    @Before
    fun startWithNoApproval() {
        store = MoodStore(context)
        store.forgetConsent()
    }

    @After
    fun withdrawAfterTheTest() {
        store.forgetConsent()
    }

    @Test
    fun nothingIsApprovedOnAFreshInstall() {
        assertFalse(store.hasConsent(here))
        assertNull(store.consentAt())
    }

    @Test
    fun approvalSurvivesANewStore() {
        store.rememberConsent(here)

        val reopened = MoodStore(context)

        assertTrue(reopened.hasConsent(here))
        assertNotNull(reopened.consentAt())
    }

    @Test
    fun anotherEndpointIsNotCoveredByTheApproval() {
        store.rememberConsent(here)

        assertFalse(store.hasConsent(there))
        assertTrue(store.hasConsent(here))
    }

    @Test
    fun withdrawingRemovesBothTheApprovalAndItsTime() {
        store.rememberConsent(here)

        store.forgetConsent()

        assertFalse(store.hasConsent(here))
        assertNull(store.consentAt())
    }

    @Test
    fun theApprovalIsNotStoredWithTheMoodSettings() {
        store.rememberConsent(here)

        // The backup rules exclude the consent file specifically; if this key moved into the
        // settings file it would travel with Android Auto Backup.
        val settings = context.getSharedPreferences("soundtrail_mood", Context.MODE_PRIVATE)
        assertFalse(settings.contains(MoodConsentRecord.KEY_FINGERPRINT))

        val consent = context.getSharedPreferences("soundtrail_mood_consent", Context.MODE_PRIVATE)
        assertEquals(here.fingerprint, consent.getString(MoodConsentRecord.KEY_FINGERPRINT, null))
    }

    @Test
    fun theTimestampIsRecordedInTheSameFile() {
        store.rememberConsent(here)

        val consent = context.getSharedPreferences("soundtrail_mood_consent", Context.MODE_PRIVATE)
        assertNotNull(consent.getString(MoodConsentRecord.KEY_APPROVED_AT, null))
        assertNotNull(store.consentAt())
    }
}
