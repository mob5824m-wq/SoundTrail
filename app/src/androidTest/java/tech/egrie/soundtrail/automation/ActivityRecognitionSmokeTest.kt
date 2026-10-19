package tech.egrie.soundtrail.automation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke test for the optional activity-recognition source.
 *
 * It covers the parts a JVM test cannot: the real SharedPreferences file, the runtime permission
 * check, and talking to whatever Play services implementation the device actually has. The two
 * tests that need Play services skip themselves with a reason rather than failing, so the suite is
 * still meaningful on an emulator image without Google APIs, or on a device running microG.
 *
 * Method names avoid spaces on purpose: instrumented test names become dex method names, and
 * spaces in those are not safe across every Android version this app supports.
 */
@RunWith(AndroidJUnit4::class)
class ActivityRecognitionSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val prefs
        get() = context.getSharedPreferences(
            "soundtrail_activity_recognition", Context.MODE_PRIVATE
        )

    @Before
    fun startFromNothing() {
        prefs.edit().clear().commit()
    }

    @After
    fun leaveNothingBehind() {
        prefs.edit().clear().commit()
        GoogleActivityMotion.cancel(context)
    }

    @Test
    fun optInIsOffOnAFreshInstall() {
        val store = ActivityRecognitionStore(context)

        assertFalse(store.enabled)
        assertNull(store.last())
    }

    @Test
    fun optInAndLastActivitySurviveANewStore() {
        ActivityRecognitionStore(context).apply {
            enabled = true
            record(MotionKind.DRIVING, atMs = 1_700_000_000_000)
        }

        val reopened = ActivityRecognitionStore(context)

        assertTrue(reopened.enabled)
        assertEquals(MotionKind.DRIVING to 1_700_000_000_000L, reopened.last())
    }

    @Test
    fun switchingTheSourceOffClearsTheStoredReading() {
        val store = ActivityRecognitionStore(context)
        store.enabled = true
        store.record(MotionKind.WALKING)

        // What the watch does when the listener opts out.
        store.enabled = false
        store.clear()

        assertFalse(store.enabled)
        assertNull(store.last())
    }

    @Test
    fun anActivityNameThisVersionDoesNotKnowIsIgnored() {
        // A value from a newer build, or a hand-edited file: it must not become a motion reading.
        prefs.edit().putString("recognized_motion", "HOVERING").putLong("recognized_at", 1L).commit()

        assertNull(ActivityRecognitionStore(context).last())
    }

    @Test
    fun aTimestampWithoutAnActivityIsNotAReading() {
        prefs.edit().putLong("recognized_at", 1L).commit()

        assertNull(ActivityRecognitionStore(context).last())
    }

    @Test
    fun playServicesAvailabilityIsAnsweredWithoutThrowing() {
        // Whatever the answer, asking twice must give the same answer and must not crash a
        // device that has no Google Play services at all.
        val available = GoogleActivityMotion.available(context)

        assertEquals(available, GoogleActivityMotion.available(context))
    }

    @Test
    fun thePermissionHelperAgreesWithThePlatform() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Before Android 10 this was not a runtime permission the app requests.
            assertTrue(GoogleActivityMotion.permissionGranted(context))
        } else {
            assertEquals(granted, GoogleActivityMotion.permissionGranted(context))
        }
    }

    @Test
    fun playServicesIsOnlyRegisteredWhenItCanBeUsed() {
        assumeTrue(
            "No Google Play services on this device — the app falls back to GPS speed, so this " +
                "path is expected to be unavailable.",
            GoogleActivityMotion.available(context)
        )
        assumeTrue(
            "ACTIVITY_RECOGNITION is not granted to the test app, so registration is refused " +
                "by design.",
            grantActivityRecognition()
        )

        // Both calls must be accepted without throwing. Play services completes the request
        // asynchronously, so what is asserted here is the smoke-test part: the hand-off works.
        assertTrue(GoogleActivityMotion.request(context))
        GoogleActivityMotion.cancel(context)
    }

    @Test
    fun theReceiverIgnoresAnIntentThatCarriesNoTransition() {
        // Play services delivers results as an extra on this intent; anything else is ignored.
        val receiver = ActivityTransitionReceiver()
        receiver.onReceive(context, Intent("tech.egrie.soundtrail.TEST"))
        receiver.onReceive(
            context,
            Intent("tech.egrie.soundtrail.TEST").putExtra("activity", "still")
        )

        assertNull(ActivityRecognitionStore(context).last())
    }

    /** Grants the runtime permission through the instrumentation, when the platform needs it. */
    private fun grantActivityRecognition(): Boolean {
        if (GoogleActivityMotion.permissionGranted(context)) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.ACTIVITY_RECOGNITION
            )
        }.isSuccess && GoogleActivityMotion.permissionGranted(context)
    }
}
