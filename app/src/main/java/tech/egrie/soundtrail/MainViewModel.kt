package tech.egrie.soundtrail

import android.accounts.Account
import android.app.Application
import android.net.Uri
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.egrie.soundtrail.automation.AutomationRule
import tech.egrie.soundtrail.automation.AutomationStore
import tech.egrie.soundtrail.automation.LocationWatchService
import tech.egrie.soundtrail.data.BackupException
import tech.egrie.soundtrail.data.BackupFormat
import tech.egrie.soundtrail.data.FileStore
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.PinStore
import tech.egrie.soundtrail.data.Playlist
import tech.egrie.soundtrail.data.PlaylistItem
import tech.egrie.soundtrail.data.LikedSongs
import tech.egrie.soundtrail.data.consolidateLikes
import tech.egrie.soundtrail.data.PlaylistStore
import tech.egrie.soundtrail.data.StoredAudioFile
import tech.egrie.soundtrail.integrations.AudioDbService
import tech.egrie.soundtrail.integrations.DeviceApps
import tech.egrie.soundtrail.integrations.JellyfinRef
import tech.egrie.soundtrail.integrations.JellyfinService
import tech.egrie.soundtrail.integrations.MusicLink
import tech.egrie.soundtrail.integrations.MusicLinkParser
import tech.egrie.soundtrail.integrations.MusicProvider
import tech.egrie.soundtrail.integrations.SongInfo
import tech.egrie.soundtrail.integrations.SoundCloudService
import tech.egrie.soundtrail.integrations.SpotifyService
import tech.egrie.soundtrail.assistant.AssistantAnswer
import tech.egrie.soundtrail.assistant.AssistantHit
import tech.egrie.soundtrail.assistant.AssistantLibrary
import tech.egrie.soundtrail.assistant.AssistantParser
import tech.egrie.soundtrail.assistant.toQueueEntry
import tech.egrie.soundtrail.integrations.TrackResult
import tech.egrie.soundtrail.integrations.GoogleAuthHealth
import tech.egrie.soundtrail.integrations.GoogleAuthState
import tech.egrie.soundtrail.integrations.YouTubeConsentRequiredException
import tech.egrie.soundtrail.integrations.YouTubeDataService
import tech.egrie.soundtrail.integrations.YouTubeImportedPlaylist
import tech.egrie.soundtrail.integrations.YouTubeReconnectRequiredException
import tech.egrie.soundtrail.integrations.YouTubeSearch
import tech.egrie.soundtrail.location.DeviceLocation
import tech.egrie.soundtrail.mood.DEFAULT_OLLAMA_MODEL
import tech.egrie.soundtrail.mood.MoodAiException
import tech.egrie.soundtrail.mood.MoodAiProvider
import tech.egrie.soundtrail.mood.MoodAiService
import tech.egrie.soundtrail.mood.MoodAiUrls
import tech.egrie.soundtrail.mood.MoodCandidate
import tech.egrie.soundtrail.mood.MoodContext
import tech.egrie.soundtrail.mood.MoodEngine
import tech.egrie.soundtrail.mood.MoodMixTrack
import tech.egrie.soundtrail.mood.MoodMixer
import tech.egrie.soundtrail.mood.MoodProfile
import tech.egrie.soundtrail.mood.MoodStore
import tech.egrie.soundtrail.mood.TrackMoodTags
import tech.egrie.soundtrail.playback.JellyfinPlayer
import tech.egrie.soundtrail.playback.LibraryMedia
import tech.egrie.soundtrail.playback.PlaybackHub
import tech.egrie.soundtrail.playback.QueueEntry
import tech.egrie.soundtrail.playback.toQueueEntry
import tech.egrie.soundtrail.sync.CloudSyncException
import tech.egrie.soundtrail.sync.CloudSyncService
import tech.egrie.soundtrail.sync.YouTubeAudioStage
import java.util.UUID

/** Which connected catalog the Search tab queries. */
internal enum class SearchProvider(val label: String) {
    SPOTIFY("Spotify"), SOUNDCLOUD("SoundCloud"), JELLYFIN("Jellyfin"), YOUTUBE_DATA("YouTube")
}

internal data class AppState(
    val pins: List<MusicPin> = emptyList(),
    val location: GeoPoint? = null,
    val locating: Boolean = false,
    val spotifyConnected: Boolean = false,
    val spotifyClientId: String = "",
    val spotifyName: String? = null,
    val soundcloudConnected: Boolean = false,
    val soundcloudClientId: String = "",
    val soundcloudName: String? = null,
    val jellyfinConnected: Boolean = false,
    val jellyfinServer: String = "",
    val jellyfinName: String = "",
    val jellyfinServerName: String = "",
    val youtubeDataConnected: Boolean = false,
    val youtubeDataAccount: String? = null,
    val youtubeOAuthClientId: String = "",
    val connecting: Boolean = false,
    val connectingSoundcloud: Boolean = false,
    val connectingJellyfin: Boolean = false,
    val connectingYouTubeData: Boolean = false,
    val connectingYouTubeOAuth: Boolean = false,
    val searchProvider: SearchProvider = SearchProvider.SPOTIFY,
    val searching: Boolean = false,
    val results: List<TrackResult> = emptyList(),
    val searchError: String? = null,
    val lookupSearching: Boolean = false,
    val lookupResults: List<SongInfo> = emptyList(),
    val lookupError: String? = null,
    val playlists: List<tech.egrie.soundtrail.data.Playlist> = emptyList(),
    val automations: List<AutomationRule> = emptyList(),
    val autoSearchYoutube: Boolean = true,
    val backupBusy: Boolean = false,
    val syncServer: String = "",
    val syncEffectiveServer: String = CloudSyncService.DEFAULT_SYNC_SERVER,
    val syncUsingCustomServer: Boolean = false,
    val syncEmail: String = "",
    val syncConnected: Boolean = false,
    val syncHasRecoveryKey: Boolean = false,
    val syncBusy: Boolean = false,
    val syncAutoEnabled: Boolean = false,
    val syncLastSyncAt: Long = 0L,
    val syncPendingChanges: Int = 0,
    val syncFailed: Boolean = false,
    val syncError: String? = null,
    val syncRecoveryKey: String? = null,
    val jellyfinDownloads: Map<String, JellyfinDownloadProgress> = emptyMap(),
    val youtubeDownloads: Map<String, YouTubeDownloadProgress> = emptyMap(),
    val youtubeImporting: Boolean = false,
    val youtubeAutoImportEnabled: Boolean = true,
    val youtubeLastImportedAt: Long = 0L,
    val offlineAudioFiles: List<OfflineAudioItem> = emptyList(),
    val offlineAudioBusy: Boolean = false,
    val audioStorageBytes: Long = 0L,
    val watchStatus: tech.egrie.soundtrail.automation.WatchStatus = tech.egrie.soundtrail.automation.WatchStatus(),
    val spotifyAppInstalled: Boolean = false,
    val soundcloudAppInstalled: Boolean = false,
    val youtubeAppInstalled: Boolean = false,
    val servicesLabel: String = "Checking services…",
    val gmsSignInAvailable: Boolean = false,
    val microGReInstalled: Boolean = false,
    /** The mood reader: what the listener typed, what it meant, and the mix it produced. */
    val mood: MoodUiState = MoodUiState(),
    /** The Ask tab: what was said or typed, and the answer to it. */
    val assistant: AssistantUiState = AssistantUiState(),
    /** Health of the saved Google connection; never contains a token value. */
    val youtubeAuth: GoogleAuthHealth = GoogleAuthHealth(GoogleAuthState.NOT_CONNECTED)
)

/**
 * Everything the Ask tab renders: the request in progress and the answer to the last one.
 *
 * The microphone itself is not here — it belongs to the activity, which owns the recognizer's
 * lifetime and the permission. What this holds is the words, and what the app found for them.
 */
internal data class AssistantUiState(
    val input: String = "",
    /** True while the microphone is open; the activity reports it back as it changes. */
    val listening: Boolean = false,
    /** The words heard so far, shown while they are still being said. */
    val partial: String = "",
    /** True while the library and the catalogue are being searched. */
    val thinking: Boolean = false,
    /** The sentence the answer says, shown and — when [speakReplies] is on — spoken. */
    val reply: String? = null,
    /** What the answer found, best first. Empty while a request is still being understood. */
    val hits: List<AssistantHit> = emptyList(),
    /** What was asked, so an answer can explain itself. */
    val question: String = "",
    /** True when the reply should be read out loud. */
    val speakReplies: Boolean = true
)

/**
 * Everything the Mood tab renders. Kept next to the screen's other state so the UI stays a pure
 * function of [AppState].
 */
internal data class MoodUiState(
    val text: String = "",
    /** 0–100 "how much energy do you want", or null for "let the words decide". */
    val energyHint: Int? = null,
    val analyzing: Boolean = false,
    val profile: MoodProfile? = null,
    val mix: List<MoodMixTrack> = emptyList(),
    val error: String? = null,
    /** Index into [mix] while the mix is playing, or -1. */
    val queueIndex: Int = -1,
    val queuePlaying: Boolean = false,
    val aiEnabled: Boolean = false,
    /**
     * Where a reading comes from when [aiEnabled]: on-device, Ollama, an OpenAI-compatible
     * endpoint, or the fixed hosted model.
     */
    val aiProvider: MoodAiProvider = MoodAiProvider.ON_DEVICE,
    val aiModel: String = MoodStore.DEFAULT_AI_MODEL,
    val ollamaUrl: String = "",
    val ollamaModel: String = DEFAULT_OLLAMA_MODEL,
    /** Models the listener's Ollama server reported on the last check. */
    val ollamaModels: List<String> = emptyList(),
    /** An OpenAI-compatible endpoint, as typed. */
    val openAiUrl: String = "",
    /** The model asked for there; blank until the listener names one. */
    val openAiModel: String = "",
    /** Models that endpoint listed on the last check. */
    val openAiModels: List<String> = emptyList(),
    /** True while a connection test or a model check is running. */
    val aiBusy: Boolean = false,
    /** A sentence from the last test, shown under the provider picker. */
    val aiNote: String? = null,
    /** True when the selected provider has everything it needs right now. */
    val aiReady: Boolean = false,
    val aiKeySet: Boolean = false,
    val aiKeySaved: Boolean = false,
    val enrichEnabled: Boolean = false,
    val enriching: Boolean = false,
    val enrichProgress: Int = 0,
    val enrichTotal: Int = 0,
    val librarySize: Int = 0,
    val untaggedCount: Int = 0,
    val savedPlaylistName: String? = null
)

internal data class JellyfinDownloadProgress(val receivedBytes: Long, val totalBytes: Long)

internal data class YouTubeDownloadProgress(
    val stage: YouTubeAudioStage,
    val receivedBytes: Long,
    val totalBytes: Long
)

internal data class OfflineAudioItem(
    val fileName: String,
    val title: String,
    val detail: String,
    val sizeBytes: Long
)

/** How many TheAudioDB lookups one mood refinement may make; keeps it quick and polite. */
private const val MAX_MOOD_TAG_LOOKUPS = 12

internal data class PinInput(
    val title: String,
    val artist: String,
    val place: String,
    val note: String,
    val url: String,
    val point: GeoPoint?
)

