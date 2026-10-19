package tech.egrie.soundtrail.automation

import com.google.android.gms.location.DetectedActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The optional Google source must never make GPS speed worse, and must map every activity. */
class ActivityMotionTest {
    @Test
    fun `every activity the app registers for maps to one motion kind`() {
        assertEquals(MotionKind.STATIONARY, ActivityMotion.kindOf(DetectedActivity.STILL))
        assertEquals(MotionKind.WALKING, ActivityMotion.kindOf(DetectedActivity.WALKING))
        assertEquals(MotionKind.WALKING, ActivityMotion.kindOf(DetectedActivity.ON_FOOT))
        assertEquals(MotionKind.RUNNING, ActivityMotion.kindOf(DetectedActivity.RUNNING))
        // Cycling shares the app's RUNNING band, exactly as the GPS speed classifier has it.
        assertEquals(MotionKind.RUNNING, ActivityMotion.kindOf(DetectedActivity.ON_BICYCLE))
        assertEquals(MotionKind.DRIVING, ActivityMotion.kindOf(DetectedActivity.IN_VEHICLE))
    }

    @Test
    fun `activities that carry no motion information are ignored`() {
        assertNull(ActivityMotion.kindOf(DetectedActivity.UNKNOWN))
        assertNull(ActivityMotion.kindOf(DetectedActivity.TILTING))
        assertNull(ActivityMotion.kindOf(Int.MAX_VALUE))
    }

    @Test
    fun `a fresh recognized activity wins over a speed threshold`() {
        assertEquals(
            MotionKind.WALKING,
            ActivityMotion.choose(MotionKind.STATIONARY, MotionKind.WALKING, 60_000)
        )
        assertEquals(
            MotionKind.RUNNING,
            ActivityMotion.choose(MotionKind.WALKING, MotionKind.RUNNING, 60_000)
        )
    }

    @Test
    fun `a stale recognized activity falls back to GPS speed`() {
        assertEquals(
            MotionKind.DRIVING,
            ActivityMotion.choose(
                MotionKind.DRIVING, MotionKind.WALKING, ActivityMotion.FRESH_MS + 1
            )
        )
        assertEquals(
            MotionKind.WALKING,
            ActivityMotion.choose(MotionKind.WALKING, MotionKind.STATIONARY, -5)
        )
    }

    @Test
    fun `gps speed overrules a recognized activity that contradicts real movement`() {
        // A missed transition must not hide the car.
        assertEquals(
            MotionKind.DRIVING,
            ActivityMotion.choose(MotionKind.DRIVING, MotionKind.WALKING, 1_000)
        )
        // A recognized "still" while the phone is visibly moving is stale.
        assertEquals(
            MotionKind.WALKING,
            ActivityMotion.choose(MotionKind.WALKING, MotionKind.STATIONARY, 1_000)
        )
    }

    @Test
    fun `without a recognized activity the result is exactly the gps reading`() {
        MotionKind.entries.forEach { kind ->
            assertEquals(
                kind,
                ActivityMotion.choose(kind, null, Long.MAX_VALUE)
            )
        }
    }
}
