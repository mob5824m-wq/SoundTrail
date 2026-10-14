package tech.egrie.soundtrail.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/**
 * What the platform shows and controls: one [MediaSessionCompat] for the whole app.
 *
 * Lockscreens, headset buttons, Bluetooth, the system's media controls and Android Auto all
 * drive this single session, and all of them end up here as calls on [SessionTransport] —
 * the app's own player, never a second one. Nothing about the app's screens is exposed
 * beyond the song that is playing and, while a queue exists, the songs around it.
 *
 * The session is created once per process and lives with the app's single player, so the car
 * can keep controlling playback while the app's own activity is gone. It is deliberately
 * created on the main thread: the compat session installs a handler when it is built.
 */
internal class NowPlayingSession(context: Context, private val transport: SessionTransport) {

    private val appContext = context.applicationContext
    private val appName: String = runCatching {
        appContext.packageManager.getApplicationLabel(appContext.applicationInfo).toString()
    }.getOrDefault("Soundtrail")

    /** Everything a controller asks for, translated into the app's own vocabulary. */
    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = transport.play()

        override fun onPause() = transport.pause()

        override fun onStop() = transport.stop()

        override fun onSkipToNext() = transport.skipToNext()

        override fun onSkipToPrevious() = transport.skipToPrevious()

        override fun onSeekTo(pos: Long) = transport.seekTo(pos)

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            mediaId?.let(transport::playFromMediaId)
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) =
            transport.playFromSearch(query.orEmpty())
    }

    private val session: MediaSessionCompat = MediaSessionCompat(
        appContext,
        "Soundtrail",
        ComponentName(appContext, PlaybackService::class.java),
        mediaButtonIntent()
    ).apply {
        // Media buttons must still reach playback when the app's screens are gone and the
        // process has been rebuilt: the same toggle the notification's own action uses.
        setMediaButtonReceiver(mediaButtonIntent())
        setSessionActivity(openAppIntent())
        setCallback(callback)
    }

    /** The token Android Auto (and any other controller) connects through. */
    val token: MediaSessionCompat.Token get() = session.sessionToken

    /**
     * Pushes one moment of the app's playback to the platform: the song, whether it is
     * playing, and the queue it sits in (which is what gives the car its next/previous
     * buttons and highlights the current row).
     */
    fun publish(state: NowPlaying?, queue: QueueState) {
        val entry = queue.current
        val active = state != null || queue.entries.isNotEmpty()
        if (session.isActive != active) session.setActive(active)
        session.setMetadata(metadata(state, entry))
        session.setPlaybackState(playbackState(state, queue))
        session.setQueue(
            queue.entries.mapIndexed { position, queued ->
                MediaSessionCompat.QueueItem(describe(queued), position.toLong())
            }.ifEmpty { null }
        )
    }

    private fun metadata(state: NowPlaying?, entry: QueueEntry?): MediaMetadataCompat {
        val title = state?.title ?: entry?.title ?: appName
        val artist = state?.artist ?: entry?.artist.orEmpty()
        val source = (state?.provider ?: entry?.provider).sourceLabel()
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, source)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, source)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state?.durationMs?.coerceAtLeast(0L) ?: 0L)
        (state?.itemId ?: entry?.mediaId)?.let { mediaId ->
            builder.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
        }
        return builder.build()
    }

    private fun playbackState(state: NowPlaying?, queue: QueueState): PlaybackStateCompat {
        val builder = PlaybackStateCompat.Builder().setActions(actions(queue))
        val position = state?.positionMs?.coerceAtLeast(0L) ?: 0L
        when {
            state == null -> builder.setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f)
            state.error != null -> builder
                .setState(PlaybackStateCompat.STATE_ERROR, position, 0f)
                .setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, state.error)
            state.buffering -> builder.setState(PlaybackStateCompat.STATE_BUFFERING, position, 0f)
            state.playing -> builder.setState(PlaybackStateCompat.STATE_PLAYING, position, 1f)
            else -> builder.setState(PlaybackStateCompat.STATE_PAUSED, position, 0f)
        }
        // The active row is how the car knows which song in the open list is the one playing.
        if (queue.index in queue.entries.indices) {
            builder.setActiveQueueItemId(queue.index.toLong())
        }
        return builder.build()
    }

    private fun actions(queue: QueueState): Long = PlaybackStateCompat.ACTION_PLAY or
        PlaybackStateCompat.ACTION_PAUSE or
        PlaybackStateCompat.ACTION_PLAY_PAUSE or
        PlaybackStateCompat.ACTION_STOP or
        PlaybackStateCompat.ACTION_SEEK_TO or
        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
        PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
        (if (queue.index < queue.entries.lastIndex) PlaybackStateCompat.ACTION_SKIP_TO_NEXT else 0L) or
        (if (queue.index > 0) PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS else 0L)

    private fun describe(entry: QueueEntry): MediaDescriptionCompat =
        MediaDescriptionCompat.Builder()
            .setMediaId(entry.mediaId)
            .setTitle(entry.title)
            .setSubtitle(entry.subtitle)
            .setDescription(entry.detail.takeIf(String::isNotBlank))
            .build()

    private fun mediaButtonIntent(): PendingIntent = PendingIntent.getService(
        appContext, 3,
        Intent(appContext, PlaybackService::class.java).setAction(PlaybackService.ACTION_TOGGLE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        appContext, 4,
        Intent(appContext, tech.egrie.soundtrail.MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * Everything a controller can ask for, in the app's own terms. [PlaybackHub] is the only
 * implementation, which is what keeps one player behind the notification, the lockscreen and
 * the car at the same time.
 */
internal interface SessionTransport {
    fun play()
    fun pause()
    fun stop()
    fun skipToNext()
    fun skipToPrevious()
    fun seekTo(positionMs: Long)
    fun playFromMediaId(mediaId: String)
    fun playFromSearch(query: String)
}