internal class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val pins = PinStore(application)
    private val playlists = PlaylistStore(application)
    private val automations = AutomationStore(application)
    private val files = FileStore(application)
    private val spotify = SpotifyService(application)
    private val soundcloud = SoundCloudService(application)
    private val jellyfin = JellyfinService(application)
    private val youtubeData = YouTubeDataService(application)
    private val audioDb = AudioDbService()
    private val locationReader = DeviceLocation(application)
    private val apps = DeviceApps(application)
    private val cloudSync = CloudSyncService(application, pins, playlists, automations, files)
    private val youtubePrefs = application.getSharedPreferences("soundtrail_youtube", android.content.Context.MODE_PRIVATE)
    private val moodPrefs = MoodStore(application)

    // The optional AI reader, and the on-device engine that always backs it up.
    private val moodAi = MoodAiService(moodPrefs)

    /**
     * The app's one playback hub: the queue, the stream resolution, the media session and the
     * player behind every surface this app has — its own bar, the notification, the lockscreen
     * and Android Auto. Screens render [player] through it, so no two surfaces can ever show a
     * different song.
     */
    private val hub = PlaybackHub.shared(application)

    /**
     * The app's one player, shared with [tech.egrie.soundtrail.playback.PlaybackService] so
     * the in-app bar and the playback notification always drive the same stream.
     */
    val player: JellyfinPlayer = hub.player

    /** The media ids of the mix that is playing, so the Mood tab's rows light up as it goes. */
    private var moodQueueKeys: List<String> = emptyList()

    private val _state = MutableStateFlow(
        AppState(
            pins = pins.all(),
            playlists = playlists.all(),
            automations = automations.all(),
            offlineAudioFiles = buildOfflineAudioItems(playlists.all()),
            audioStorageBytes = files.totalBytes(),
            syncPendingChanges = cloudSync.pendingChangeCount(),
            spotifyConnected = spotify.connected,
            spotifyClientId = spotify.clientId,
            soundcloudConnected = soundcloud.connected,
            soundcloudClientId = soundcloud.clientId,
            jellyfinConnected = jellyfin.connected,
            jellyfinServer = jellyfin.serverUrl,
            jellyfinName = jellyfin.userName,
            jellyfinServerName = jellyfin.serverName,
            youtubeDataConnected = youtubeData.connected,
            youtubeDataAccount = youtubeData.accountName,
            youtubeOAuthClientId = youtubeData.oauthClientId,
            gmsSignInAvailable = apps.systemGmsInstalled,
            microGReInstalled = apps.microGReInstalled,
            syncServer = cloudSync.serverUrl,
            syncEffectiveServer = cloudSync.effectiveServerUrl,
            syncUsingCustomServer = cloudSync.isUsingCustomServer,
            syncEmail = cloudSync.accountEmail,
            syncConnected = cloudSync.connected,
            syncHasRecoveryKey = cloudSync.hasRecoveryKey,
            syncAutoEnabled = cloudSync.autoSyncEnabled,
            syncLastSyncAt = cloudSync.lastSyncAt,
            mood = MoodUiState(
                text = moodPrefs.lastText,
                energyHint = moodPrefs.lastEnergyHint,
                aiEnabled = moodPrefs.aiEnabled,
                aiProvider = moodPrefs.aiProvider,
                aiModel = moodPrefs.aiModel,
                ollamaUrl = moodPrefs.ollamaUrl,
                ollamaModel = moodPrefs.ollamaModel,
                openAiUrl = moodPrefs.openAiUrl,
                openAiModel = moodPrefs.openAiModel,
                aiKeySet = moodPrefs.keySet(moodPrefs.aiProvider),
                aiReady = moodAi.configured,
                enrichEnabled = moodPrefs.tagLookupEnabled
            ),
            youtubeAuth = youtubeData.authHealth()
        )
    )
    val state = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    /**
     * Sentences for the phone to say out loud. The speaking engine belongs to the activity (it
     * owns an Android service and its lifetime), so the words are handed over rather than spoken
     * here; when the listener has speech replies switched off, nothing is emitted at all.
     */
    private val _speech = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val speech = _speech.asSharedFlow()

    init {
        refreshDeviceStatus()
        if (spotify.connected) loadProfile()
        if (soundcloud.connected) loadSoundcloudProfile()
        if (cloudSync.autoSyncEnabled && cloudSync.connected && cloudSync.hasRecoveryKey) {
            runCloudSync(showMessages = false)
        }
        // If the user left auto-import on and YouTube is already connected (after an upgrade or
        // process restart), do a quiet background refresh. This is a pull, not a mutation of any
        // remote account: it just adds newly discovered videos to local playlists.
        if (youtubeData.connected && youtubePrefs.getBoolean("auto_import", true)) {
            viewModelScope.launch { runCatching { runYouTubeImport(showMessages = false) } }
        }
        // Renew the saved Google access token in the background. Nothing here can sign the
        // listener out: a failure only changes the status line on the Connect tab, and the tokens
        // stay put for a one-tap reconnect.
        if (youtubeData.oauthConnected) {
            viewModelScope.launch {
                youtubeData.warmUp()
                _state.update { it.copy(youtubeAuth = youtubeData.authHealth()) }
            }
        }
        viewModelScope.launch {
            LocationWatchService.status.collect { live -> _state.update { it.copy(watchStatus = live) } }
        }
        // Playback lives in the hub, so the Mood tab follows the song that is playing rather
        // than owning a queue of its own: the mix that plays in the car lights up here too.
        viewModelScope.launch {
            player.state.collect { current ->
                val playing = current?.playing == true
                val index = current?.itemId?.let { itemId -> moodQueueKeys.indexOf(itemId) } ?: -1
                val mood = _state.value.mood
                if (mood.queuePlaying != playing || mood.queueIndex != index) {
                    _state.update {
                        it.copy(
                            mood = it.mood.copy(
                                queueIndex = index,
                                queuePlaying = index >= 0 && playing
                            )
                        )
                    }
                }
                // The mix played to its end: say so once, the way the app always has. The
                // queue still has to be that very mix — the car, or another tap in the app,
                // may have replaced it with a list of its own.
                if (current?.completed == true && moodQueueKeys.isNotEmpty() &&
                    current.itemId == moodQueueKeys.last() &&
                    hub.queueState.value.entries.map { it.mediaId } == moodQueueKeys
                ) {
                    moodQueueKeys = emptyList()
                    notify("That's the end of the mood mix.")
                }
            }
        }
        // Anything the hub could not play is something the listener should read.
        viewModelScope.launch { hub.messages.collect { notify(it) } }
    }

    fun notify(message: String) { _messages.tryEmit(message) }

    fun refreshDeviceStatus() {
        _state.update {
            it.copy(
                spotifyAppInstalled = apps.spotifyInstalled,
                soundcloudAppInstalled = apps.soundcloudInstalled,
                youtubeAppInstalled = apps.youtubeMusicPackage != null,
                servicesLabel = apps.servicesLabel,
                gmsSignInAvailable = apps.systemGmsInstalled,
                microGReInstalled = apps.microGReInstalled,
                spotifyConnected = spotify.connected,
                soundcloudConnected = soundcloud.connected,
                jellyfinConnected = jellyfin.connected,
                youtubeDataConnected = youtubeData.connected,
                youtubeDataAccount = youtubeData.accountName,
                youtubeOAuthClientId = youtubeData.oauthClientId,
                youtubeAutoImportEnabled = youtubePrefs.getBoolean("auto_import", true),
                youtubeLastImportedAt = youtubePrefs.getLong("last_imported_at", 0L),
                youtubeAuth = youtubeData.authHealth(),
                syncServer = cloudSync.serverUrl,
                syncEffectiveServer = cloudSync.effectiveServerUrl,
                syncUsingCustomServer = cloudSync.isUsingCustomServer,
                syncEmail = cloudSync.accountEmail,
                syncConnected = cloudSync.connected,
                syncHasRecoveryKey = cloudSync.hasRecoveryKey,
                syncAutoEnabled = cloudSync.autoSyncEnabled,
                syncLastSyncAt = cloudSync.lastSyncAt,
                syncPendingChanges = cloudSync.pendingChangeCount()
            )
        }
    }

    fun locate() {
        if (_state.value.locating) return
        _state.update { it.copy(locating = true) }
        viewModelScope.launch {
            try {
                val point = locationReader.locate()
                _state.update { it.copy(location = point) }
                notify("Location ready. Coordinates are used only for your saved pins and opt-in automations.")
            } catch (e: Exception) {
                notify(e.message ?: "Couldn't find your location.")
            } finally {
                _state.update { it.copy(locating = false) }
            }
        }
    }

    fun beginSpotify(clientId: String): Uri {
        val uri = spotify.beginSignIn(clientId)
        _state.update { it.copy(spotifyClientId = spotify.clientId, spotifyConnected = spotify.connected) }
        return uri
    }

    fun cancelSpotify() = spotify.cancelSignIn()

    fun finishSpotify(uri: Uri) {
        if (_state.value.connecting) return
        _state.update { it.copy(connecting = true) }
        viewModelScope.launch {
            try {
                spotify.finishSignIn(uri)
                _state.update { it.copy(spotifyConnected = true, spotifyClientId = spotify.clientId) }
                notify("Spotify connected. Find a track to pin!")
                loadProfile()
            } catch (e: Exception) {
                notify(e.message ?: "Could not connect Spotify.")
            } finally {
                _state.update { it.copy(connecting = false, spotifyConnected = spotify.connected) }
            }
        }
    }

    private fun loadProfile() {
        viewModelScope.launch {
            // The connection still works when profile lookup is unavailable/offline.
            val name = runCatching { spotify.profileName() }.getOrNull()
            _state.update { it.copy(spotifyName = name, spotifyConnected = spotify.connected) }
        }
    }

    fun disconnectSpotify() {
        spotify.disconnect()
        _state.update {
            it.copy(spotifyConnected = false, spotifyName = null, results = emptyList(), searchError = null)
        }
        notify("Spotify disconnected on this device.")
    }

    fun beginSoundcloud(clientId: String, clientSecret: String): Uri {
        val uri = soundcloud.beginSignIn(clientId, clientSecret)
        _state.update {
            it.copy(soundcloudClientId = soundcloud.clientId, soundcloudConnected = soundcloud.connected)
        }
        return uri
    }

    fun cancelSoundcloud() = soundcloud.cancelSignIn()

    fun finishSoundcloud(uri: Uri) {
        if (_state.value.connectingSoundcloud) return
        _state.update { it.copy(connectingSoundcloud = true) }
        viewModelScope.launch {
            try {
                soundcloud.finishSignIn(uri)
                _state.update { it.copy(soundcloudConnected = true, soundcloudClientId = soundcloud.clientId) }
                notify("SoundCloud connected. Find a track to pin!")
                loadSoundcloudProfile()
            } catch (e: Exception) {
                notify(e.message ?: "Could not connect SoundCloud.")
            } finally {
                _state.update { it.copy(connectingSoundcloud = false, soundcloudConnected = soundcloud.connected) }
            }
        }
    }

    private fun loadSoundcloudProfile() {
        viewModelScope.launch {
            // The connection still works when profile lookup is unavailable/offline.
            val name = runCatching { soundcloud.profileName() }.getOrNull()
            _state.update { it.copy(soundcloudName = name, soundcloudConnected = soundcloud.connected) }
        }
    }

    fun disconnectSoundcloud() {
        // Clear local state first; the server-side sign-out is best-effort and only
        // invalidates the exact token captured here, so it cannot race a reconnect.
        val previous = soundcloud.disconnect()
        _state.update {
            it.copy(soundcloudConnected = false, soundcloudName = null, results = emptyList(), searchError = null)
        }
        notify("SoundCloud disconnected on this device.")
        if (previous != null) {
            viewModelScope.launch { soundcloud.revokeAccessToken(previous) }
        }
    }

    // ----- Jellyfin: the user's own server -----

    /**
     * Signs in to the user's Jellyfin server. The address, the username, and the password go
     * only to that server; the password is not stored, and the returned token is encrypted
     * with Android Keystore like the other providers' tokens.
     */
    fun connectJellyfin(server: String, user: String, password: String) {
        if (_state.value.connectingJellyfin) return
        _state.update { it.copy(connectingJellyfin = true) }
        viewModelScope.launch {
            try {
                val name = jellyfin.connect(server, user, password)
                _state.update {
                    it.copy(
                        jellyfinConnected = true,
                        jellyfinServer = jellyfin.serverUrl,
                        jellyfinName = jellyfin.userName.ifBlank { name },
                        jellyfinServerName = jellyfin.serverName
                    )
                }
                notify("Connected to ${jellyfin.serverName.ifBlank { "your Jellyfin server" }} as $name.")
            } catch (e: Exception) {
                notify(e.message ?: "Could not reach your Jellyfin server.")
            } finally {
                _state.update {
                    it.copy(
                        connectingJellyfin = false,
                        jellyfinConnected = jellyfin.connected,
                        jellyfinServer = jellyfin.serverUrl,
                        jellyfinName = jellyfin.userName,
                        jellyfinServerName = jellyfin.serverName
                    )
                }
            }
        }
    }

    fun disconnectJellyfin() {
        // Clear locally first; the sign-out call is best-effort and cannot race a reconnect.
        val previous = jellyfin.disconnect()
        val server = _state.value.jellyfinServer
        hub.stopPlayback()
        _state.update {
            it.copy(
                jellyfinConnected = false,
                jellyfinName = "",
                results = if (it.searchProvider == SearchProvider.JELLYFIN) emptyList() else it.results,
                searchError = null
            )
        }
        notify("Jellyfin disconnected on this device.")
        if (previous != null && server.isNotBlank()) {
            viewModelScope.launch { jellyfin.revokeAccessToken(server, previous) }
        }
    }

    /**
     * Plays one song now: a Jellyfin item reached from somewhere other than a list, or a single
     * track with no list around it. Everything else goes through the queue — see [playPin] and
     * [playPlaylistItemUnified] — so next and previous know where to go.
     */
    fun playJellyfin(link: MusicLink, title: String, artist: String) {
        hub.playEntry(
            QueueEntry(
                mediaId = LibraryMedia.song(link.url),
                title = title.ifBlank { "Jellyfin song" },
                artist = artist,
                provider = link.provider,
                url = link.url
            )
        )
    }

    /**
     * Plays a pinned song with the whole pins list queued around it, so the notification, the
     * lockscreen and the car can step along the trail without going back to the map.
     */
    fun playPin(pin: tech.egrie.soundtrail.data.MusicPin) {
        val pins = _state.value.pins
        hub.play(pins.map { it.toQueueEntry() }, pins.indexOfFirst { it.id == pin.id })
    }

    /** Plays a playlist song in the context of its playlist, preferring an offline copy. */
    fun playPlaylistItemUnified(item: PlaylistItem) {
        val playlist = _state.value.playlists.firstOrNull { list -> list.items.any { it.id == item.id } }
        if (playlist == null) {
            hub.playEntry(item.toQueueEntry())
            return
        }
        hub.playPlaylistItem(item, playlist.items, playlist.name)
    }

    /** Plays a search result; a preview URL the search already resolved is used as it stands. */
    fun playTrackResult(track: TrackResult) {
        hub.playEntry(
            QueueEntry(
                mediaId = LibraryMedia.song(track.url),
                title = track.title,
                artist = track.artist,
                provider = track.provider,
                url = track.url,
                streamUrl = track.playableUrl
            )
        )
    }

    fun togglePlayback() = hub.toggle()

    /** Ends playback and clears the queue behind every surface: bar, notification and car. */
    fun stopPlayback() = hub.stopPlayback()

    fun clearYouTubePlaybackCache() = hub.clearYouTubeCache()

    // ----- Ask: talking to the app, and the answer coming back -----

    /** What was typed or spoken, as it is typed or heard. */
    fun setAssistantInput(text: String) {
        _state.update { it.copy(assistant = it.assistant.copy(input = text.take(400))) }
    }

    /**
     * The activity's report of the microphone: whether it is open, and the words so far. The
     * words are shown while they are still being said, so a misheard request can be seen and
     * stopped rather than discovered after the fact.
     */
    fun setAssistantListening(listening: Boolean, partial: String = "") {
        _state.update {
            it.copy(assistant = it.assistant.copy(listening = listening, partial = partial))
        }
    }

    /** Whether answers are read out loud. Off means the reply is only on screen. */
    fun setAssistantSpeakReplies(enabled: Boolean) {
        _state.update { it.copy(assistant = it.assistant.copy(speakReplies = enabled)) }
    }

    /**
     * Takes a request — typed, or what the microphone heard — and answers it.
     *
     * A description of a feeling goes to the mood reader, which is what that engine is for. A
     * song goes to the listener's own library first, and then — when a Google account is
     * connected — to YouTube's music catalogue, so the request works even for songs that were
     * never saved here. Whatever is found is played through the same single player as everything
     * else, queued in the order it is listed.
     */
    fun ask(text: String = _state.value.assistant.input) {
        if (_state.value.assistant.thinking) return
        val said = text.trim().take(400)
        if (said.isBlank()) {
            answer("Say or type what you would like to hear.", emptyList(), said)
            return
        }
        val request = AssistantParser.parse(said)
        if (request == null) {
            answer("Say or type the song you would like to hear.", emptyList(), said)
            return
        }
        if (request.mood) {
            // The mood engine reads "i feel like something calm" better than a search would, and
            // the mix it builds plays exactly as a mix always has.
            setMoodText(request.query)
            _state.update {
                it.copy(assistant = it.assistant.copy(input = "", question = said, hits = emptyList(), reply = null))
            }
            analyzeMood(announce = true)
            return
        }
        _state.update {
            it.copy(
                assistant = it.assistant.copy(
                    thinking = true, input = "", question = said, reply = null, hits = emptyList()
                )
            )
        }
        viewModelScope.launch {
            val snapshot = _state.value
            val mine = AssistantLibrary.search(request, snapshot.pins, snapshot.playlists)
            // A request that named somewhere else — "the xx on Spotify" — is about that service,
            // not YouTube, so the catalogue is left alone and the library answers on its own.
            val wantsYouTube = request.provider == null ||
                request.provider == MusicProvider.YOUTUBE ||
                request.provider == MusicProvider.YOUTUBE_MUSIC
            val searched = youtubeData.connected && wantsYouTube
            var failure: String? = null
            val found = if (searched) {
                try {
                    // One look at the request as it was understood; a title that a command word
                    // was hiding ("Play That Funky Music") gets a second look as it was said,
                    // but only when the first found nothing at all.
                    var hits = withContext(Dispatchers.IO) { youtubeData.searchCatalogue(request.phrase) }
                    if (hits.isEmpty() && request.spoken.isNotBlank() &&
                        !request.spoken.equals(request.phrase, ignoreCase = true)
                    ) {
                        hits = withContext(Dispatchers.IO) { youtubeData.searchCatalogue(request.spoken) }
                    }
                    hits.map { track ->
                        AssistantHit(
                            title = track.title,
                            artist = track.artist,
                            provider = track.provider,
                            detail = track.provider.label,
                            url = track.url,
                            musicUrl = track.url
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message ?: "YouTube search is unavailable."
                    emptyList()
                }
            } else emptyList()

            val hits = AssistantAnswer.merge(mine, found)
            val playing = AssistantAnswer.playable(hits)
            if (playing.isNotEmpty()) {
                hub.play(playing.map { it.toQueueEntry() }, 0)
            }
            // A search that failed after the library already answered is not worth interrupting
            // for; the songs are the answer.
            val spokenError = failure.takeIf { hits.isEmpty() }
            val sentence = AssistantAnswer.sentence(hits, searched = searched, error = spokenError)
            answer(sentence, hits, said)
        }
    }

    /** Plays one row of an answer, with the rest of the answer queued behind it. */
    fun playAssistantHit(hit: AssistantHit) {
        val hits = _state.value.assistant.hits
        val playable = AssistantAnswer.playable(hits)
        val index = playable.indexOfFirst { it.itemId != null && it.itemId == hit.itemId }
            .takeIf { it >= 0 } ?: playable.indexOfFirst { it.url == hit.url }
        if (playable.isEmpty() || index < 0) {
            hub.playEntry(hit.toQueueEntry())
            return
        }
        hub.play(playable.map { it.toQueueEntry() }, index)
    }

    /** Shows (and, when speech replies are on, says) one answer. */
    private fun answer(sentence: String, hits: List<AssistantHit>, question: String) {
        _state.update {
            it.copy(
                assistant = it.assistant.copy(
                    thinking = false,
                    listening = false,
                    partial = "",
                    reply = sentence,
                    hits = hits,
                    question = question
                )
            )
        }
        if (_state.value.assistant.speakReplies) _speech.tryEmit(AssistantAnswer.spoken(sentence))
    }

    // ----- Mood: how you feel in, music out -----

    fun setMoodText(text: String) {
        val trimmed = text.take(400)
        moodPrefs.lastText = trimmed
        _state.update { it.copy(mood = it.mood.copy(text = trimmed, error = null)) }
    }

    /** 0–100, or null for "let my words decide how much energy I want". */
    fun setMoodEnergyHint(percent: Int?) {
        val bounded = percent?.coerceIn(0, 100)
        moodPrefs.lastEnergyHint = bounded
        _state.update { it.copy(mood = it.mood.copy(energyHint = bounded)) }
    }

    /**
     * Reads the mood and builds a mix from the listener's own library.
     *
     * When a model is switched on — Ollama on the listener's own hardware, or a hosted model with
     * their key — it reads the description; every failure (no network, quota, a refusal,
     * unreadable output) falls back to the on-device engine with a short explanation, so the
     * feature never depends on a model being reachable. A quick-pick mood skips reading entirely.
     */
    fun analyzeMood(quickMoodId: String? = null, announce: Boolean = false) {
        val mood = _state.value.mood
        if (mood.analyzing) return
        val context = moodContext()
        val useModel = mood.aiEnabled && mood.aiProvider != MoodAiProvider.ON_DEVICE && mood.aiReady
        if (mood.aiEnabled && !mood.aiReady && mood.aiProvider != MoodAiProvider.ON_DEVICE) {
            notify(missingProviderSetting(mood.aiProvider))
        }
        _state.update { it.copy(mood = it.mood.copy(analyzing = true, error = null, savedPlaylistName = null)) }
        viewModelScope.launch {
            val profile = try {
                when {
                    quickMoodId != null ->
                        MoodEngine.forMood(quickMoodId, context) ?: MoodEngine.analyze(mood.text, context)
                    useModel -> moodAi.readMood(mood.text, context)
                    else -> MoodEngine.analyze(mood.text, context)
                }
            } catch (e: MoodAiException) {
                notify("${e.message} Reading it on the device instead.")
                MoodEngine.analyze(mood.text, context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MoodEngine.analyze(mood.text, context)
            }
            val candidates = moodCandidates()
            val mix = MoodMixer.build(profile, candidates)
            val untagged = MoodMixer.untagged(candidates).size
            _state.update {
                it.copy(
                    mood = it.mood.copy(
                        analyzing = false,
                        profile = profile,
                        mix = mix,
                        librarySize = candidates.size,
                        untaggedCount = untagged,
                        error = if (mix.isEmpty()) {
                            "Nothing in your library yet — add a song or a link, or search YouTube Music below."
                        } else null,
                        queueIndex = -1,
                        queuePlaying = false
                    )
                )
            }
            // A request that came in by voice is answered out loud, in the same words the card
            // shows; the mix itself is on the Mood tab.
            if (announce) {
                val said = buildString {
                    append("That reads as ${profile.label}.")
                    append(
                        if (mix.isEmpty()) " Nothing in your library fits it yet."
                        else " ${mix.size} song${if (mix.size == 1) "" else "s"} on the Mood tab."
                    )
                }
                answer(said, emptyList(), _state.value.assistant.question)
            }
            // With tag lookups switched on, improve the mix in the background rather than
            // pretending the first pass knows more than it does.
            if (moodPrefs.tagLookupEnabled && untagged > 0) improveMoodMix(quiet = true)
        }
    }

    /** Plays the whole mix in order, through the same queue as everything else. */
    fun playMoodMix() {
        if (_state.value.mood.mix.isEmpty()) {
            notify("Build a mood mix first.")
            return
        }
        playMixFrom(0)
    }

    /** Jumps to one song in the mix; playback continues through the rest of it. */
    fun playMoodTrack(track: MoodMixTrack) {
        val index = _state.value.mood.mix.indexOfFirst { it.key == track.key }.takeIf { it >= 0 } ?: 0
        playMixFrom(index)
    }

    fun stopMoodMix() {
        moodQueueKeys = emptyList()
        hub.stopPlayback()
        _state.update { it.copy(mood = it.mood.copy(queueIndex = -1, queuePlaying = false)) }
    }

    /**
     * Saves the current mix as a playlist called "Mood · <label>". Re-saving the same mood
     * replaces the songs the mix generated and keeps anything added by hand, so the playlist
     * tracks how you feel today instead of growing forever.
     */
    fun saveMoodMixAsPlaylist() {
        if (rejectMutationWhileSyncing()) return
        val mood = _state.value.mood
        val profile = mood.profile
        if (profile == null || mood.mix.isEmpty()) {
            notify("Build a mood mix first.")
            return
        }
        val now = System.currentTimeMillis()
        val name = "Mood \u00b7 ${profile.label}".take(80)
        val existing = _state.value.playlists.firstOrNull { it.name == name }
        val generated = mood.mix.map { track ->
            PlaylistItem(
                id = "mood:${profile.id}:${track.key.hashCode().toUInt().toString(16)}",
                title = track.title,
                artist = track.artist,
                url = track.url,
                provider = track.provider,
                createdAt = now
            )
        }
        val handAdded = existing?.items.orEmpty().filterNot { it.id.startsWith("mood:") }
        val playlist = Playlist(
            id = existing?.id ?: "mood-pl:${profile.id}",
            name = name,
            items = handAdded + generated,
            createdAt = existing?.createdAt ?: now
        )
        val updated = (_state.value.playlists.filterNot { it.id == playlist.id } + playlist)
            .sortedBy { it.createdAt }
        playlists.replaceAll(updated)
        _state.update {
            it.copy(playlists = updated, mood = it.mood.copy(savedPlaylistName = name))
        }
        recordCloudSyncChanges()
        notify("\u201C$name\u201D saved with ${generated.size} songs.")
    }

    /**
     * Learns genre/mood tags for the songs the app knows nothing about, using TheAudioDB's free
     * metadata API (no account, nothing stored about who asked). Capped per run, sequential, and
     * entirely opt-in: the switch in the Mood tab is what decides whether artist and title ever
     * leave the device.
     */
    fun improveMoodMix(quiet: Boolean = false) {
        val mood = _state.value.mood
        if (mood.enriching) return
        val candidates = moodCandidates()
        val targets = MoodMixer.untagged(candidates)
            .filter { it.artist.isNotBlank() && !it.artist.equals("Unknown artist", ignoreCase = true) }
            .take(MAX_MOOD_TAG_LOOKUPS)
        if (targets.isEmpty()) {
            if (!quiet) notify("Every song the mix looked at already has sound tags.")
            return
        }
        _state.update {
            it.copy(mood = it.mood.copy(enriching = true, enrichProgress = 0, enrichTotal = targets.size))
        }
        viewModelScope.launch {
            val learned = mutableMapOf<String, TrackMoodTags.Tags>()
            targets.forEach { candidate ->
                runCatching {
                    val info = audioDb.searchTracks(candidate.artist, candidate.title).firstOrNull()
                    if (info != null) {
                        val tags = TrackMoodTags.fromMetadata(info.genre, info.mood)
                        if (!tags.isEmpty) learned[MoodStore.keyOf(candidate)] = tags
                    }
                }
                _state.update { it.copy(mood = it.mood.copy(enrichProgress = it.mood.enrichProgress + 1)) }
            }
            if (learned.isNotEmpty()) moodPrefs.saveTags(learned)
            _state.update { it.copy(mood = it.mood.copy(enriching = false, enrichEnabled = true)) }
            // Re-score with what was just learned, so the mix visibly improves.
            val profile = _state.value.mood.profile
            if (profile != null) {
                val refreshed = moodCandidates()
                _state.update {
                    it.copy(
                        mood = it.mood.copy(
                            mix = MoodMixer.build(profile, refreshed),
                            untaggedCount = MoodMixer.untagged(refreshed).size,
                            librarySize = refreshed.size
                        )
                    )
                }
            }
            if (!quiet || learned.isNotEmpty()) {
                notify(
                    if (learned.isEmpty()) "No sound tags found for those songs — titles alone still count."
                    else "Learned sound tags for ${learned.size} song${if (learned.size == 1) "" else "s"}."
                )
            }
        }
    }

    fun setMoodTagLookup(enabled: Boolean) {
        moodPrefs.tagLookupEnabled = enabled
        _state.update { it.copy(mood = it.mood.copy(enrichEnabled = enabled)) }
        notify(
            if (enabled) "Tag lookups on: artist and title go to TheAudioDB's free metadata API when a mix is built."
            else "Tag lookups off. Mixes use what is already on this device."
        )
    }

    fun setMoodAiEnabled(enabled: Boolean) {
        moodPrefs.aiEnabled = enabled
        val mood = _state.value.mood
        _state.update { it.copy(mood = it.mood.copy(aiEnabled = enabled, aiNote = null)) }
        notify(
            when {
                !enabled -> "Model reading off. The on-device engine still reads your mood offline."
                mood.aiProvider == MoodAiProvider.ON_DEVICE ->
                    "Already on the on-device engine — nothing leaves the device."
                mood.aiReady -> "${mood.aiProvider.short} reading on. Only your mood description leaves the device."
                else -> missingProviderSetting(mood.aiProvider)
            }
        )
    }

    /**
     * Switches where readings come from. The on-device engine is the default and the fallback, so
     * this is a preference, never a requirement.
     */
    fun setMoodAiProvider(provider: MoodAiProvider) {
        moodPrefs.aiProvider = provider
        _state.update {
            it.copy(
                mood = it.mood.copy(
                    aiProvider = provider,
                    aiReady = moodAi.configured,
                    aiKeySet = moodPrefs.keySet(provider),
                    aiKeySaved = moodPrefs.keySet(provider),
                    aiNote = null,
                    ollamaModels = if (provider == MoodAiProvider.OLLAMA) it.mood.ollamaModels else emptyList(),
                    openAiModels = if (provider == MoodAiProvider.OPENAI_COMPATIBLE) {
                        it.mood.openAiModels
                    } else {
                        emptyList()
                    }
                )
            )
        }
        notify(
            when {
                provider == MoodAiProvider.ON_DEVICE ->
                    "Reading moods on this device: no network, no key, nothing sent."
                provider == MoodAiProvider.OLLAMA && moodAi.configured ->
                    "Ollama selected. Only your mood description goes to that address."
                provider == MoodAiProvider.OLLAMA ->
                    "Add your Ollama server's address below, or press Test to check it."
                provider == MoodAiProvider.OPENAI_COMPATIBLE && moodAi.configured ->
                    "That endpoint is ready. Only your mood description goes to the address you typed."
                provider == MoodAiProvider.OPENAI_COMPATIBLE ->
                    "Add the endpoint's address and the model name below, or press Test to check it."
                moodPrefs.keySet(provider) -> "${provider.short} selected."
                else -> "Paste an API key from ${provider.keyUrl} to use ${provider.short}."
            }
        )
    }

    /** The address of the listener's Ollama server, accepted in whatever form they pasted it. */
    fun setMoodOllamaUrl(url: String) {
        moodPrefs.ollamaUrl = url
        val normalized = MoodAiUrls.normalize(url)
        _state.update {
            it.copy(
                mood = it.mood.copy(
                    ollamaUrl = moodPrefs.ollamaUrl,
                    aiReady = moodAi.configured,
                    aiNote = when {
                        url.isBlank() -> null
                        normalized == null -> "That does not look like an address. Try http://192.168.0.20:11434"
                        else -> "Using ${MoodAiUrls.display(normalized)}"
                    }
                )
            )
        }
    }

    fun setMoodOllamaModel(name: String) {
        moodPrefs.ollamaModel = name
        _state.update { it.copy(mood = it.mood.copy(ollamaModel = moodPrefs.ollamaModel, aiNote = null)) }
    }

    /**
     * The address of an OpenAI-compatible endpoint, accepted in whatever form it was pasted —
     * a bare host, a whole `…/v1/chat/completions` from a README, or a LAN address with no scheme.
     */
    fun setMoodOpenAiUrl(url: String) {
        moodPrefs.openAiUrl = url
        val normalized = MoodAiUrls.normalizeOpenAi(url)
        _state.update {
            it.copy(
                mood = it.mood.copy(
                    openAiUrl = moodPrefs.openAiUrl,
                    aiReady = moodAi.configured,
                    aiNote = when {
                        url.isBlank() -> null
                        normalized == null ->
                            "That does not look like an address. Try https://api.openai.com/v1"
                        else -> "Using ${MoodAiUrls.display(normalized)}"
                    }
                )
            )
        }
    }

    fun setMoodOpenAiModel(name: String) {
        moodPrefs.openAiModel = name
        _state.update {
            it.copy(
                mood = it.mood.copy(
                    openAiModel = moodPrefs.openAiModel,
                    aiReady = moodAi.configured,
                    aiNote = null
                )
            )
        }
    }

    /**
     * Fills in Arena's gateway. Arena publishes an OpenAI-compatible API, so it needs no provider
     * of its own — only the address and the router alias its own portal documents. The key still
     * has to come from the listener: it is created on Arena's Keys page and pasted into the field
     * below, then **Find models** lists whatever that key is enabled for.
     */
    fun useMoodArenaPreset() {
        moodPrefs.aiProvider = MoodAiProvider.OPENAI_COMPATIBLE
        moodPrefs.openAiUrl = MoodAiUrls.ARENA_BASE_URL
        moodPrefs.openAiModel = MoodAiUrls.ARENA_MODEL
        _state.update {
            it.copy(
                mood = it.mood.copy(
                    aiProvider = MoodAiProvider.OPENAI_COMPATIBLE,
                    openAiUrl = moodPrefs.openAiUrl,
                    openAiModel = moodPrefs.openAiModel,
                    aiKeySet = moodPrefs.keySet(MoodAiProvider.OPENAI_COMPATIBLE),
                    aiKeySaved = moodPrefs.keySet(MoodAiProvider.OPENAI_COMPATIBLE),
                    ollamaModels = emptyList(),
                    openAiModels = emptyList(),
                    aiReady = moodAi.configured,
                    aiNote = "Arena's gateway is filled in. Paste a key from the Keys page below, " +
                        "then press Test."
                )
            )
        }
        notify("Arena (preview) filled in. Create a key on Arena's Keys page, paste it below, then press Test.")
    }

    /**
     * Asks the selected endpoint which models it has, so the listener picks instead of guessing:
     * the tags an Ollama server has pulled, or the ids an OpenAI-compatible endpoint lists. A
     * server that publishes no list is told apart from one that cannot be reached, because the
     * first case still works with a model name typed by hand.
     */
    fun detectMoodModels() {
        val mood = _state.value.mood
        if (mood.aiBusy) return
        val openAi = mood.aiProvider == MoodAiProvider.OPENAI_COMPATIBLE
        if (openAi) {
            if (MoodAiUrls.normalizeOpenAi(mood.openAiUrl) == null) {
                notify("Add the endpoint's address first, for example https://api.openai.com/v1.")
                return
            }
        } else if (MoodAiUrls.normalize(mood.ollamaUrl) == null) {
            notify("Add your Ollama server's address first, for example http://192.168.0.20:11434.")
            return
        }
        if (!openAi) moodPrefs.aiProvider = MoodAiProvider.OLLAMA
        _state.update {
            it.copy(
                mood = it.mood.copy(
                    aiProvider = moodPrefs.aiProvider,
                    aiBusy = true,
                    aiNote = null
                )
            )
        }
        viewModelScope.launch {
            runCatching { moodAi.listModels() }
                .onSuccess { models ->
                    val before = _state.value.mood
                    val adapted = modelAfterList(before, models)
                    val swapped = openAi && adapted.isNotBlank() && adapted != before.openAiModel
                    if (swapped) moodPrefs.openAiModel = adapted
                    _state.update {
                        it.copy(
                            mood = it.mood.copy(
                                aiBusy = false,
                                ollamaModels = if (openAi) it.mood.ollamaModels else models,
                                openAiModels = if (openAi) models else it.mood.openAiModels,
                                openAiModel = if (openAi) moodPrefs.openAiModel else it.mood.openAiModel,
                                aiNote = when {
                                    swapped ->
                                        "Arena: \u201C${before.openAiModel}\u201D is not enabled for " +
                                            "this key, so \u201C$adapted\u201D is selected — the same " +
                                            "choice Arena's own setup script makes."
                                    models.isEmpty() && openAi ->
                                        "That endpoint answered but lists no models. Type the name " +
                                            "of the one you want — the app will still use it."
                                    models.isEmpty() ->
                                        "That server answered but has no models yet. Run: ollama pull ${it.mood.ollamaModel}"
                                    else ->
                                        "${models.size} model${if (models.size == 1) "" else "s"} found — tap one to use it."
                                },
                                aiReady = moodAi.configured
                            )
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(mood = it.mood.copy(aiBusy = false, aiNote = error.message)) }
                    notify(
                        error.message ?: if (openAi) {
                            "That endpoint could not be reached."
                        } else {
                            "That Ollama server could not be reached."
                        }
                    )
                }
        }
    }

    /**
     * The model to keep once a list has arrived. A model already in the list is kept as it is, and
     * Ollama's tags are left alone — they were typed or tapped deliberately. Arena is the one case
     * that changes on its own: a key may have any of Arena's router aliases enabled, and Arena's
     * own setup script picks whichever the key actually has, so the app picks the same one rather
     * than leaving an alias that would fail the first real request.
     */
    private fun modelAfterList(mood: MoodUiState, models: List<String>): String {
        if (models.isEmpty() || models.any { it == mood.openAiModel }) return mood.openAiModel
        if (mood.aiProvider != MoodAiProvider.OPENAI_COMPATIBLE) return mood.openAiModel
        if (!MoodAiUrls.isArena(MoodAiUrls.normalizeOpenAi(mood.openAiUrl))) return mood.openAiModel
        // A name the listener typed themselves is theirs to keep; only the preset alias is Arena's
        // to move, and it is moved only when the key turned out not to have it.
        val presetAlias = MoodAiUrls.ARENA_ROUTER_MODELS.any { it == mood.openAiModel }
        if (mood.openAiModel.isNotBlank() && !presetAlias) return mood.openAiModel
        return MoodAiUrls.arenaRouterFrom(models) ?: mood.openAiModel
    }

    /** Probes the selected provider and reports exactly what happened. */
    fun testMoodAi() {
        val mood = _state.value.mood
        if (mood.aiBusy) return
        if (!moodAi.configured) {
            notify(missingProviderSetting(mood.aiProvider))
            return
        }
        _state.update { it.copy(mood = it.mood.copy(aiBusy = true, aiNote = null)) }
        viewModelScope.launch {
            runCatching { moodAi.testConnection() }
                .onSuccess { note ->
                    _state.update { it.copy(mood = it.mood.copy(aiBusy = false, aiNote = note)) }
                    notify(note)
                }
                .onFailure { error ->
                    val message = error.message ?: "That model could not be reached."
                    _state.update { it.copy(mood = it.mood.copy(aiBusy = false, aiNote = message)) }
                    notify(message)
                }
        }
    }

    /** Saves the credential for whichever provider is selected (Ollama's is optional). */
    fun saveMoodAiKey(key: String) {
        val provider = _state.value.mood.aiProvider
        if (provider == MoodAiProvider.ON_DEVICE) {
            notify("The on-device engine needs no key.")
            return
        }
        moodPrefs.saveKey(provider, key)
        val saved = moodPrefs.keySet(provider)
        _state.update {
            it.copy(
                mood = it.mood.copy(
                    aiKeySet = saved,
                    aiKeySaved = saved,
                    aiReady = moodAi.configured,
                    aiNote = when {
                        !saved -> null
                        provider == MoodAiProvider.OLLAMA ->
                            "Token saved. It is sent only to that address, as a bearer token."
                        provider == MoodAiProvider.OPENAI_COMPATIBLE ->
                            "Key saved. It is sent only to that address, as a bearer token."
                        else -> null
                    }
                )
            )
        }
        if (saved) {
            notify("Saved, encrypted with this device's key store.")
            if (provider == MoodAiProvider.GEMINI || provider == MoodAiProvider.OPENAI_COMPATIBLE) {
                testMoodAi()
            }
        } else {
            notify("Key cleared. The on-device engine reads your mood.")
        }
    }

    fun setMoodAiModel(model: String) {
        moodPrefs.aiModel = model
        _state.update { it.copy(mood = it.mood.copy(aiModel = moodPrefs.aiModel)) }
    }

    fun clearMoodAiKey() {
        val provider = _state.value.mood.aiProvider
        moodPrefs.clearKey(provider)
        _state.update {
            it.copy(
                mood = it.mood.copy(
                    aiKeySet = false,
                    aiKeySaved = false,
                    aiReady = moodAi.configured,
                    aiNote = null
                )
            )
        }
        notify("Key removed from this device.")
    }

    /** What to tell someone who switched a model on before finishing its setup. */
    private fun missingProviderSetting(provider: MoodAiProvider): String = when (provider) {
        MoodAiProvider.ON_DEVICE -> "The on-device engine needs nothing."
        MoodAiProvider.OLLAMA ->
            "Add your Ollama server's address (for example http://192.168.0.20:11434). " +
                "Reading on-device until then."
        MoodAiProvider.OPENAI_COMPATIBLE -> when {
            MoodAiUrls.normalizeOpenAi(moodPrefs.openAiUrl) == null ->
                "Add the endpoint's address (for example https://api.openai.com/v1). Reading " +
                    "on-device until then."
            moodPrefs.openAiModel.isBlank() ->
                "Name the model to ask for at that endpoint. Reading on-device until then."
            else ->
                "That endpoint is set up; test it from the Mood tab. Reading on-device until then."
        }
        MoodAiProvider.GEMINI ->
            "Paste a Google AI Studio API key to use ${provider.short}. Reading on-device until then."
    }

    /** Hands the current mood's search phrases to YouTube Music; never needs a Google sign-in. */
    fun openMoodInYouTubeMusic(): Boolean {
        val profile = _state.value.mood.profile
        val query = profile?.query.orEmpty().ifBlank { _state.value.mood.text }
        return apps.searchYouTubeMusicForMood(query)
    }

    /** Every song the mood engine can choose from: pins first, then each playlist. */
    private fun moodCandidates(): List<MoodCandidate> {
        val seen = HashSet<String>()
        val candidates = mutableListOf<MoodCandidate>()
        fun add(id: String, title: String, artist: String, url: String?, origin: String, createdAt: Long) {
            if (title.isBlank()) return
            val cacheKey = url ?: "text:" + MoodEngine.normalize("$title $artist")
            if (!seen.add(cacheKey)) return
            val learned = moodPrefs.tags(cacheKey)
            candidates += MoodCandidate(
                id = id,
                title = title,
                artist = artist,
                url = url,
                provider = url?.let { MusicLinkParser.parse(it)?.provider },
                origin = origin,
                tags = learned?.tags.orEmpty(),
                valence = learned?.valence,
                energy = learned?.energy,
                tempo = learned?.tempo,
                tagSource = learned?.origin,
                createdAt = createdAt
            )
        }
        _state.value.pins.forEach { pin ->
            add(pin.id, pin.title, pin.artist, pin.url, "Pin", pin.createdAt)
        }
        _state.value.playlists.forEach { playlist ->
            playlist.items.forEach { item ->
                add(item.id, item.title, item.artist, item.url, playlist.name, item.createdAt)
            }
        }
        return candidates
    }

    /** Time of day and (when the watch is on) motion, for the mood engine's gentle tilts. */
    private fun moodContext(): MoodContext {
        val watch = LocationWatchService.status.value
        return MoodContext(
            hourOfDay = runCatching { java.time.LocalTime.now().hour }.getOrNull(),
            motion = if (watch.running) watch.motion.label else null,
            energyHint = _state.value.mood.energyHint?.let { it / 100.0 }
        )
    }

    /**
     * Hands the mix to the hub as a queue, starting at [index]. The hub skips anything that
     * cannot play here and moves to the next song when one ends, so a mix keeps going in the
     * car exactly as it does on the phone.
     */
    private fun playMixFrom(index: Int) {
        val entries = _state.value.mood.mix.map { it.toQueueEntry() }
        moodQueueKeys = entries.map { it.mediaId }
        hub.play(entries, index)
    }

    /** A link to the item in Jellyfin's own web client, for sharing with people on that server. */
    fun jellyfinWebUrl(link: MusicLink): String? {
        val itemId = JellyfinRef.itemId(link.url) ?: return null
        if (!jellyfin.connected) return null
        return JellyfinRef.webUrl(jellyfin.serverUrl, itemId)
    }

    /** The server's own web interface, for opening in a browser. */
    fun jellyfinServerUrl(): String = jellyfin.serverUrl

    /** Connects a Google account that the user finished signing in to in the Google sign-in screen. */
    fun connectYouTubeData(account: GoogleSignInAccount) {
        val googleAccount = account.account
        if (googleAccount == null) {
            notify("Google Sign-In did not return an account. Try again.")
            return
        }
        runYouTubeConnect(onConsentNeeded = null) {
            youtubeData.connect(googleAccount, account.email ?: account.displayName)
        }
    }

    /**
     * Connects the microG (or device) Google account without a picker. If Google still needs the
     * read-only consent, [onConsentNeeded] runs so the caller can open the interactive sign-in.
     */
    fun connectMicroGAccount(account: Account, onConsentNeeded: () -> Unit) {
        runYouTubeConnect(onConsentNeeded) {
            youtubeData.connect(account, null)
        }
    }

    private fun runYouTubeConnect(
        onConsentNeeded: (() -> Unit)?,
        connect: suspend () -> String
    ) {
        if (_state.value.connectingYouTubeData) return
        _state.update { it.copy(connectingYouTubeData = true, youtubeDataConnected = false) }
        viewModelScope.launch {
            try {
                val accountName = connect()
                _state.update {
                    it.copy(
                        youtubeDataConnected = true,
                        youtubeDataAccount = accountName,
                        youtubeAutoImportEnabled = youtubePrefs.getBoolean("auto_import", true),
                        youtubeLastImportedAt = youtubePrefs.getLong("last_imported_at", 0L)
                    )
                }
                notify("YouTube read-only access connected. Playlists will import automatically if that option is on.")
                maybeAutoImportYouTube()
            } catch (e: YouTubeConsentRequiredException) {
                _state.update { it.copy(youtubeDataConnected = false, youtubeDataAccount = null) }
                if (onConsentNeeded != null) {
                    onConsentNeeded()
                } else {
                    notify(e.message ?: "Approve the YouTube read-only permission, then connect again.")
                }
            } catch (e: Exception) {
                _state.update { it.copy(youtubeDataConnected = false, youtubeDataAccount = null) }
                notify(e.message ?: "Could not connect your YouTube account.")
            } finally {
                _state.update {
                    it.copy(
                        connectingYouTubeData = false,
                        youtubeDataConnected = youtubeData.connected,
                        youtubeDataAccount = youtubeData.accountName,
                        youtubeAuth = youtubeData.authHealth()
                    )
                }
            }
        }
    }

    /**
     * Starts the browser OAuth flow with the user's own Desktop client ID (no GMS needed).
     * A blank [clientId] means "reconnect with the Client ID this device already remembers",
     * which is what the Connect tab's one-tap reconnect uses — the ID is never re-typed.
     */
    fun beginYouTubeOAuth(clientId: String): Uri {
        val uri = youtubeData.beginOAuthSignIn(clientId)
        _state.update { it.copy(youtubeOAuthClientId = youtubeData.oauthClientId) }
        return uri
    }

    fun cancelYouTubeOAuth() = youtubeData.cancelOAuthSignIn()

    /** Finishes the browser OAuth flow after Google redirects back to the app. */
    fun finishYouTubeOAuth(uri: Uri) {
        if (_state.value.connectingYouTubeOAuth) return
        _state.update { it.copy(connectingYouTubeOAuth = true) }
        viewModelScope.launch {
            try {
                val label = youtubeData.finishOAuthSignIn(uri)
                _state.update {
                    it.copy(
                        youtubeDataConnected = true,
                        youtubeDataAccount = label,
                        youtubeOAuthClientId = youtubeData.oauthClientId,
                        youtubeAuth = youtubeData.authHealth(),
                        youtubeAutoImportEnabled = youtubePrefs.getBoolean("auto_import", true),
                        youtubeLastImportedAt = youtubePrefs.getLong("last_imported_at", 0L)
                    )
                }
                notify("YouTube read-only access connected. Playlists will import automatically if that option is on.")
                maybeAutoImportYouTube()
            } catch (e: YouTubeReconnectRequiredException) {
                notify(e.message ?: "Google no longer accepts this connection. Reconnect to continue.")
                _state.update {
                    it.copy(
                        youtubeDataConnected = youtubeData.connected,
                        youtubeDataAccount = youtubeData.accountName,
                        youtubeOAuthClientId = youtubeData.oauthClientId,
                        youtubeAuth = youtubeData.authHealth()
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        youtubeDataConnected = youtubeData.connected,
                        youtubeDataAccount = youtubeData.accountName,
                        youtubeOAuthClientId = youtubeData.oauthClientId
                    )
                }
                notify(e.message ?: "Could not connect your YouTube account.")
            } finally {
                _state.update {
                    it.copy(
                        connectingYouTubeOAuth = false,
                        youtubeDataConnected = youtubeData.connected,
                        youtubeDataAccount = youtubeData.accountName,
                        youtubeOAuthClientId = youtubeData.oauthClientId,
                        youtubeAuth = youtubeData.authHealth()
                    )
                }
            }
        }
    }

    fun disconnectYouTubeData() {
        // Clear local state first; the server-side revocation is best-effort and only
        // invalidates the exact token captured here, so it cannot race a reconnect.
        val previousOAuth = youtubeData.disconnect()
        _state.update {
            it.copy(
                youtubeDataConnected = false,
                youtubeDataAccount = null,
                youtubeImporting = false,
                youtubeAuth = youtubeData.authHealth(),
                results = if (it.searchProvider == SearchProvider.YOUTUBE_DATA) emptyList() else it.results,
                searchError = null
            )
        }
        notify("YouTube access disconnected on this device; Google was asked to revoke the grant.")
        if (previousOAuth != null) {
            viewModelScope.launch { youtubeData.revokeOAuthToken(previousOAuth) }
        }
    }

    fun setYouTubeAutoImport(enabled: Boolean) {
        youtubePrefs.edit().putBoolean("auto_import", enabled).apply()
        _state.update { it.copy(youtubeAutoImportEnabled = enabled) }
    }

    /**
     * Pulls playlists and their videos from the connected YouTube account and merges them into
     * Soundtrail playlists. Deterministic ids (yt-pl:<playlistId> and yt:<playlistId>:<videoId>)
     * keep re-imports idempotent: previously imported playlists keep existing items (including
     * offline files) and only newly discovered videos are appended. YouTube Music library-only
     * collections (saved songs/albums/mixes) are not reachable via the public Data API and are
     * therefore not imported; the notice text makes that clear.
     */
    fun importYouTubePlaylists() = runYouTubeImport(showMessages = true)

    private fun runYouTubeImport(showMessages: Boolean) {
        if (_state.value.youtubeImporting) return
        if (!youtubeData.connected) {
            if (showMessages) notify("Connect your Google account before importing YouTube playlists.")
            return
        }
        _state.update { it.copy(youtubeImporting = true) }
        viewModelScope.launch {
            try {
                val imported: List<YouTubeImportedPlaylist> = withContext(Dispatchers.IO) { youtubeData.importPlaylists() }
                val now = System.currentTimeMillis()
                // One list for likes, whichever account they came from: an import goes into the
                // liked list that already exists (whatever it is called) rather than creating a
                // second one beside it.
                val existingLikes = playlists.all()
                    .firstOrNull { LikedSongs.isLikedName(it.name) && !it.id.startsWith("yt-pl:") }
                    ?: playlists.all().firstOrNull { LikedSongs.isLikedName(it.name) }
                val incoming = imported.map { yt ->
                    val isLikes = yt.title == "Liked videos"
                    val distinctName = when {
                        isLikes -> LikedSongs.NAME
                        yt.title == "Uploads" -> "YouTube Uploads"
                        yt.title == "Favorites" -> "YouTube Favorites"
                        else -> yt.title
                    }
                    Playlist(
                        id = if (isLikes) existingLikes?.id ?: "yt-pl:${yt.playlistId}" else "yt-pl:${yt.playlistId}",
                        name = distinctName,
                        items = yt.items.map { video ->
                            PlaylistItem(
                                id = "yt:${yt.playlistId}:${video.videoId}",
                                title = video.title,
                                artist = video.artist,
                                url = "https://www.youtube.com/watch?v=${video.videoId}",
                                provider = MusicProvider.YOUTUBE,
                                createdAt = now
                            )
                        },
                        createdAt = now
                    )
                }
                val (addedLists, addedItems) = withContext(Dispatchers.IO) { playlists.merge(incoming) }
                // One list holds likes: a list an earlier import made is folded into the
                // listener's own (or into the newest import's), and a name the app chose is
                // renamed to what the list holds. Every row keeps the provider it came from.
                runCatching {
                    val lists = playlists.all()
                    val target = lists.firstOrNull { LikedSongs.isLikedName(it.name) }
                    if (target != null) {
                        val (consolidated, moved) = consolidateLikes(lists, target.id)
                        val renamed = consolidated.map {
                            if (it.id == target.id && LikedSongs.isImportedName(it.name)) {
                                it.copy(name = LikedSongs.NAME)
                            } else {
                                it
                            }
                        }
                        val changed = moved > 0 || renamed != lists
                        if (changed) playlists.replaceAll(renamed)
                    }
                }
                youtubePrefs.edit().putLong("last_imported_at", now).apply()
                refreshOfflineAudioState()
                recordCloudSyncChanges()
                _state.update {
                    it.copy(
                        playlists = playlists.all(),
                        youtubeLastImportedAt = now,
                        youtubeImporting = false
                    )
                }
                if (showMessages) {
                    val note = buildString {
                        append("Imported ${incoming.size} YouTube playlist")
                        if (incoming.size != 1) append('s')
                        append(" — ${addedItems.coerceAtLeast(0)} new song")
                        if (addedItems != 1) append('s')
                        append(" added.")
                    }
                    notify(note)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(youtubeImporting = false) }
                if (showMessages) notify(e.message ?: "Could not import YouTube playlists.")
            }
        }
    }

    /** Called after a successful YouTube connect to optionally start an automatic import. */
    private fun maybeAutoImportYouTube() {
        if (youtubePrefs.getBoolean("auto_import", true)) runYouTubeImport(showMessages = true)
    }

    fun selectSearchProvider(provider: SearchProvider) {
        if (_state.value.searchProvider == provider) return
        _state.update { it.copy(searchProvider = provider, results = emptyList(), searchError = null) }
    }

    fun search(query: String) {
        if (_state.value.searching) return
        if (query.isBlank()) {
            _state.update { it.copy(results = emptyList(), searchError = null) }
            return
        }
        val provider = _state.value.searchProvider
        _state.update { it.copy(searching = true, results = emptyList(), searchError = null) }
        viewModelScope.launch {
            try {
                val tracks = when (provider) {
                    SearchProvider.SPOTIFY -> spotify.searchTracks(query)
                    SearchProvider.SOUNDCLOUD -> soundcloud.searchTracks(query)
                    SearchProvider.JELLYFIN -> jellyfin.searchTracks(query)
                    SearchProvider.YOUTUBE_DATA -> youtubeData.searchTracks(query)
                }
                _state.update { it.copy(results = tracks) }
            } catch (e: Exception) {
                _state.update { it.copy(searchError = e.message ?: "Search is unavailable.") }
            } finally {
                _state.update {
                    it.copy(
                        searching = false,
                        spotifyConnected = spotify.connected,
                        soundcloudConnected = soundcloud.connected,
                        jellyfinConnected = jellyfin.connected,
                        youtubeDataConnected = youtubeData.connected,
                        youtubeDataAccount = youtubeData.accountName
                    )
                }
            }
        }
    }

    fun lookupSong(artist: String, title: String) {
        if (_state.value.lookupSearching) return
        if (artist.isBlank() || title.isBlank()) {
            _state.update { it.copy(lookupError = "Enter both artist and song title.") }
            return
        }
        _state.update { it.copy(lookupSearching = true, lookupResults = emptyList(), lookupError = null) }
        viewModelScope.launch {
            try {
                val songs = audioDb.searchTracks(artist, title)
                _state.update { it.copy(lookupResults = songs) }
            } catch (e: Exception) {
                _state.update { it.copy(lookupError = e.message ?: "Song lookup is unavailable.") }
            } finally {
                _state.update { it.copy(lookupSearching = false) }
            }
        }
    }

    private fun rejectMutationWhileSyncing(): Boolean {
        val current = _state.value
        when {
            current.syncBusy -> notify("Wait for cloud sync to finish before editing synced items.")
            current.offlineAudioBusy -> notify("Wait for offline storage management to finish first.")
            current.backupBusy -> notify("Wait for the current backup operation to finish.")
            else -> return false
        }
        return true
    }

    // ----- Playlists -----

    fun createPlaylist(rawName: String) {
        if (rejectMutationWhileSyncing()) return
        val name = rawName.trim().take(80)
        val current = _state.value.playlists
        when {
            name.isEmpty() -> notify("Give the playlist a name first.")
            current.any { it.name.equals(name, ignoreCase = true) } ->
                notify("A playlist called \"$name\" already exists.")
            else -> {
                val playlist = Playlist(
                    id = UUID.randomUUID().toString(), name = name,
                    items = emptyList(), createdAt = System.currentTimeMillis()
                )
                val updated = (current + playlist).sortedBy { it.createdAt }
                playlists.replaceAll(updated)
                _state.update { it.copy(playlists = updated) }
                recordCloudSyncChanges()
                notify("Playlist \"$name\" created.")
            }
        }
    }

    fun deletePlaylist(id: String) {
        if (rejectMutationWhileSyncing()) return
        val currentPlaylists = _state.value.playlists
        // Imported audio belongs to the song; it leaves with the playlist.
        currentPlaylists.firstOrNull { it.id == id }?.items
            ?.forEach { item -> item.localFile?.let(files::delete) }
        val updatedPlaylists = currentPlaylists.filterNot { it.id == id }
        playlists.replaceAll(updatedPlaylists)

        // Rules pointing at a deleted playlist would never fire again.
        val currentRules = _state.value.automations
        val updatedRules = currentRules.filterNot { it.playlistId == id }
        automations.replaceAll(updatedRules)
        _state.update { it.copy(playlists = updatedPlaylists, automations = updatedRules) }
        refreshOfflineAudioState()
        recordCloudSyncChanges()
        notify("Playlist removed.")
    }

    /**
     * Adds a song to a playlist. All music now plays directly in-app, so no external search URL is returned.
     * YouTube offline audio remains a separate, rights-confirmed action through the self-hosted API.
     */
    fun addToPlaylist(playlistId: String, rawTitle: String, rawArtist: String, rawUrl: String) {
        if (rejectMutationWhileSyncing()) return
        val title = rawTitle.trim().take(120)
        val artist = rawArtist.trim().take(120)
        val link = rawUrl.trim().takeIf(String::isNotBlank)?.let(MusicLinkParser::fromText)
        val current = _state.value.playlists
        val playlist = current.firstOrNull { it.id == playlistId }
        when {
            title.isEmpty() -> { notify("Add a song title first."); return }
            playlist == null -> { notify("That playlist no longer exists."); return }
            rawUrl.trim().isNotBlank() && link == null -> {
                notify("That link is not a supported song URL. Leave it empty or add a valid Spotify, SoundCloud, YouTube Music, or Jellyfin link.")
                return
            }
        }
        val item = PlaylistItem(
            id = UUID.randomUUID().toString(), title = title, artist = artist,
            url = link?.url, provider = link?.provider, createdAt = System.currentTimeMillis()
        )
        val updated = current.map {
            if (it.id == playlistId) it.copy(items = it.items + item) else it
        }
        playlists.replaceAll(updated)
        _state.update { it.copy(playlists = updated) }
        recordCloudSyncChanges()
        notify("Added \"$title\" to ${playlist.name}. It will play directly in Soundtrail.")
    }

    fun removePlaylistItem(playlistId: String, itemId: String) {
        if (rejectMutationWhileSyncing()) return
        val current = _state.value.playlists
        val playlist = current.firstOrNull { it.id == playlistId }
        playlist?.items?.firstOrNull { it.id == itemId }?.localFile?.let(files::delete)
        if (playlist != null) {
            val updated = current.map {
                if (it.id == playlistId) {
                    it.copy(items = it.items.filterNot { item -> item.id == itemId })
                } else it
            }
            playlists.replaceAll(updated)
            _state.update { it.copy(playlists = updated) }
        }
        refreshOfflineAudioState()
        recordCloudSyncChanges()
        notify("Song removed.")
    }

    /** Downloads only the selected Jellyfin server item the signed-in user is permitted to access. */
    fun downloadJellyfinOffline(playlistId: String, itemId: String) {
        if (rejectMutationWhileSyncing()) return
        val key = "$playlistId:$itemId"
        if (_state.value.jellyfinDownloads.containsKey(key)) return
        if (!jellyfin.connected) {
            notify("Connect your Jellyfin server before downloading offline.")
            return
        }
        val item = playlists.find(playlistId)?.items?.firstOrNull { it.id == itemId }
        if (item == null) {
            notify("That playlist song no longer exists.")
            return
        }
        if (item.localFile?.let(files::exists) == true) {
            notify("That song is already available offline on this device.")
            return
        }
        val serverItemId = item.url?.let(JellyfinRef::itemId)
        if (serverItemId == null) {
            notify("Offline download is available for tracks added from your Jellyfin library.")
            return
        }
        _state.update {
            it.copy(jellyfinDownloads = it.jellyfinDownloads + (key to JellyfinDownloadProgress(0L, 0L)))
        }
        viewModelScope.launch {
            var temp: java.io.File? = null
            var downloadedName: String? = null
            var attached = false
            try {
                val staging = withContext(Dispatchers.IO) { files.newDownloadTemp() }
                temp = staging
                val info = jellyfin.downloadAudio(serverItemId, staging) { received, total ->
                    _state.update {
                        it.copy(jellyfinDownloads = it.jellyfinDownloads + (key to JellyfinDownloadProgress(received, total)))
                    }
                }
                downloadedName = withContext(Dispatchers.IO) {
                    files.finishDownload(staging, info.contentType, info.filename)
                } ?: throw IllegalStateException("Couldn't save that audio file to this device.")
                val latest = playlists.find(playlistId)?.items?.firstOrNull { it.id == itemId }
                if (latest == null) throw IllegalStateException("That playlist song was removed during the download.")
                attached = playlists.replaceItem(playlistId, itemId, latest.copy(localFile = downloadedName))
                if (!attached) throw IllegalStateException("Couldn't attach the downloaded file to its song.")
                refreshOfflineAudioState()
                val mebibytes = info.bytes / (1024L * 1024L)
                notify("${item.title} downloaded for offline playback from your Jellyfin server (${mebibytes} MiB).")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(jellyfinConnected = jellyfin.connected) }
                notify(e.message ?: "Jellyfin offline download failed.")
            } finally {
                temp?.let(files::discardDownloadTemp)
                if (!attached) downloadedName?.let(files::delete)
                _state.update { it.copy(jellyfinDownloads = it.jellyfinDownloads - key) }
            }
        }
    }

    /** Requests audio-only extraction for a YouTube item after the UI's explicit rights confirmation. */
    fun downloadYouTubeOffline(playlistId: String, itemId: String) {
        if (rejectMutationWhileSyncing()) return
        val key = "$playlistId:$itemId"
        if (_state.value.youtubeDownloads.containsKey(key)) return
        if (!cloudSync.connected) {
            notify("Sign in to your sync server to use its YouTube audio extractor.")
            return
        }
        val item = playlists.find(playlistId)?.items?.firstOrNull { it.id == itemId }
        if (item == null) {
            notify("That playlist song no longer exists.")
            return
        }
        if (item.localFile?.let(files::exists) == true) {
            notify("That song is already available offline on this device.")
            return
        }
        val videoId = item.url?.let(MusicLinkParser::youtubeVideoId)
        if (videoId == null) {
            notify("Audio extraction is available for supported YouTube video links only.")
            return
        }
        _state.update {
            it.copy(youtubeDownloads = it.youtubeDownloads + (key to YouTubeDownloadProgress(YouTubeAudioStage.PREPARING, 0L, 0L)))
        }
        viewModelScope.launch {
            var temp: java.io.File? = null
            var downloadedName: String? = null
            var attached = false
            try {
                val staging = withContext(Dispatchers.IO) { files.newDownloadTemp() }
                temp = staging
                val info = cloudSync.downloadYouTubeAudio(videoId, staging, userConfirmedRights = true) { stage, received, total ->
                    _state.update {
                        it.copy(youtubeDownloads = it.youtubeDownloads +
                            (key to YouTubeDownloadProgress(stage, received, total)))
                    }
                }
                downloadedName = withContext(Dispatchers.IO) {
                    files.finishDownload(staging, info.contentType, info.filename)
                } ?: throw IllegalStateException("Couldn't save that audio file to this device.")
                val latest = playlists.find(playlistId)?.items?.firstOrNull { it.id == itemId }
                if (latest == null) throw IllegalStateException("That playlist song was removed during the extraction.")
                attached = playlists.replaceItem(playlistId, itemId, latest.copy(localFile = downloadedName))
                if (!attached) throw IllegalStateException("Couldn't attach the extracted audio to its song.")
                refreshOfflineAudioState()
                val mebibytes = info.bytes / (1024L * 1024L)
                notify("${item.title} extracted as audio for offline playback (${mebibytes} MiB).")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notify(e.message ?: "YouTube audio extraction failed.")
            } finally {
                temp?.let(files::discardDownloadTemp)
                if (!attached) downloadedName?.let(files::delete)
                _state.update { it.copy(youtubeDownloads = it.youtubeDownloads - key) }
            }
        }
    }

    /** Attaches a user-picked audio file (their own download/import) to a playlist song. */
    fun attachLocalAudio(uri: Uri, playlistId: String, itemId: String) {
        if (rejectOfflineAudioManagement()) return
        _state.update { it.copy(offlineAudioBusy = true) }
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { files.importAudio(uri) }
                if (name == null) {
                    notify("Couldn't read that audio file.")
                    return@launch
                }
                val item = playlists.find(playlistId)?.items?.firstOrNull { it.id == itemId }
                if (item == null) {
                    files.delete(name)
                    notify("That song no longer exists.")
                    return@launch
                }
                val replaced = playlists.replaceItem(playlistId, itemId, item.copy(localFile = name))
                if (replaced) item.localFile?.takeIf { it != name }?.let(files::delete) else files.delete(name)
                refreshOfflineAudioState()
                notify(if (replaced) "Audio attached — \"${item.title}\" now plays from this device." else "Couldn't attach the audio file.")
            } catch (e: Exception) {
                notify(e.message ?: "Couldn't attach that audio file.")
            } finally {
                _state.update { it.copy(offlineAudioBusy = false) }
            }

        }
    }

    fun setAutoSearchYoutube(enabled: Boolean) {
        _state.update { it.copy(autoSearchYoutube = enabled) }
    }

    fun removeOfflineAudio(playlistId: String, itemId: String) {
        if (rejectMutationWhileSyncing()) return
        val item = playlists.find(playlistId)?.items?.firstOrNull { it.id == itemId }
        val localFile = item?.localFile
        if (item == null || localFile == null) {
            notify("That song has no offline copy to remove.")
            return
        }
        if ("$playlistId:$itemId" in _state.value.jellyfinDownloads ||
            "$playlistId:$itemId" in _state.value.youtubeDownloads
        ) {
            notify("Wait for the download to finish before removing its offline copy.")
            return
        }
        files.delete(localFile)
        playlists.replaceItem(playlistId, itemId, item.copy(localFile = null))
        refreshOfflineAudioState()
        notify("Offline copy removed. The song stays in your playlist.")
    }

    fun removeStoredOfflineAudio(fileName: String) {
        if (rejectOfflineAudioManagement()) return
        if (_state.value.offlineAudioFiles.none { it.fileName == fileName }) {
            notify("That offline audio file is no longer available.")
            refreshOfflineAudioState()
            return
        }
        _state.update { it.copy(offlineAudioBusy = true) }
        viewModelScope.launch {
            try {
                val removed = withContext(Dispatchers.IO) { files.removeStoredFile(fileName) }
                if (!removed) {
                    notify("Couldn't remove that offline audio file.")
                    return@launch
                }
                val current = playlists.all()
                val updated = current.map { playlist ->
                    playlist.copy(items = playlist.items.map { item ->
                        if (item.localFile == fileName) item.copy(localFile = null) else item
                    })
                }
                if (updated != current) playlists.replaceAll(updated)
                refreshOfflineAudioState()
                notify("Offline audio removed. Any playlist song stays in your library.")
            } catch (_: Exception) {
                notify("Couldn't remove that offline audio file.")
            } finally {
                _state.update { it.copy(offlineAudioBusy = false) }
            }
        }
    }

    fun clearAllOfflineAudio() {
        if (rejectOfflineAudioManagement()) return
        val currentFiles = files.listStoredAudio()
        if (currentFiles.isEmpty()) {
            refreshOfflineAudioState()
            notify("There is no offline audio to remove.")
            return
        }
        _state.update { it.copy(offlineAudioBusy = true) }
        viewModelScope.launch {
            try {
                val removedNames = withContext(Dispatchers.IO) {
                    currentFiles.filter { files.removeStoredFile(it.name) }.mapTo(HashSet()) { it.name }
                }
                val current = playlists.all()
                val updated = current.map { playlist ->
                    playlist.copy(items = playlist.items.map { item ->
                        if (item.localFile in removedNames) item.copy(localFile = null) else item
                    })
                }
                if (updated != current) playlists.replaceAll(updated)
                refreshOfflineAudioState()
                val stillPresent = currentFiles.size - removedNames.size
                if (stillPresent == 0) notify("Removed ${removedNames.size} offline audio files. Playlist songs remain.")
                else notify("Removed ${removedNames.size} files; $stillPresent could not be deleted. Try again.")
            } catch (_: Exception) {
                notify("Couldn't clear offline audio. Try again.")
            } finally {
                _state.update { it.copy(offlineAudioBusy = false) }
            }
        }
    }

    private fun rejectOfflineAudioManagement(): Boolean {
        val current = _state.value
        when {
            current.syncBusy || current.backupBusy -> notify("Wait for the current sync or backup to finish.")
            current.offlineAudioBusy -> notify("Offline audio is already being managed.")
            current.jellyfinDownloads.isNotEmpty() || current.youtubeDownloads.isNotEmpty() ->
                notify("Wait for audio downloads to finish before managing offline storage.")
            else -> return false
        }
        return true
    }

    // ----- Automations -----

    fun saveAutomation(rule: AutomationRule): Boolean {
        if (rejectMutationWhileSyncing()) return false
        val name = rule.name.trim()
        when {
            name.isEmpty() -> { notify("Name this automation first."); return false }
            _state.value.playlists.none { it.id == rule.playlistId } -> {
                notify("Pick a playlist that still exists."); return false
            }
            !rule.hasCondition -> { notify("Set a place, a motion, or a time window."); return false }
            rule.place != null && (rule.radiusMeters < 20.0 || rule.radiusMeters > 2000.0) -> {
                notify("Trigger radius must be 20–2000 meters."); return false
            }
            (rule.fromMinute == null) != (rule.toMinute == null) -> {
                notify("Fill both time fields, or leave both empty."); return false
            }
        }
        val saved = rule.copy(name = name.take(80))
        val updated = _state.value.automations.filterNot { it.id == saved.id } + saved
        automations.replaceAll(updated)
        _state.update { it.copy(automations = updated) }
        recordCloudSyncChanges()
        notify("Automation \"${name.take(80)}\" saved${if (watchRunning) " — the watch will apply it now." else "."}")
        return true
    }

    fun deleteAutomation(id: String) {
        if (rejectMutationWhileSyncing()) return
        val updated = _state.value.automations.filterNot { it.id == id }
        automations.replaceAll(updated)
        _state.update { it.copy(automations = updated) }
        recordCloudSyncChanges()
        notify("Automation removed.")
    }

    private val watchRunning: Boolean get() = LocationWatchService.status.value.running

    // ----- Cloud sync: account auth plus client-side end-to-end encryption -----

    fun createCloudSyncAccount(server: String, email: String, password: String) {
        if (_state.value.syncBusy) return
        if (_state.value.backupBusy) {
            notify("Wait for the current backup operation to finish.")
            return
        }
        _state.update { it.copy(syncBusy = true) }
        viewModelScope.launch {
            try {
                val recoveryKey = cloudSync.createAccount(server, email, password)
                _state.update {
                    it.copy(
                        syncServer = cloudSync.serverUrl,
                        syncEffectiveServer = cloudSync.effectiveServerUrl,
                        syncUsingCustomServer = cloudSync.isUsingCustomServer,
                        syncEmail = cloudSync.accountEmail,
                        syncConnected = cloudSync.connected,
                        syncHasRecoveryKey = cloudSync.hasRecoveryKey,
                        syncLastSyncAt = cloudSync.lastSyncAt,
                        syncPendingChanges = cloudSync.pendingChangeCount(),
                        syncFailed = false,
                        syncError = null,
                        syncRecoveryKey = recoveryKey
                    )
                }
                notify("Sync account created. Save the recovery key shown on the Connect tab before leaving this device.")
            } catch (e: Exception) {
                notify(e.message ?: "Could not create the sync account.")
            } finally {
                _state.update { it.copy(syncBusy = false) }
            }
        }
    }

    fun signInCloudSync(server: String, email: String, password: String) {
        if (_state.value.syncBusy) return
        if (_state.value.backupBusy) {
            notify("Wait for the current backup operation to finish.")
            return
        }
        _state.update { it.copy(syncBusy = true) }
        viewModelScope.launch {
            try {
                cloudSync.signIn(server, email, password)
                _state.update {
                    it.copy(
                        syncServer = cloudSync.serverUrl,
                        syncEffectiveServer = cloudSync.effectiveServerUrl,
                        syncUsingCustomServer = cloudSync.isUsingCustomServer,
                        syncEmail = cloudSync.accountEmail,
                        syncConnected = cloudSync.connected,
                        syncHasRecoveryKey = cloudSync.hasRecoveryKey,
                        syncLastSyncAt = cloudSync.lastSyncAt,
                        syncPendingChanges = cloudSync.pendingChangeCount(),
                        syncFailed = false,
                        syncError = null,
                        syncRecoveryKey = null
                    )
                }
                notify(if (cloudSync.hasRecoveryKey) "Sync account connected." else "Account connected. Enter your saved recovery key to sync this device.")
            } catch (e: Exception) {
                notify(e.message ?: "Could not sign in to the sync account.")
            } finally {
                _state.update { it.copy(syncBusy = false) }
            }
        }
    }

    fun unlockCloudSync(rawKey: String) {
        try {
            cloudSync.unlockRecoveryKey(rawKey)
            _state.update {
                it.copy(
                    syncHasRecoveryKey = cloudSync.hasRecoveryKey,
                    syncPendingChanges = cloudSync.pendingChangeCount(),
                    syncFailed = false,
                    syncError = null
                )
            }
            notify("Recovery key saved securely on this device. Tap Sync now to merge your library.")
        } catch (e: CloudSyncException) {
            notify(e.message ?: "That recovery key is not valid.")
        }
    }

    fun showCloudRecoveryKey() {
        val key = cloudSync.recoveryKeyForDisplay()
        if (key == null) notify("No recovery key is available on this device. Enter the key you saved when the account was created.")
        else _state.update { it.copy(syncRecoveryKey = key) }
    }

    fun forgetCloudRecoveryKey() {
        cloudSync.forgetRecoveryKey()
        _state.update { it.copy(syncHasRecoveryKey = false, syncRecoveryKey = null) }
        notify("This device's saved key was removed. Enter your recovery key again to sync.")
    }

    fun dismissCloudRecoveryKey() = _state.update { it.copy(syncRecoveryKey = null) }

    fun syncCloudData() = runCloudSync(showMessages = true)

    fun setCloudSyncAutoEnabled(enabled: Boolean) {
        cloudSync.setAutoSyncEnabled(enabled)
        _state.update { it.copy(syncAutoEnabled = enabled, syncError = null, syncFailed = false) }
        if (enabled && cloudSync.connected && cloudSync.hasRecoveryKey) runCloudSync(showMessages = false)
    }

    private fun runCloudSync(showMessages: Boolean) {
        if (_state.value.syncBusy) return
        if (_state.value.backupBusy || _state.value.offlineAudioBusy ||
            _state.value.jellyfinDownloads.isNotEmpty() || _state.value.youtubeDownloads.isNotEmpty()
        ) {
            if (showMessages) notify("Wait for the current backup or audio download to finish before syncing.")
            return
        }
        if (!cloudSync.connected) {
            if (showMessages) notify("Sign in to your sync account first.")
            return
        }
        if (!cloudSync.hasRecoveryKey) {
            if (showMessages) notify("Enter the recovery key you saved when you created the account.")
            return
        }
        _state.update { it.copy(syncBusy = true, syncError = null, syncFailed = false) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { cloudSync.sync() }
                _state.update {
                    it.copy(
                        pins = pins.all(), playlists = playlists.all(), automations = automations.all(),
                        syncConnected = cloudSync.connected,
                        syncHasRecoveryKey = cloudSync.hasRecoveryKey,
                        syncEffectiveServer = cloudSync.effectiveServerUrl,
                        syncUsingCustomServer = cloudSync.isUsingCustomServer,
                        syncLastSyncAt = cloudSync.lastSyncAt,
                        syncPendingChanges = cloudSync.pendingChangeCount(),
                        syncFailed = false,
                        syncError = result.compactionWarning
                    )
                }
                refreshOfflineAudioState()
                if (showMessages) {
                    val warning = result.compactionWarning?.let { " History checkpoint warning: $it" }.orEmpty()
                    notify(
                        "Synced ${counted(result.pins, "pin")}, ${counted(result.playlists, "playlist")}, " +
                            "${counted(result.automations, "automation")}. " +
                            "${result.uploaded} changes sent, ${result.downloaded} received.$warning"
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val message = e.message ?: "Cloud sync failed. Your local data is unchanged."
                _state.update {
                    it.copy(
                        syncError = message,
                        syncFailed = true,
                        syncPendingChanges = cloudSync.pendingChangeCount()
                    )
                }
                if (showMessages) notify(message)
            } finally {
                _state.update {
                    it.copy(syncBusy = false, syncPendingChanges = cloudSync.pendingChangeCount())
                }
            }
        }
    }

    fun disconnectCloudSync() {
        if (_state.value.syncBusy) return
        _state.update { it.copy(syncBusy = true) }
        viewModelScope.launch {
            try {
                cloudSync.disconnect()
                _state.update {
                    it.copy(
                        syncConnected = false, syncHasRecoveryKey = false,
                        syncEffectiveServer = cloudSync.effectiveServerUrl,
                        syncUsingCustomServer = cloudSync.isUsingCustomServer,
                        syncRecoveryKey = null, syncLastSyncAt = cloudSync.lastSyncAt
                    )
                }
                notify("Signed out of cloud sync on this device. Local pins and playlists are untouched.")
            } finally {
                _state.update { it.copy(syncBusy = false) }
            }
        }
    }

    fun deleteCloudSyncAccount() {
        if (_state.value.syncBusy) return
        _state.update { it.copy(syncBusy = true) }
        viewModelScope.launch {
            try {
                cloudSync.deleteAccount()
                _state.update {
                    it.copy(
                        syncConnected = false, syncHasRecoveryKey = false, syncEmail = "",
                        syncEffectiveServer = cloudSync.effectiveServerUrl,
                        syncUsingCustomServer = cloudSync.isUsingCustomServer,
                        syncRecoveryKey = null, syncLastSyncAt = 0L, syncPendingChanges = 0,
                        syncFailed = false, syncError = null
                    )
                }
                notify("Sync account and remote encrypted history deleted. This device's library is untouched.")
            } catch (e: Exception) {
                notify(e.message ?: "Could not delete the sync account.")
            } finally {
                _state.update { it.copy(syncBusy = false) }
            }
        }
    }

    private fun buildOfflineAudioItems(
        playlistSnapshot: List<Playlist>,
        storedFiles: List<StoredAudioFile> = files.listStoredAudio()
    ): List<OfflineAudioItem> {
        val linked = mutableMapOf<String, Pair<PlaylistItem, String>>()
        playlistSnapshot.forEach { playlist ->
            playlist.items.forEach { item ->
                item.localFile?.let { name ->
                    if (name !in linked) linked[name] = item to playlist.name
                }
            }
        }
        return storedFiles.map { stored ->
            val reference = linked[stored.name]
            val item = reference?.first
            val playlistName = reference?.second
            OfflineAudioItem(
                fileName = stored.name,
                title = item?.title ?: "Unlinked audio file",
                detail = if (item == null) "Not attached to a playlist song" else
                    listOf(item.artist.ifBlank { "Unknown artist" }, playlistName.orEmpty())
                        .filter(String::isNotBlank).joinToString(" · "),
                sizeBytes = stored.sizeBytes
            )
        }
    }

    private fun refreshOfflineAudioState() {
        // A car that is open on a list should see the change without being reopened.
        hub.notifyLibraryChanged()
        val currentPlaylists = playlists.all()
        val storedFiles = files.listStoredAudio()
        _state.update {
            it.copy(
                playlists = currentPlaylists,
                offlineAudioFiles = buildOfflineAudioItems(currentPlaylists, storedFiles),
                audioStorageBytes = storedFiles.sumOf { file -> file.sizeBytes }
            )
        }
    }

    private fun recordCloudSyncChanges() {
        // Cloud sync is opt-in. Before sign-in (or while signed out), keep no duplicate journal;
        // the first sync seeds local data and later syncs diff against the last applied snapshot.
        if (!cloudSync.connected) return
        hub.notifyLibraryChanged()
        runCatching { cloudSync.observeLocalChanges() }
        _state.update { it.copy(syncPendingChanges = cloudSync.pendingChangeCount()) }
    }

    // ----- Backup: export and import of everything local -----

    /**
     * Writes every pin, playlist, and automation to the file the user just picked.
     * The file goes wherever the system file picker sends it; nothing is uploaded.
     */
    fun exportBackup(uri: Uri) {
        if (_state.value.syncBusy || _state.value.backupBusy || _state.value.offlineAudioBusy ||
            _state.value.jellyfinDownloads.isNotEmpty() || _state.value.youtubeDownloads.isNotEmpty()
        ) {
            notify("Wait for the current sync, backup, or audio operation to finish.")
            return
        }
        _state.update { it.copy(backupBusy = true) }
        viewModelScope.launch {
            try {
                val currentPins = pins.all()
                val currentPlaylists = playlists.all()
                val currentRules = automations.all()
                val text = withContext(Dispatchers.IO) {
                    BackupFormat.export(currentPins, currentPlaylists, currentRules)
                }
                val written = withContext(Dispatchers.IO) { writeText(uri, text) }
                if (written) {
                    notify(
                        "Backup saved — ${counted(currentPins.size, "pin")}, " +
                            "${counted(currentPlaylists.size, "playlist")}, " +
                            "${counted(currentRules.size, "automation")}."
                    )
                } else {
                    notify("Couldn't write that file. Try a different location.")
                }
            } catch (e: Exception) {
                notify(e.message ?: "Couldn't export a backup.")
            } finally {
                _state.update { it.copy(backupBusy = false) }
            }
        }
    }

    /**
     * Reads a backup and merges it into what is already here. Ids that exist are kept
     * locally, so importing twice (or importing an older file) never loses an edit.
     * Audio files are not carried by backups; a reference to a file this device does not
     * have is dropped rather than imported broken.
     */
    fun importBackup(uri: Uri) {
        if (rejectMutationWhileSyncing() || _state.value.backupBusy ||
            _state.value.jellyfinDownloads.isNotEmpty() || _state.value.youtubeDownloads.isNotEmpty()
        ) return
        _state.update { it.copy(backupBusy = true) }
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { readText(uri) }
                    ?: throw BackupException("Couldn't read that file.")
                val brought = withContext(Dispatchers.IO) { BackupFormat.parse(text) }
                val restored = brought.copy(
                    playlists = brought.playlists.map { playlist ->
                        playlist.copy(items = playlist.items.map { item ->
                            item.copy(localFile = item.localFile?.takeIf(files::exists))
                        })
                    }
                )
                val addedPins = pins.merge(restored.pins)
                val (addedPlaylists, addedSongs) = playlists.merge(restored.playlists)
                val addedRules = automations.merge(restored.automations)
                _state.update {
                    it.copy(
                        pins = pins.all(),
                        playlists = playlists.all(),
                        automations = automations.all()
                    )
                }
                refreshOfflineAudioState()
                recordCloudSyncChanges()
                val total = addedPins + addedPlaylists + addedSongs + addedRules
                if (total == 0) {
                    notify("Nothing new in that backup — everything it holds is already on this device.")
                } else {
                    notify(
                        "Imported ${counted(addedPins, "new pin")}, ${counted(addedPlaylists, "playlist")}, " +
                            "${counted(addedSongs, "song")}, and ${counted(addedRules, "automation")}. " +
                            "Items already on this device were kept."
                    )
                }
            } catch (e: BackupException) {
                notify(e.message ?: "That file is not a Soundtrail backup.")
            } catch (e: Exception) {
                notify("Couldn't import that backup.")
            } finally {
                _state.update { it.copy(backupBusy = false) }
            }
        }
    }

    private fun counted(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

    private fun writeText(uri: Uri, text: String): Boolean {
        val resolver = getApplication<Application>().contentResolver
        return runCatching {
            // "wt" truncates a pre-existing file; not every provider accepts a mode, so fall back.
            val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(uri)
                ?: error("No writable stream for that file.")
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.isSuccess
    }

    private fun readText(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.size > BackupFormat.MAX_CHARS * 4) {
            throw BackupException("That file is too large to be a Soundtrail backup.")
        }
        return bytes.toString(Charsets.UTF_8)
    }

    fun addPin(input: PinInput): Boolean {
        if (rejectMutationWhileSyncing()) return false
        val link = MusicLinkParser.fromText(input.url)
        val point = input.point
        when {
            input.title.isBlank() -> { notify("Add a song title first."); return false }
            link == null -> {
                notify("Use a Spotify, SoundCloud, YouTube Music, or Jellyfin song link.")
                return false
            }
            point == null || !point.isValid() -> {
                notify("Find your location or enter valid coordinates."); return false
            }
        }
        val pin = MusicPin(
            id = UUID.randomUUID().toString(),
            title = input.title.trim().take(120),
            artist = input.artist.trim().take(120),
            place = input.place.trim().ifBlank { "Somewhere special" }.take(100),
            note = input.note.trim().take(280),
            url = link.url,
            provider = link.provider,
            point = point,
            createdAt = System.currentTimeMillis()
        )
        return try {
            val updated = (_state.value.pins + pin).sortedByDescending { it.createdAt }
            pins.replaceAll(updated)
            _state.update { it.copy(pins = updated) }
            recordCloudSyncChanges()
            notify("Pinned ${pin.title} to ${pin.place}.")
            true
        } catch (_: Exception) {
            notify("Couldn't save this pin. Please try again.")
            false
        }
    }

    fun deletePin(id: String) {
        if (rejectMutationWhileSyncing()) return
        try {
            val updated = _state.value.pins.filterNot { it.id == id }
            pins.replaceAll(updated)
            _state.update { it.copy(pins = updated) }
            recordCloudSyncChanges()
            notify("Pin removed.")
        } catch (_: Exception) {
            notify("Couldn't remove that pin.")
        }
    }
}
