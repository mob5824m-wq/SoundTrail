package tech.egrie.soundtrail.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tech.egrie.soundtrail.MainActivity
import tech.egrie.soundtrail.R

/**
 * Keeps playback alive outside Soundtrail's own screens: a `mediaPlayback` foreground service
 * carrying a MediaStyle notification.
 *
 * Everything it shows and everything it does comes from [PlaybackHub], which owns the app's
 * single player and the platform's single media session. That is what makes the notification
 * shade, the lockscreen, headset buttons, the app's own bar and Android Auto all act on one
 * stream: the service only mirrors what is playing, and forwards its own buttons back.
 */
class PlaybackService : Service() {
    companion object {
        const val ACTION_TOGGLE = "tech.egrie.soundtrail.playback.TOGGLE"
        const val ACTION_NEXT = "tech.egrie.soundtrail.playback.NEXT"
        const val ACTION_PREVIOUS = "tech.egrie.soundtrail.playback.PREVIOUS"
        const val ACTION_STOP = "tech.egrie.soundtrail.playback.STOP"
        private const val CHANNEL_ID = "soundtrail_playback"
        private const val PLAYBACK_NOTIFICATION_ID = 42

        /** Set synchronously on start/stop so rapid stop-then-play cannot race the lifecycle. */
        @Volatile
        private var running = false

        /**
         * Brings the service up if it is not already running. Called when a stream begins or
         * resumes — including from a car, where the app's own activity has never been opened.
         *
         * Android 12+ restricts starting a foreground service from the background. Playback
         * itself does not depend on this service, so a refusal is not fatal: the plain start
         * is tried (the service still posts its notification once the system lets it) and
         * playback continues through the app's player and its media session either way.
         */
        fun ensureStarted(context: Context) {
            if (running) return
            running = true
            val intent = Intent(context, PlaybackService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: IllegalStateException) {
                runCatching { context.startService(intent) }.onFailure { running = false }
            }
        }
    }

    private lateinit var hub: PlaybackHub
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collecting = false

    /** The player ticks its position twice a second; the notification only changes on real events. */
    private var notifiedSignature: String? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Headphones unplugged: pause rather than blast audio from the speaker.
            if (hub.player.state.value?.playing == true) hub.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        hub = PlaybackHub.shared(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "In-app music playback" }
        )
        runCatching {
            ContextCompat.registerReceiver(
                this, noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The foreground notification must appear before anything else can stop us.
        startInForeground(hub.player.state.value)
        when (intent?.action) {
            ACTION_TOGGLE -> hub.toggle()
            ACTION_NEXT -> hub.skipToNext()
            ACTION_PREVIOUS -> hub.skipToPrevious()
            ACTION_STOP -> hub.stopPlayback()
            else -> Unit
        }
        if (!collecting) {
            collecting = true
            scope.launch {
                hub.player.state.collect { state ->
                    if (state == null) stopEverything()
                    else publish(state)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        runCatching { unregisterReceiver(noisyReceiver) }
        super.onDestroy()
    }

    private fun startInForeground(state: NowPlaying?) {
        // A refused foreground start (see ensureStarted) must not take the service down: the
        // notification is a convenience, playback and the media session are not.
        runCatching {
            ServiceCompat.startForeground(
                this, PLAYBACK_NOTIFICATION_ID, buildNotification(state),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        }
    }

    /** Mirrors the player's own state onto the notification. */
    private fun publish(state: NowPlaying) {
        val signature = listOf(
            state.playing, state.buffering, state.error, state.title, state.artist,
            hub.canSkipNext(), hub.canSkipPrevious()
        ).joinToString("|")
        if (signature == notifiedSignature) return
        notifiedSignature = signature
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(PLAYBACK_NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun buildNotification(state: NowPlaying?): Notification {
        val playing = state?.playing == true
        val buffering = state?.buffering == true
        val source = state?.provider.sourceLabel()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_soundtrail_notification)
            .setContentTitle(state?.title ?: "Soundtrail")
            .setContentText(
                when {
                    state == null -> "Nothing is playing"
                    state.error != null -> state.error
                    buffering -> "Buffering…"
                    playing -> state.artist.ifBlank { source.orEmpty() }
                    else -> "Paused" + state.artist.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
                }
            )
            .setSubText(state?.let { source })
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // Only a live stream pins itself; paused or finished rows can be swiped away.
            .setOngoing(playing || buffering)

        val compact = mutableListOf<Int>()
        if (state != null) {
            val actions = mutableListOf<NotificationCompat.Action>()
            fun add(icon: Int, label: String, action: String, requestCode: Int, inCompact: Boolean) {
                actions += NotificationCompat.Action.Builder(
                    IconCompat.createWithResource(this, icon), label,
                    serviceIntent(action, requestCode)
                ).build()
                if (inCompact) compact += actions.lastIndex
            }
            if (hub.canSkipPrevious()) {
                add(R.drawable.ic_media_previous, "Previous", ACTION_PREVIOUS, 12, true)
            }
            add(
                if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                ACTION_TOGGLE, 10, true
            )
            if (hub.canSkipNext()) {
                add(R.drawable.ic_media_next, "Next", ACTION_NEXT, 13, true)
            }
            add(R.drawable.ic_media_stop, "Stop", ACTION_STOP, 11, false)
            actions.forEach { builder.addAction(it) }
        }
        val style = MediaNotificationCompat.MediaStyle().setMediaSession(hub.session.token)
        if (compact.isNotEmpty()) style.setShowActionsInCompactView(*compact.toIntArray())
        builder.setStyle(style)
        return builder.build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 2,
        Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun stopEverything() {
        running = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }
}
