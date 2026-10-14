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
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.graphics.drawable.Icon
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tech.egrie.soundtrail.MainActivity
import tech.egrie.soundtrail.R

/**
 * Keeps Jellyfin streaming alive outside Soundtrail's own screens: a `mediaPlayback`
 * foreground service carrying a MediaStyle notification and the platform's [MediaSession],
 * so lockscreen controls, Bluetooth/headset buttons, and the notification's play/pause
 * and stop all reach the very same [JellyfinPlayer] instance the in-app bar drives —
 * there is exactly one player and one source of truth for its state.
 *
 * The deprecated-but-supported `android.media.MediaSession` is used on purpose instead of
 * pulling in a media library: Soundtrail plays one resolved item at a time (no queue, no
 * browse tree), so all that is needed is transport controls and a session token for the
 * notification. The service only mirrors the player's [JellyfinPlayer.state] flow; it
 * never fabricates playback and stops itself the moment the state clears.
 */
class PlaybackService : Service() {
    companion object {
        const val ACTION_TOGGLE = "tech.egrie.soundtrail.playback.TOGGLE"
        const val ACTION_STOP = "tech.egrie.soundtrail.playback.STOP"
        private const val CHANNEL_ID = "soundtrail_playback"
        private const val PLAYBACK_NOTIFICATION_ID = 42

        /** Set synchronously on start/stop so rapid stop-then-play cannot race the service lifecycle. */
        @Volatile
        private var running = false

        /** Brings the service up if it is not already running. Called when a stream begins or resumes. */
        fun ensureStarted(context: Context) {
            if (running) return
            running = true
            context.startForegroundService(Intent(context, PlaybackService::class.java))
        }
    }

    private lateinit var player: JellyfinPlayer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collecting = false

    /** The player ticks its position twice a second; the notification only changes on real events. */
    private var notifiedSignature: String? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Headphones unplugged: pause rather than blast audio from the speaker.
            if (player.state.value?.playing == true) player.toggle()
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        player = JellyfinPlayer.shared(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "In-app music playback" }
        )
        session = MediaSession(this, "Soundtrail playback").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (player.state.value?.playing != true) player.toggle()
                }

                override fun onPause() {
                    if (player.state.value?.playing == true) player.toggle()
                }

                override fun onStop() {
                    player.stop()
                }
            })
            isActive = true
        }
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
        startInForeground(player.state.value)
        when (intent?.action) {
            ACTION_TOGGLE -> player.toggle()
            ACTION_STOP -> player.stop()
            else -> Unit
        }
        if (!collecting) {
            collecting = true
            scope.launch {
                player.state.collect { state ->
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
        session?.isActive = false
        session?.release()
        session = null
        runCatching { unregisterReceiver(noisyReceiver) }
        super.onDestroy()
    }

    private fun startInForeground(state: NowPlaying?) {
        ServiceCompat.startForeground(
            this, PLAYBACK_NOTIFICATION_ID, buildNotification(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    /** Pushes the player's own state to the session and the notification. */
    private fun publish(state: NowPlaying) {
        val current = session ?: return
        current.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs.coerceAtLeast(0L))
                .build()
        )
        val status = when {
            state.error != null -> PlaybackState.STATE_ERROR
            state.playing -> PlaybackState.STATE_PLAYING
            state.buffering -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_PAUSED
        }
        current.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
                )
                .setState(status, state.positionMs.coerceAtLeast(0L), if (state.playing) 1f else 0f)
                .build()
        )
        val signature = listOf(
            state.playing, state.buffering, state.error, state.title, state.artist
        ).joinToString("|")
        if (signature != notifiedSignature) {
            notifiedSignature = signature
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(PLAYBACK_NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    private fun buildNotification(state: NowPlaying?): Notification {
        val playing = state?.playing == true
        val buffering = state?.buffering == true
        val subtitle = when (state?.provider) {
            tech.egrie.soundtrail.integrations.MusicProvider.SPOTIFY -> "Spotify preview"
            tech.egrie.soundtrail.integrations.MusicProvider.SOUNDCLOUD -> "SoundCloud"
            tech.egrie.soundtrail.integrations.MusicProvider.YOUTUBE_MUSIC -> "YouTube Music"
            tech.egrie.soundtrail.integrations.MusicProvider.YOUTUBE -> "YouTube"
            tech.egrie.soundtrail.integrations.MusicProvider.JELLYFIN -> "Jellyfin"
            null -> "Soundtrail"
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_soundtrail_notification)
            .setContentTitle(state?.title ?: "Soundtrail")
            .setContentText(
                when {
                    state == null -> "Nothing is playing"
                    state.error != null -> state.error
                    buffering -> "Buffering…"
                    playing -> state.artist.ifBlank { subtitle }
                    else -> "Paused" + state.artist.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
                }
            )
            .setContentIntent(openAppIntent())
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            // Only a live stream pins itself; paused or finished rows can be swiped away.
            .setOngoing(playing || buffering)
        if (state != null) {
            val toggleLabel = if (playing) "Pause" else "Play"
            val toggleIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, toggleIcon), toggleLabel,
                    serviceIntent(ACTION_TOGGLE, 10)
                ).build()
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop",
                    serviceIntent(ACTION_STOP, 11)
                ).build()
            )
            session?.let { current ->
                builder.setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(current.sessionToken)
                        .setShowActionsInCompactView(0, 1)
                )
            }
        }
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
