package tech.egrie.soundtrail.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tech.egrie.soundtrail.integrations.JellyfinPlayback
import tech.egrie.soundtrail.integrations.MusicProvider
import java.io.File

/** What the now-playing bar shows. */
internal data class NowPlaying(
    val title: String,
    val artist: String,
    val itemId: String,
    val provider: MusicProvider = MusicProvider.JELLYFIN,
    val playing: Boolean,
    val buffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val error: String? = null,
    /** True once the item has played to its end; a play action rewinds instead of no-oping. */
    val completed: Boolean = false
)

/**
 * Unified in-app player: Jellyfin, local files, remote URLs (Spotify previews, SoundCloud streams,
 * YouTube audio via server, etc.) all play inside Soundtrail without handing off to another app.
 *
 * Previously this was Jellyfin-only; it now handles any source while keeping the same foreground
 * service, audio-focus, and notification behavior. The name JellyfinPlayer is retained for
 * compatibility with PlaybackService and ViewModel, but it is now the app's single player for
 * every provider.
 */
internal class JellyfinPlayer private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: JellyfinPlayer? = null

        /** The app's single player: the UI and [PlaybackService] must never own two streams. */
        fun shared(context: Context): JellyfinPlayer = instance ?: synchronized(this) {
            instance ?: JellyfinPlayer(context.applicationContext).also { instance = it }
        }
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var sources: List<Source> = emptyList()
    private var sourceIndex = 0
    private var ticker: Job? = null
    private var focusRequest: AudioFocusRequest? = null
    private var pausedForFocus = false

    private data class Source(
        val uri: String? = null,
        val file: File? = null,
        val headers: Map<String, String> = emptyMap()
    )

    /** Starts an item from its resolved stream URLs, trying the direct stream before transcoding. */
    fun play(title: String, artist: String, itemId: String, playback: JellyfinPlayback) {
        release()
        sources = playback.urls.map { url ->
            Source(uri = url, headers = mapOf("Authorization" to playback.authorization, "Accept" to "*/*"))
        }
        sourceIndex = 0
        _state.value = NowPlaying(
            title = title, artist = artist, itemId = itemId,
            provider = MusicProvider.JELLYFIN,
            playing = false, buffering = true, positionMs = 0, durationMs = 0
        )
        openCurrentSource()
        PlaybackService.ensureStarted(appContext)
    }

    /** Plays a local file stored in private app storage (imported audio or offline cache). */
    fun playLocalFile(title: String, artist: String, itemId: String, file: File, provider: MusicProvider) {
        if (!file.isFile || !file.canRead()) {
            _state.value = NowPlaying(
                title = title, artist = artist, itemId = itemId,
                provider = provider,
                playing = false, buffering = false, positionMs = 0, durationMs = 0,
                error = "That local audio file is missing."
            )
            return
        }
        release()
        sources = listOf(Source(file = file))
        sourceIndex = 0
        _state.value = NowPlaying(
            title = title, artist = artist, itemId = itemId,
            provider = provider,
            playing = false, buffering = true, positionMs = 0, durationMs = 0
        )
        openCurrentSource()
        PlaybackService.ensureStarted(appContext)
    }

    /** Plays a remote URL directly (Spotify preview, SoundCloud stream, etc.). */
    fun playRemoteUrl(
        title: String,
        artist: String,
        itemId: String,
        url: String,
        provider: MusicProvider,
        headers: Map<String, String> = emptyMap()
    ) {
        if (url.isBlank()) {
            _state.value = NowPlaying(
                title = title, artist = artist, itemId = itemId,
                provider = provider,
                playing = false, buffering = false, positionMs = 0, durationMs = 0,
                error = "No playable audio URL for this song."
            )
            return
        }
        release()
        sources = listOf(Source(uri = url, headers = headers))
        sourceIndex = 0
        _state.value = NowPlaying(
            title = title, artist = artist, itemId = itemId,
            provider = provider,
            playing = false, buffering = true, positionMs = 0, durationMs = 0
        )
        openCurrentSource()
        PlaybackService.ensureStarted(appContext)
    }

    /** Shows buffering state while resolving a stream (e.g. YouTube via server). */
    fun setBuffering(title: String, artist: String, itemId: String, provider: MusicProvider) {
        release()
        sources = emptyList()
        sourceIndex = 0
        _state.value = NowPlaying(
            title = title, artist = artist, itemId = itemId,
            provider = provider,
            playing = false, buffering = true, positionMs = 0, durationMs = 0
        )
        PlaybackService.ensureStarted(appContext)
    }

    /** Generic: list of URLs with optional headers, tries each in order (for fallback). */
    fun playUrls(
        title: String,
        artist: String,
        itemId: String,
        urls: List<String>,
        provider: MusicProvider,
        headers: Map<String, String> = emptyMap()
    ) {
        if (urls.isEmpty()) {
            _state.value = NowPlaying(
                title = title, artist = artist, itemId = itemId,
                provider = provider,
                playing = false, buffering = false, positionMs = 0, durationMs = 0,
                error = "No playable audio URL for this song."
            )
            return
        }
        release()
        sources = urls.map { Source(uri = it, headers = headers) }
        sourceIndex = 0
        _state.value = NowPlaying(
            title = title, artist = artist, itemId = itemId,
            provider = provider,
            playing = false, buffering = true, positionMs = 0, durationMs = 0
        )
        openCurrentSource()
        PlaybackService.ensureStarted(appContext)
    }

    fun toggle() {
        val media = player ?: return
        val current = _state.value ?: return
        if (current.error != null || current.buffering) return
        if (current.playing) {
            media.pause()
            ticker?.cancel()
            _state.update { it?.copy(playing = false, positionMs = media.currentPosition.toLong()) }
        } else {
            requestFocus()
            if (current.completed) media.seekTo(0)
            media.start()
            _state.update { it?.copy(playing = true, completed = false) }
            startTicker()
            PlaybackService.ensureStarted(appContext)
        }
    }

    /**
     * Moves within the item that is playing. This is what the car's scrubber, the lockscreen
     * and a "previous" press that restarts the song all land on; the bar follows through
     * [state] like it does for every other change.
     */
    fun seekTo(positionMs: Long) {
        val media = player ?: return
        val current = _state.value ?: return
        val limit = current.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = positionMs.coerceIn(0L, limit)
        runCatching { media.seekTo(target.toInt()) }
        ticker?.cancel()
        _state.update { it?.copy(positionMs = target, completed = false) }
        if (current.playing) startTicker()
    }

    /** Stops playback and clears the bar. */
    fun stop() {
        release()
        _state.value = null
    }

    private fun openCurrentSource() {
        val src = sources.getOrNull(sourceIndex)
        if (src == null) {
            val provider = _state.value?.provider ?: MusicProvider.JELLYFIN
            val msg = when (provider) {
                MusicProvider.JELLYFIN -> "Couldn't stream this song. It may have been moved on the server."
                MusicProvider.SPOTIFY -> "No Spotify preview available for this track. Spotify full playback requires their app, but previews play here when available."
                MusicProvider.SOUNDCLOUD -> "Couldn't stream this SoundCloud track. It may be private or removed."
                MusicProvider.YOUTUBE_MUSIC -> "Couldn't play this YouTube Music track. Connect your sync server for in-app audio or use an offline copy."
                MusicProvider.YOUTUBE -> "Couldn't play this YouTube track. Connect your sync server for in-app audio or use an offline copy."
            }
            fail(msg)
            return
        }
        val media = MediaPlayer()
        player = media
        try {
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            when {
                src.file != null -> {
                    media.setDataSource(src.file.absolutePath)
                }
                src.uri != null -> {
                    if (src.headers.isNotEmpty()) {
                        media.setDataSource(appContext, Uri.parse(src.uri), src.headers)
                    } else {
                        media.setDataSource(src.uri)
                    }
                }
                else -> {
                    fail("No audio source for this song.")
                    return
                }
            }
            media.setOnPreparedListener { prepared ->
                prepared.start()
                requestFocus()
                _state.update {
                    it?.copy(playing = true, buffering = false, durationMs = prepared.duration.coerceAtLeast(0).toLong())
                }
                startTicker()
            }
            media.setOnCompletionListener { finished ->
                ticker?.cancel()
                _state.update {
                    it?.copy(
                        playing = false,
                        positionMs = finished.duration.coerceAtLeast(0).toLong(),
                        completed = true
                    )
                }
            }
            media.setOnErrorListener { _, _, _ ->
                advancePastFailure(media)
                true
            }
            media.prepareAsync()
        } catch (_: Exception) {
            advancePastFailure(media)
        }
    }

    private fun advancePastFailure(failed: MediaPlayer) {
        runCatching {
            failed.reset()
            failed.release()
        }
        if (player === failed) player = null
        sourceIndex += 1
        if (sourceIndex < sources.size) {
            openCurrentSource()
        } else {
            val provider = _state.value?.provider ?: MusicProvider.JELLYFIN
            val msg = when (provider) {
                MusicProvider.JELLYFIN -> "Couldn't stream this song. The server may be offline or the file unplayable here."
                MusicProvider.SPOTIFY -> "Couldn't play Spotify preview for this track."
                MusicProvider.SOUNDCLOUD -> "Couldn't stream this SoundCloud track."
                MusicProvider.YOUTUBE_MUSIC -> "Couldn't play this YouTube Music track in-app."
                MusicProvider.YOUTUBE -> "Couldn't play this YouTube track in-app."
            }
            fail(msg)
        }
    }

    private fun fail(message: String) {
        ticker?.cancel()
        abandonFocus()
        _state.update { it?.copy(playing = false, buffering = false, error = message) }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && _state.value?.playing == true) {
                player?.let { media ->
                    val position = media.currentPosition.toLong()
                    _state.update { it?.copy(positionMs = position) }
                }
                delay(500)
            }
        }
    }

    private fun release() {
        ticker?.cancel()
        ticker = null
        runCatching {
            player?.reset()
            player?.release()
        }
        player = null
        sources = emptyList()
        sourceIndex = 0
        pausedForFocus = false
        abandonFocus()
    }

    // ----- Audio focus: pause for calls and other players, then resume when it is polite -----

    private fun requestFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> scope.launch { pauseForFocus(resumeLater = false) }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                        scope.launch { pauseForFocus(resumeLater = true) }
                    AudioManager.AUDIOFOCUS_GAIN -> scope.launch { resumeAfterFocus() }
                }
            }
            .build()
            .also { focusRequest = it }
        runCatching { manager.requestAudioFocus(request) }
    }

    private fun pauseForFocus(resumeLater: Boolean) {
        val media = player ?: return
        if (_state.value?.playing != true) return
        pausedForFocus = resumeLater
        media.pause()
        ticker?.cancel()
        _state.update { it?.copy(playing = false, positionMs = media.currentPosition.toLong()) }
    }

    private fun resumeAfterFocus() {
        val media = player ?: return
        if (!pausedForFocus || _state.value?.playing == true) return
        pausedForFocus = false
        media.start()
        _state.update { it?.copy(playing = true) }
        startTicker()
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        focusRequest?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
    }
}
