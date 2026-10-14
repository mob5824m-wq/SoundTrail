package tech.egrie.soundtrail.playback

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.egrie.soundtrail.automation.AutomationStore
import tech.egrie.soundtrail.auto.AutoTree
import tech.egrie.soundtrail.data.FileStore
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.PinStore
import tech.egrie.soundtrail.data.PlaylistItem
import tech.egrie.soundtrail.data.PlaylistStore
import tech.egrie.soundtrail.integrations.JellyfinRef
import tech.egrie.soundtrail.integrations.JellyfinService
import tech.egrie.soundtrail.integrations.MusicLink
import tech.egrie.soundtrail.integrations.MusicLinkParser
import tech.egrie.soundtrail.integrations.MusicProvider
import tech.egrie.soundtrail.integrations.SoundCloudService
import tech.egrie.soundtrail.integrations.SpotifyService
import tech.egrie.soundtrail.sync.CloudSyncService
import java.io.File

/**
 * The one place playback is decided, for every surface the app has.
 *
 * The in-app bar, the notification, the lockscreen, headset buttons and Android Auto all end
 * up here, and all of them drive the same [JellyfinPlayer] — there is never a second stream,
 * and never a second answer to "what is playing".
 *
 * A *queue* is what makes that possible. Tapping a playlist song in the app queues the
 * playlist, the car's browse tree queues what it just listed, and a mood mix is simply a
 * queue built from the mix. When a song ends the next entry starts, an entry that cannot
 * play reports why and the queue moves on instead of going quiet, and [NowPlayingSession]
 * mirrors all of it to whatever controller is listening.
 *
 * Must be shared() from the main thread: the media session installs a handler when it is built.
 */
