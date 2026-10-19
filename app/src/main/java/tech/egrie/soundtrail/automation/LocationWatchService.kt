package tech.egrie.soundtrail.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.egrie.soundtrail.MainActivity
import tech.egrie.soundtrail.R
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.PlaylistStore
import java.time.LocalTime

/**
 * Opt-in foreground service that watches location, motion, and time for automation
 * triggers. Android requires the persistent notification shown here, which keeps the
 * watch visible at all times. Coordinates never leave the device.
 */
class LocationWatchService : Service() {
    companion object {
        const val ACTION_START = "app.soundtrail.watch.START"
        const val ACTION_STOP = "app.soundtrail.watch.STOP"
        /** Re-reads the optional activity-recognition setting while the watch is already up. */
        const val ACTION_REFRESH_ACTIVITY = "app.soundtrail.watch.REFRESH_ACTIVITY"
        const val EXTRA_OPEN_LISTS = "open_lists"
        const val EXTRA_PLAY_PLAYLIST = "play_playlist"
        private const val CHANNEL_ID = "soundtrail_automation"
        private const val WATCH_NOTIFICATION_ID = 41
        private const val TICK_MS = 60_000L
        private const val LOCATION_INTERVAL_MS = 20_000L
        private const val LOCATION_DISTANCE_M = 25f

        private val _status = MutableStateFlow(WatchStatus())
        val status = _status.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LocationWatchService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationWatchService::class.java))
            _status.value = WatchStatus()
        }

        /**
         * Applies a changed opt-in immediately when the watch is running. When it is not, the
         * preference is simply read the next time the watch starts; no service is woken up.
         */
        fun refreshActivityRecognition(context: Context) {
            if (!_status.value.running) return
            runCatching {
                context.startService(
                    Intent(context, LocationWatchService::class.java)
                        .setAction(ACTION_REFRESH_ACTIVITY)
                )
            }
        }
    }

    private lateinit var rules: AutomationStore
    private lateinit var playlists: PlaylistStore
    private lateinit var activityStore: ActivityRecognitionStore
    private val handler = Handler(Looper.getMainLooper())
    private var lastFix: Location? = null
    private var activityRecognitionRegistered = false

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastFix = location
            evaluate()
        }

        // Explicitly implemented: older OS levels declare these abstract.
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        rules = AutomationStore(this)
        playlists = PlaylistStore(this)
        activityStore = ActivityRecognitionStore(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Playlist automations", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Triggers playlists from places, motion, and time" }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH_ACTIVITY) {
            syncActivityRecognition()
            return START_STICKY
        }
        startInForeground()
        registerUpdates()
        syncActivityRecognition()
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        unregisterUpdates()
        if (activityRecognitionRegistered) {
            GoogleActivityMotion.cancel(this)
            activityRecognitionRegistered = false
        }
        _status.value = WatchStatus()
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_soundtrail_notification)
            .setContentTitle("Soundtrail is watching")
            .setContentText("Playlist triggers for places, motion, and time are active.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openListsIntent())
            .build()
        ServiceCompat.startForeground(
            this, WATCH_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
    }

    private fun registerUpdates() {
        val manager = getSystemService(LocationManager::class.java)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(
                        provider, LOCATION_INTERVAL_MS, LOCATION_DISTANCE_M, listener, Looper.getMainLooper()
                    )
                }
            }
        }
        // Seed with the freshest known fix so triggers can fire before the next update.
        runCatching { lastFix = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lastFix }
    }

    private fun unregisterUpdates() {
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(listener) }
    }

    /**
     * Registers or unregisters Google's activity source to match the opt-in. Failure is not
     * fatal: [evaluate] keeps classifying GPS speed exactly as it always did.
     */
    private fun syncActivityRecognition() {
        val wanted = activityStore.enabled && GoogleActivityMotion.permissionGranted(this)
        when {
            wanted && !activityRecognitionRegistered ->
                activityRecognitionRegistered = GoogleActivityMotion.request(this)
            !wanted && activityRecognitionRegistered -> {
                GoogleActivityMotion.cancel(this)
                activityRecognitionRegistered = false
            }
        }
        if (!activityStore.enabled) activityStore.clear()
    }

    private fun evaluate() {
        val now = System.currentTimeMillis()
        val minuteOfDay = LocalTime.now().get(java.time.temporal.ChronoField.MINUTE_OF_DAY)
        val fix = lastFix
        val gpsMotion = MotionClassifier.classify(fix?.speed ?: Float.NaN)
        val recognized = if (activityStore.enabled) activityStore.last() else null
        val recognizedAge = recognized?.let { now - it.second } ?: Long.MAX_VALUE
        val motion = ActivityMotion.choose(gpsMotion, recognized?.first, recognizedAge)
        val source = if (recognized != null && motion == recognized.first &&
            recognizedAge in 0..ActivityMotion.FRESH_MS
        ) {
            MotionSource.ACTIVITY_RECOGNITION
        } else {
            MotionSource.GPS
        }
        val point = fix?.let { GeoPoint(it.latitude, it.longitude) }?.takeIf(GeoPoint::isValid)
        _status.value = WatchStatus(
            running = true,
            motion = motion,
            motionSource = source,
            location = point,
            minuteOfDay = minuteOfDay,
            updatedAt = now
        )

        val due = AutomationEngine.due(rules.all(), TriggerContext(motion, point, minuteOfDay), now, rules::lastFiredAt)
        val rule = due.firstOrNull() ?: return
        val playlist = playlists.find(rule.playlistId) ?: return
        rules.markFired(rule.id, now)
        notifyTrigger(rule, playlist.name)
    }

    private fun describe(rule: AutomationRule): String {
        val parts = buildList {
            rule.place?.let { add("at your saved spot") }
            rule.activity?.let { add(it.label.lowercase()) }
            if (rule.fromMinute != null && rule.toMinute != null) {
                add("between %02d:%02d and %02d:%02d".format(
                    rule.fromMinute!! / 60, rule.fromMinute!! % 60, rule.toMinute!! / 60, rule.toMinute!! % 60
                ))
            }
        }
        return parts.joinToString(" · ").ifBlank { "trigger matched" }
    }

    private fun notifyTrigger(rule: AutomationRule, playlistName: String) {
        val playIntent = PendingIntent.getActivity(
            this, rule.playlistId.hashCode(),
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_PLAY_PLAYLIST, rule.playlistId)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_soundtrail_notification)
            .setContentTitle(rule.name)
            .setContentText("▶ $playlistName — ${describe(rule)}")
            .setContentIntent(playIntent)
            .addAction(0, "Play playlist", playIntent)
            .addAction(0, "Open Soundtrail", openListsIntent())
            .setAutoCancel(true)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java).notify(rule.id.hashCode(), notification)
        }
    }

    private fun openListsIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_LISTS, true)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
