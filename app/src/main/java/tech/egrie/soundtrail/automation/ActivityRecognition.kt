package tech.egrie.soundtrail.automation

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Turns Google's detected activities into the app's own motion vocabulary.
 *
 * Kept pure so the mapping and the choice between the two sources can be tested without a
 * phone, Play services, or a location fix.
 */
internal object ActivityMotion {
    /** How long a Transition API event is trusted before GPS speed takes over again. */
    const val FRESH_MS = 15 * 60_000L

    /**
     * `STILL` is stationary; walking and being generally on foot are walking; running **and
     * cycling** share the app's RUNNING band because that is how [MotionClassifier] already reads
     * GPS speed (2.6–7.5 m/s covers cycling too); a vehicle is driving. Anything else — tilting,
     * unknown, a future activity type — adds no information and returns null.
     */
    fun kindOf(type: Int): MotionKind? = when (type) {
        DetectedActivity.STILL -> MotionKind.STATIONARY
        DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> MotionKind.WALKING
        DetectedActivity.RUNNING, DetectedActivity.ON_BICYCLE -> MotionKind.RUNNING
        DetectedActivity.IN_VEHICLE -> MotionKind.DRIVING
        else -> null
    }

    /**
     * Picks the reading the automation engine should see.
     *
     * A recent activity transition is better than a speed threshold at telling walking from
     * running and walking from cycling, so it wins while it is fresh. Two cases are the other
     * way round on purpose: GPS is immediate and hard to argue with when it reports driving (a
     * missed transition must not make the car invisible), and a recognized "still" that the
     * phone is visibly moving away from is stale. With no recognized activity — the switch is
     * off, Play services refused, or nothing has arrived yet — this is exactly GPS speed.
     */
    fun choose(gps: MotionKind, recognized: MotionKind?, ageMs: Long): MotionKind {
        if (recognized == null || ageMs !in 0..FRESH_MS) return gps
        if (gps == MotionKind.DRIVING && recognized != MotionKind.DRIVING) return gps
        if (recognized == MotionKind.STATIONARY && gps != MotionKind.STATIONARY) return gps
        return recognized
    }
}

/**
 * Persists whether the optional source is on, and the last activity Google reported.
 *
 * This deliberately lives in its own preferences file rather than beside the automation rules:
 * [AutomationStore]'s file is included in Android Auto Backup, and an opt-in to a motion sensor
 * should be given again on a new device rather than restored along with the library. Only the
 * last activity type and its timestamp are kept — never a raw sensor sample, and never a
 * location.
 */
internal class ActivityRecognitionStore(context: Context) {
    private val prefs =
        context.getSharedPreferences("soundtrail_activity_recognition", Context.MODE_PRIVATE)

    /** Off unless the listener opts in on the Lists tab. */
    var enabled: Boolean
        get() = prefs.getBoolean("activity_recognition", false)
        set(value) = prefs.edit().putBoolean("activity_recognition", value).apply()

    fun record(kind: MotionKind, atMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString("recognized_motion", kind.name)
            .putLong("recognized_at", atMs)
            .apply()
    }

    /** The last recognized activity and when it arrived, or null when there is none. */
    fun last(): Pair<MotionKind, Long>? {
        val name = prefs.getString("recognized_motion", null) ?: return null
        val kind = MotionKind.entries.firstOrNull { it.name == name } ?: return null
        val at = prefs.getLong("recognized_at", -1L)
        if (at < 0) return null
        return kind to at
    }

    fun clear() {
        prefs.edit().remove("recognized_motion").remove("recognized_at").apply()
    }
}

/**
 * The optional Google Play services Activity Recognition source.
 *
 * Soundtrail works without Play services and uses GPS speed unless this is explicitly switched
 * on and the runtime permission is granted. Every call is guarded, so a phone with microG, no
 * Google services, or a revoked permission simply keeps using [MotionClassifier].
 */
internal object GoogleActivityMotion {
    private const val REQUEST_CODE = 7301
    private const val ACTION_TRANSITION = "tech.egrie.soundtrail.action.ACTIVITY_TRANSITION"

    /** True when a Google-compatible Play services implementation is usable right now. */
    fun available(context: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS
    }.getOrDefault(false)

    /** Android 10+ made activity recognition a runtime permission; older releases did not. */
    fun permissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Registers for enter-transitions of the five activities this app understands. Returns
     * false when the source cannot even be asked; an asynchronous refusal from Play services is
     * left to the next tick, which falls back to GPS speed without a sound.
     */
    fun request(context: Context): Boolean {
        if (!available(context) || !permissionGranted(context)) return false
        return runCatching {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(transitions(), pendingIntent(context))
            true
        }.getOrDefault(false)
    }

    fun cancel(context: Context) {
        if (!available(context)) return
        runCatching {
            ActivityRecognition.getClient(context)
                .removeActivityTransitionUpdates(pendingIntent(context))
        }
    }

    /** Enter events are enough: a change of activity always enters the new one. */
    private fun transitions(): ActivityTransitionRequest {
        val types = listOf(
            DetectedActivity.STILL,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE
        )
        val events = types.map { type ->
            ActivityTransition.Builder()
                .setActivityType(type)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        }
        return ActivityTransitionRequest(events)
    }

    /**
     * Play services fills the transition result into this intent before sending it, so the
     * PendingIntent must be mutable on Android 12+. It wraps an explicit component, so no other
     * app can redirect it; the mutable flag is what lets the system attach the extras at all.
     */
    private fun pendingIntent(context: Context): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ActivityTransitionReceiver::class.java).setAction(ACTION_TRANSITION),
            flags
        )
    }
}

/**
 * Receives transition events from Play services and records the newest one.
 *
 * The receiver is not exported: Play services delivers the intent to this app's explicit
 * component, and nothing else should be able to write a motion reading into the store.
 */
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val entered = result.transitionEvents
            .lastOrNull { event ->
                event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER &&
                    ActivityMotion.kindOf(event.activityType) != null
            } ?: return
        val kind = ActivityMotion.kindOf(entered.activityType) ?: return
        // A transition already queued when the listener switched the source off must not put a
        // reading back into the store they just cleared.
        val store = ActivityRecognitionStore(context)
        if (!store.enabled) return
        store.record(kind)
    }
}