internal class PlaybackHub private constructor(context: Context) : SessionTransport {
    companion object {
        /** Past this, "previous" restarts the song instead of stepping back a track. */
        private const val PREVIOUS_RESTARTS_AFTER_MS = 3_000L

        @Volatile
        private var instance: PlaybackHub? = null

        /** The app's single hub; the UI, [PlaybackService] and the car service all use this one. */
        fun shared(context: Context): PlaybackHub = instance ?: synchronized(this) {
            instance ?: PlaybackHub(context.applicationContext).also { instance = it }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val queue = PlaybackQueue()
    private val files = FileStore(appContext)
    private val pins = PinStore(appContext)
    private val playlists = PlaylistStore(appContext)
    private val jellyfin = JellyfinService(appContext)
    private val spotify = SpotifyService(appContext)
    private val soundcloud = SoundCloudService(appContext)
    private val cloudSync = CloudSyncService(appContext, pins, playlists, AutomationStore(appContext), files)

    /** The app's single player, kept public so screens can render it directly. */
    val player: JellyfinPlayer = JellyfinPlayer.shared(appContext)

    /** The platform's view of playback: one session for the notification, lockscreen and car. */
    val session = NowPlayingSession(appContext, this)

    private val _queueState = MutableStateFlow(QueueState())
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()

    /** Things the listener should be told about (a song that will not play, and why). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Incremented by every new start, so a slow lookup cannot hand back a song already replaced. */
    private var startToken = 0

    /** The entry whose end has already been acted on, so one completion advances once. */
    private var advancedPast: String? = null

    /** The last YouTube audio pulled for in-app playback, kept only until the next one. */
    private var lastYouTubeCopy: File? = null

    /** Told when the library changes, so an open car list can refresh. */
    private var libraryWatcher: (() -> Unit)? = null

    init {
        scope.launch {
            player.state.collect { state ->
                session.publish(state, _queueState.value)
                val current = queue.current
                if (state?.completed != true) {
                    advancedPast = null
                    return@collect
                }
                // The song reached its end: hand the next one over, once per finished item.
                if (current == null || state.itemId != current.mediaId) return@collect
                if (advancedPast == current.mediaId) return@collect
                advancedPast = current.mediaId
                if (queue.hasNext()) startAt(queue.index + 1) else discardYouTubeCopy()
            }
        }
    }

    // ----- What the app itself plays -----

    /** Plays [entries] from [startIndex]; every surface that starts playback goes through here. */
    fun play(entries: List<QueueEntry>, startIndex: Int = 0) {
        val playable = entries.filter { it.hasSource }
        if (playable.isEmpty()) {
            message("Nothing here can play yet — save a link, or attach audio you own.")
            return
        }
        // Keeping the caller's order matters: the car highlights rows by their place in the
        // queue, so the entry that was tapped has to keep its position after filtering.
        val target = entries.getOrNull(startIndex)
        val requested = target?.let { playable.indexOfFirst { entry -> entry.mediaId == it.mediaId } } ?: 0
        queue.start(playable, if (requested >= 0) requested else 0)
        startToken += 1
        publishQueue()
        startAt(queue.index)
    }

    /** One song on its own: what a search result or a single tap on a pin means. */
    fun playEntry(entry: QueueEntry) = play(listOf(entry), 0)

    fun playPin(pin: MusicPin) = playEntry(pin.toQueueEntry())

    /** A playlist song plays in the context of its playlist, so next/previous have somewhere to go. */
    fun playPlaylistItem(item: PlaylistItem, playlist: List<PlaylistItem>, playlistName: String = "") {
        play(playlist.map { it.toQueueEntry(playlistName) }, playlist.indexOfFirst { it.id == item.id })
    }

    fun toggle() = player.toggle()

    /** Ends playback and forgets the queue: what the app's bar, the notification and the car all mean. */
    fun stopPlayback() {
        startToken += 1
        queue.clear()
        publishQueue()
        player.stop()
        discardYouTubeCopy()
    }

    /** Drops the temporary YouTube audio file kept for in-app playback, if any. */
    fun clearYouTubeCache() = discardYouTubeCopy()

    // ----- What the car, the lockscreen and the headset ask for -----

    override fun play() {
        val state = player.state.value
        when {
            state?.playing == true -> Unit
            state != null -> player.toggle()
            queue.current != null -> startAt(queue.index)
            else -> playFirstFromLibrary()
        }
    }

    override fun pause() {
        if (player.state.value?.playing == true) player.toggle()
    }

    override fun stop() = stopPlayback()

    override fun skipToNext() {
        when {
            queue.hasNext() -> startAt(queue.index + 1)
            queue.current == null -> playFirstFromLibrary()
            else -> Unit
        }
    }

    override fun skipToPrevious() {
        // The usual car behaviour: back restarts the song that is playing, and only a press
        // once it is already at the start moves on to the song before it.
        if ((player.state.value?.positionMs ?: 0L) > PREVIOUS_RESTARTS_AFTER_MS) {
            player.seekTo(0L)
            return
        }
        if (queue.hasPrevious()) startAt(queue.index - 1) else player.seekTo(0L)
    }

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    /** A tapped row in the car (or an Assistant "play X"). Unplayable ids simply do nothing. */
    override fun playFromMediaId(mediaId: String) {
        val selection = AutoTree.selection(mediaId, pins.all(), playlists.all()) ?: return
        play(selection.entries, selection.index)
    }

    /** A spoken search: matched against the songs already on this phone, never sent anywhere. */
    override fun playFromSearch(query: String) {
        val text = query.trim()
        if (text.isEmpty()) {
            play()
            return
        }
        val selection = AutoTree.search(text, pins.all(), playlists.all())
        if (selection == null) {
            message("Nothing in your library matches \u201C$text\u201D.")
            return
        }
        play(selection.entries, selection.index)
    }

    // ----- Telling the UI and the car what changed -----

    /** Republishes the current moment; used when a car or a controller connects to a live app. */
    fun refresh() {
        _queueState.value = QueueState(queue.entries, queue.index)
        session.publish(player.state.value, _queueState.value)
    }

    fun setLibraryListener(listener: (() -> Unit)?) {
        libraryWatcher = listener
    }

    /** Called after pins, playlists or offline copies change, so open car lists refresh. */
    fun notifyLibraryChanged() {
        runCatching { libraryWatcher?.invoke() }
    }

    fun canSkipNext(): Boolean = queue.hasNext()

    fun canSkipPrevious(): Boolean = queue.hasPrevious()

    // ----- Starting one entry, whatever provider it came from -----

    /**
     * Walks [index] and its neighbours until something actually starts. An entry that cannot
     * play — a song with no link, a Jellyfin server that is not connected, a Spotify track
     * with no preview — reports why and the queue moves on.
     */
    private fun startAt(index: Int) {
        val token = ++startToken
        scope.launch {
            var cursor = index
            while (cursor in queue.entries.indices && token == startToken) {
                val entry = queue.moveTo(cursor) ?: return@launch
                publishQueue()
                if (start(entry)) return@launch
                cursor += 1
            }
        }
    }

    private suspend fun start(entry: QueueEntry): Boolean {
        // An attached copy always wins: it plays with no network and no account at all.
        entry.localFile?.let { name ->
            val file = files.fileFor(name)
            if (file != null && file.isFile && file.canRead()) {
                player.playLocalFile(
                    title = entry.title,
                    artist = entry.artist,
                    itemId = entry.mediaId,
                    file = file,
                    provider = entry.provider ?: MusicProvider.YOUTUBE
                )
                return true
            }
        }
        // A preview URL a search already resolved needs no second lookup.
        entry.streamUrl?.takeIf(String::isNotBlank)?.let { stream ->
            player.playRemoteUrl(
                title = entry.title,
                artist = entry.artist,
                itemId = entry.mediaId,
                url = stream,
                provider = entry.provider ?: MusicProvider.YOUTUBE
            )
            return true
        }
        val link = entry.url?.let(MusicLinkParser::parse)
        if (link == null) {
            return fail("${entry.title} has no playable link. Add a supported link or attach audio you own.")
        }
        return when (link.provider) {
            MusicProvider.JELLYFIN -> startJellyfin(link, entry)
            MusicProvider.SPOTIFY -> startSpotify(link, entry)
            MusicProvider.SOUNDCLOUD -> startSoundcloud(link, entry)
            MusicProvider.YOUTUBE, MusicProvider.YOUTUBE_MUSIC -> startYouTube(link, entry)
        }
    }

    private fun startJellyfin(link: MusicLink, entry: QueueEntry): Boolean {
        val itemId = JellyfinRef.itemId(link.url)
            ?: return fail("${entry.title} is not a usable Jellyfin link.")
        val playback = jellyfin.playback(itemId)
            ?: return fail("Connect your Jellyfin server on the Connect tab to stream ${entry.title}.")
        player.play(
            title = entry.title,
            artist = entry.artist,
            itemId = entry.mediaId,
            playback = playback
        )
        return true
    }

    private suspend fun startSpotify(link: MusicLink, entry: QueueEntry): Boolean {
        player.setBuffering(entry.title, entry.artist, entry.mediaId, MusicProvider.SPOTIFY)
        val preview = try {
            spotify.resolvePlayableUrl(link)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return fail("No Spotify preview is available for ${entry.title}. Full playback needs Spotify's own app.")
        player.playRemoteUrl(entry.title, entry.artist, entry.mediaId, preview, MusicProvider.SPOTIFY)
        return true
    }

    private suspend fun startSoundcloud(link: MusicLink, entry: QueueEntry): Boolean {
        player.setBuffering(entry.title, entry.artist, entry.mediaId, MusicProvider.SOUNDCLOUD)
        val stream = try {
            soundcloud.resolvePlayableUrl(link)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return fail("Couldn't get a SoundCloud stream for ${entry.title}. It may be private or need reconnecting.")
        player.playRemoteUrl(entry.title, entry.artist, entry.mediaId, stream, MusicProvider.SOUNDCLOUD)
        return true
    }

    /**
     * YouTube audio is prepared by the user's own sync server, so this path needs the server
     * connected before it can start. The result is a private temporary file that plays through
     * the same player as everything else, and is dropped as soon as another song starts.
     */
    private suspend fun startYouTube(link: MusicLink, entry: QueueEntry): Boolean {
        val videoId = MusicLinkParser.youtubeVideoId(link.url)
            ?: return fail("That YouTube link is not a playable video.")
        if (!cloudSync.connected) {
            return fail("Connect your sync server to play ${entry.title} in-app, or download it for offline playback first.")
        }
        player.setBuffering(entry.title, entry.artist, entry.mediaId, link.provider)
        discardYouTubeCopy()
        val staging = withContext(Dispatchers.IO) { files.newDownloadTemp() }
        return try {
            cloudSync.downloadYouTubeAudio(videoId, staging, userConfirmedRights = true) { _, _, _ -> }
            if (!staging.isFile || staging.length() == 0L) {
                withContext(Dispatchers.IO) { files.discardDownloadTemp(staging) }
                fail("Couldn't prepare YouTube audio for ${entry.title}.")
            } else {
                lastYouTubeCopy = staging
                player.playLocalFile(entry.title, entry.artist, entry.mediaId, staging, link.provider)
                true
            }
        } catch (e: CancellationException) {
            withContext(Dispatchers.IO) { files.discardDownloadTemp(staging) }
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { files.discardDownloadTemp(staging) }
            fail(e.message ?: "YouTube playback failed for ${entry.title}.")
        }
    }

    /** Starts the first thing in the library; what pressing play in the car does with nothing chosen. */
    private fun playFirstFromLibrary() {
        val selection = AutoTree.firstSelection(pins.all(), playlists.all())
        if (selection == null) {
            message("Nothing to play yet — save a pin or add a song to a playlist first.")
            return
        }
        play(selection.entries, selection.index)
    }

    private fun publishQueue() {
        _queueState.value = QueueState(queue.entries, queue.index)
        session.publish(player.state.value, _queueState.value)
    }

    /** Publishes a reason playback stopped at this song, and reports that nothing started. */
    private fun fail(reason: String): Boolean {
        message(reason)
        return false
    }

    private fun message(text: String) {
        _messages.tryEmit(text)
    }

    private fun discardYouTubeCopy() {
        val copy = lastYouTubeCopy ?: return
        lastYouTubeCopy = null
        scope.launch(Dispatchers.IO) { files.discardDownloadTemp(copy) }
    }
}
