package tech.egrie.soundtrail

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import tech.egrie.soundtrail.data.BackupContents
import tech.egrie.soundtrail.data.BackupException
import tech.egrie.soundtrail.data.BackupFormat
import tech.egrie.soundtrail.data.FileStore
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.LegacyServiceCleanup
import tech.egrie.soundtrail.data.ManagedBackup
import tech.egrie.soundtrail.data.ManagedBackupStore
import tech.egrie.soundtrail.data.ManagedBackupStatus
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.PinStore
import tech.egrie.soundtrail.data.Playlist
import tech.egrie.soundtrail.data.PlaylistItem
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
import tech.egrie.soundtrail.assistant.AssistantAnswer
import tech.egrie.soundtrail.assistant.AssistantHit
import tech.egrie.soundtrail.assistant.AssistantLibrary
import tech.egrie.soundtrail.assistant.AssistantParser
import tech.egrie.soundtrail.assistant.toQueueEntry
import tech.egrie.soundtrail.integrations.TrackResult
import tech.egrie.soundtrail.location.DeviceLocation
import tech.egrie.soundtrail.mood.DEFAULT_OLLAMA_MODEL
import tech.egrie.soundtrail.mood.MoodAiException
import tech.egrie.soundtrail.mood.MoodAiOutcome
import tech.egrie.soundtrail.mood.MoodAiConsent
import tech.egrie.soundtrail.mood.MoodAiConsentPrompt
import tech.egrie.soundtrail.mood.MoodAiDestination
import tech.egrie.soundtrail.mood.MoodAiProvider
import tech.egrie.soundtrail.mood.MoodAiService
import tech.egrie.soundtrail.mood.MoodAiUrls
import tech.egrie.soundtrail.mood.MoodCandidate
import tech.egrie.soundtrail.mood.MoodContext
import tech.egrie.soundtrail.mood.MoodEngine
import tech.egrie.soundtrail.mood.MoodMixTrack
import tech.egrie.soundtrail.mood.MoodMixer
import tech.egrie.soundtrail.mood.MoodPickResolver
import tech.egrie.soundtrail.mood.MoodProfile
import tech.egrie.soundtrail.mood.MoodPickSearch
import tech.egrie.soundtrail.mood.MoodStore
import tech.egrie.soundtrail.mood.MoodSearchTarget
import tech.egrie.soundtrail.mood.TrackMoodTags
import tech.egrie.soundtrail.playback.JellyfinPlayer
import tech.egrie.soundtrail.playback.LibraryMedia
import tech.egrie.soundtrail.playback.PlaybackHub
import tech.egrie.soundtrail.playback.QueueEntry
import tech.egrie.soundtrail.playback.toQueueEntry
import tech.egrie.soundtrail.sync.CloudSyncException
import tech.egrie.soundtrail.sync.CloudSyncService
import tech.egrie.soundtrail.sync.PasswordChange
import tech.egrie.soundtrail.sync.SessionRenewal
import tech.egrie.soundtrail.sync.SessionSignOut
import tech.egrie.soundtrail.sync.SyncDeviceSession
import java.util.UUID

internal data class AppState(
    val pins: List<MusicPin> = emptyList(),
    val location: GeoPoint? = null,
    val locating: Boolean = false,
    val jellyfinConnected: Boolean = false,
    val jellyfinServer: String = "",
    val jellyfinName: String = "",
    val jellyfinServerName: String = "",
    val connectingJellyfin: Boolean = false,
    val searching: Boolean = false,
    val results: List<TrackResult> = emptyList(),
    val searchError: String? = null,
    val lookupSearching: Boolean = false,
    val lookupResults: List<SongInfo> = emptyList(),
    val lookupError: String? = null,
    val playlists: List<tech.egrie.soundtrail.data.Playlist> = emptyList(),
    val automations: List<AutomationRule> = emptyList(),
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
    /** This device's own session id, so the device list can mark itself without another request. */
    val syncSessionId: String? = null,
    val syncSessionExpiresAt: Long = 0L,
    /** Every signed-in device on the account, newest first. Ids only — never a token. */
    val syncSessions: List<SyncDeviceSession> = emptyList(),
    val syncSessionsLoading: Boolean = false,
    val syncSessionsError: String? = null,
    val syncPasswordBusy: Boolean = false,
    /**
     * When a password change last succeeded, as a timestamp.
     *
     * The form clears on this rather than on the tap, so a refusal — "that is not your current
     * password" — leaves what was typed in place to correct. A success is the only thing that
     * should empty a password field.
     */
    val syncPasswordDone: Long = 0L,
    val syncRepushBusy: Boolean = false,
    /** True while a server history that went backwards has not been re-pushed. */
    val syncRollbackWarning: Boolean = false,
    val jellyfinDownloads: Map<String, JellyfinDownloadProgress> = emptyMap(),
    val offlineAudioFiles: List<OfflineAudioItem> = emptyList(),
    val offlineAudioBusy: Boolean = false,
    val audioStorageBytes: Long = 0L,
    val watchStatus: tech.egrie.soundtrail.automation.WatchStatus = tech.egrie.soundtrail.automation.WatchStatus(),
    /** Automatic on-device snapshots: the switch, the files kept, and whether one is writing. */
    val managedBackups: ManagedBackupStatus = ManagedBackupStatus(),
    /** The mood reader: what the listener typed, what it meant, and the mix it produced. */
    val mood: MoodUiState = MoodUiState(),
    /** The Ask tab: what was said or typed, and the answer to it. */
    val assistant: AssistantUiState = AssistantUiState()
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
    /**
     * What the endpoint calls those models, when it gave names of its own — Arena lists the model
     * a request must ask for (`coding-router-preview`) as `(Arena) Coding Router`. Display only:
     * the id is what gets sent.
     */
    val openAiModelLabels: Map<String, String> = emptyMap(),
    /**
     * The songs the last reading turned up, resolved to something real: a stored song, a search
     * result with a link, or a row that opens the app that owns it.
     */
    val found: List<AssistantHit> = emptyList(),
    /** A sentence about the search: what it could not match, or why it found nothing. */
    val foundNote: String? = null,
    /** True while a connection test or a model check is running. */
    val aiBusy: Boolean = false,
    /** A sentence from the last test, shown under the provider picker. */
    val aiNote: String? = null,
    /**
     * An external destination waiting for approval. While this is set, no mood request has been
     * sent; the app-wide dialog in SoundtrailApp owns the answer.
     */
    val consentRequest: MoodAiConsentPrompt? = null,
    /**
     * When an external endpoint was last approved on this device, or null. Shown beside the
     * provider picker with the way to withdraw it — an approval nobody can find is not consent.
     */
    val aiConsentAt: Long? = null,
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

internal data class OfflineAudioItem(
    val fileName: String,
    val title: String,
    val detail: String,
    val sizeBytes: Long
)

/** How many TheAudioDB lookups one mood refinement may make; keeps it quick and polite. */
private const val MAX_MOOD_TAG_LOOKUPS = 12

/** A burst of edits becomes one managed snapshot, not one per tap. */
private const val MANAGED_BACKUP_DEBOUNCE_MS = 15_000L

internal data class PinInput(
    val title: String,
    val artist: String,
    val place: String,
    val note: String,
    val url: String,
    val point: GeoPoint?
)

internal class MainViewModel(application: Application) : AndroidViewModel(application) {
    /** The rolling snapshots; declared before the stores whose writes schedule them. */
    private val localBackups = ManagedBackupStore(application)

    // Every store write — a pin, a playlist edit, a sync merge, an import — schedules one
    // debounced managed snapshot. Individual call sites do not have to remember to.
    private val pins = PinStore(application) { scheduleManagedBackup() }
    private val playlists = PlaylistStore(application) { scheduleManagedBackup() }
    private val automations = AutomationStore(application) { scheduleManagedBackup() }

    private var managedBackupJob: Job? = null
    private val files = FileStore(application)
    private val jellyfin = JellyfinService(application)
    private val audioDb = AudioDbService()
    private val locationReader = DeviceLocation(application)
    private val apps = DeviceApps(application)
    private val cloudSync = CloudSyncService(application, pins, playlists, automations, files)
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
    private val moodPicks = MoodPickResolver(jellyfin)

    /**
     * The app's one player, shared with [tech.egrie.soundtrail.playback.PlaybackService] so
     * the in-app bar and the playback notification always drive the same stream.
     */
    val player: JellyfinPlayer = hub.player

    /** The media ids of the mix that is playing, so the Mood tab's rows light up as it goes. */
    private var moodQueueKeys: List<String> = emptyList()

    /** What the consent dialog was opened for, so confirming can finish that same action. */
    private enum class MoodConsentAction { ENABLE, ANALYZE, TEST }

    private data class PendingMoodConsent(
        val action: MoodConsentAction,
        val destination: MoodAiDestination,
        val quickMoodId: String? = null,
        val announce: Boolean = false
    )

    private var pendingMoodConsent: PendingMoodConsent? = null

    private val _state = MutableStateFlow(
        AppState(
            pins = pins.all(),
            playlists = playlists.all(),
            automations = automations.all(),
            offlineAudioFiles = buildOfflineAudioItems(playlists.all()),
            audioStorageBytes = files.totalBytes(),
            syncPendingChanges = cloudSync.pendingChangeCount(),
            jellyfinConnected = jellyfin.connected,
            jellyfinServer = jellyfin.serverUrl,
            jellyfinName = jellyfin.userName,
            jellyfinServerName = jellyfin.serverName,
            managedBackups = ManagedBackupStatus(enabled = localBackups.enabled),
            syncServer = cloudSync.serverUrl,
            syncEffectiveServer = cloudSync.effectiveServerUrl,
            syncUsingCustomServer = cloudSync.isUsingCustomServer,
            syncEmail = cloudSync.accountEmail,
            syncConnected = cloudSync.connected,
            syncHasRecoveryKey = cloudSync.hasRecoveryKey,
            syncAutoEnabled = cloudSync.autoSyncEnabled,
            syncLastSyncAt = cloudSync.lastSyncAt,
            syncSessionId = cloudSync.sessionId,
            syncSessionExpiresAt = cloudSync.sessionExpiresAt,
            syncRollbackWarning = cloudSync.hasRollbackWarning,
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
                aiConsentAt = moodPrefs.consentAt(),
                aiReady = moodAi.configured,
                enrichEnabled = moodPrefs.tagLookupEnabled
            )
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
        // An upgrade away from the streaming services: remove what they left behind, once, before
        // anything else reads the library — and before the first sync, so the rows that go are
        // tombstoned to the listener's other devices in that same push instead of the next one.
        viewModelScope.launch {
            val cleanup = withContext(Dispatchers.IO) {
                runCatching { LegacyServiceCleanup.runOnce(application, pins, playlists) }.getOrNull()
            }
            if (cleanup != null && !cleanup.isEmpty) {
                val kept = playlists.all()
                _state.update {
                    it.copy(playlists = kept, offlineAudioFiles = buildOfflineAudioItems(kept))
                }
                recordCloudSyncChanges()
                notify(cleanup.message())
            }
            // On start: ask the server whether this session is still the one it issued, and renew
            // it if expiry is inside a week. Throttled to once a day inside refreshSession, because
            // renewing kills the old token and doing that per request would be self-inflicted
            // sign-out. A lost session is reported before any sync is attempted on top of it.
            if (cloudSync.connected) refreshSyncSession()
            if (cloudSync.autoSyncEnabled && cloudSync.connected && cloudSync.hasRecoveryKey) {
                runCloudSync(showMessages = false)
            }
        }
        // Read the snapshot list off the main thread. No snapshot is taken just for opening the
        // app: a launch that changed nothing would only push a real earlier version out of the
        // ten kept. Every store write schedules one, and "Back up now" writes on demand.
        viewModelScope.launch {
            val kept = withContext(Dispatchers.IO) {
                runCatching { localBackups.list() }.getOrDefault(emptyList())
            }
            _state.update { it.copy(managedBackups = it.managedBackups.copy(backups = kept)) }
        }
        refreshDeviceStatus()
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
                jellyfinConnected = jellyfin.connected,
                syncServer = cloudSync.serverUrl,
                syncEffectiveServer = cloudSync.effectiveServerUrl,
                syncUsingCustomServer = cloudSync.isUsingCustomServer,
                syncEmail = cloudSync.accountEmail,
                syncConnected = cloudSync.connected,
                syncHasRecoveryKey = cloudSync.hasRecoveryKey,
                syncAutoEnabled = cloudSync.autoSyncEnabled,
                syncLastSyncAt = cloudSync.lastSyncAt,
                syncSessionId = cloudSync.sessionId,
                syncSessionExpiresAt = cloudSync.sessionExpiresAt,
                syncRollbackWarning = cloudSync.hasRollbackWarning,
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
                results = emptyList(),
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

    /** Plays a search result from the listener's own Jellyfin server. */
    fun playTrackResult(track: TrackResult) {
        hub.playEntry(
            QueueEntry(
                mediaId = LibraryMedia.song(track.url),
                title = track.title,
                artist = track.artist,
                provider = track.provider,
                url = track.url
            )
        )
    }

    fun togglePlayback() = hub.toggle()

    /** Ends playback and clears the queue behind every surface: bar, notification and car. */
    fun stopPlayback() = hub.stopPlayback()

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
     * song goes to the listener's own library first, and then — when a Jellyfin server is
     * connected — to that server's catalogue, so the request works even for songs that were
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
        // "find music on Jellyfin that fits: rainy sunday" is the request the Mood tab builds for
        // a model, so the same sentence typed or spoken here goes the same way: the words after
        // "that fits" become the mood, rather than the whole sentence being searched for as if it
        // were a song title. Where it looks is not part of the request: every reading searches the
        // listener's Jellyfin server, whatever service the sentence happens to name.
        AssistantParser.findMusic(said)?.let { find ->
            setMoodText(find.text)
            _state.update {
                it.copy(
                    assistant = it.assistant.copy(
                        input = "", question = said, hits = emptyList(), reply = null
                    )
                )
            }
            analyzeMood(announce = true)
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
            // A request that named a place to look — "the xx on jellyfin" — is honoured; anything
            // else searches the listener's own server too, since that is the only catalogue left.
            val wantsServer = request.provider == null ||
                request.provider == MusicProvider.JELLYFIN
            val searched = jellyfin.connected && wantsServer
            var failure: String? = null
            val found = if (searched) {
                try {
                    // One look at the request as it was understood; a title that a command word
                    // was hiding ("Play That Funky Music") gets a second look as it was said,
                    // but only when the first found nothing at all.
                    var hits = withContext(Dispatchers.IO) { jellyfin.searchTracks(request.phrase) }
                    if (hits.isEmpty() && request.spoken.isNotBlank() &&
                        !request.spoken.equals(request.phrase, ignoreCase = true)
                    ) {
                        hits = withContext(Dispatchers.IO) { jellyfin.searchTracks(request.spoken) }
                    }
                    hits.map { track ->
                        AssistantHit(
                            title = track.title,
                            artist = track.artist,
                            provider = track.provider,
                            detail = track.provider.label,
                            url = track.url
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message ?: "Your Jellyfin server could not be searched."
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
        if (mood.consentRequest != null) {
            // A second request arrived (a spoken one, say) while the destination prompt is open.
            // Say where the answer is instead of quietly ignoring it.
            notify("Answer the destination prompt first — nothing has been sent yet.")
            return
        }
        if (mood.analyzing) return
        val context = moodContext()
        val useModel = mood.aiEnabled && mood.aiProvider != MoodAiProvider.ON_DEVICE && mood.aiReady
        if (mood.aiEnabled && !mood.aiReady && mood.aiProvider != MoodAiProvider.ON_DEVICE) {
            notify(missingProviderSetting(mood.aiProvider))
        }
        if (useModel && quickMoodId == null) {
            // A mood request leaving the phone is exactly what the destination prompt exists
            // for. Nothing is written to the network until the listener names this endpoint.
            // A quick pick never calls a model, so it does not ask.
            val destination = MoodAiConsent.destination(
                mood.aiProvider, mood.ollamaUrl, mood.openAiUrl
            )
            if (destination != null && !moodPrefs.hasConsent(destination)) {
                requestMoodConsent(
                    MoodConsentAction.ANALYZE, destination, quickMoodId, announce
                )
                return
            }
        }
        _state.update { it.copy(mood = it.mood.copy(analyzing = true, error = null, savedPlaylistName = null)) }
        // Every reading looks on the listener's Jellyfin server. That is the catalogue they can
        // actually play from here, so there is nothing else to choose between.
        val target = MoodSearchTarget.JELLYFIN
        viewModelScope.launch {
            val outcome = try {
                when {
                    quickMoodId != null ->
                        MoodAiOutcome(
                            MoodEngine.forMood(quickMoodId, context) ?: MoodEngine.analyze(mood.text, context)
                        )
                    useModel -> moodAi.readMood(mood.text, context, target)
                    else -> MoodAiOutcome(MoodEngine.analyze(mood.text, context))
                }
            } catch (e: MoodAiException) {
                notify("${e.message} Reading it on the device instead.")
                MoodAiOutcome(MoodEngine.analyze(mood.text, context))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MoodAiOutcome(MoodEngine.analyze(mood.text, context))
            }
            val profile = outcome.profile
            val candidates = moodCandidates()
            val mix = MoodMixer.build(profile, candidates)
            val untagged = MoodMixer.untagged(candidates).size
            // The songs the answer came with are looked up for real before anything is offered:
            // a model's suggestion becomes a row only once a search has confirmed it exists. With
            // no model in the loop, the reading's own phrases are what the server is asked for.
            val search = try {
                if (outcome.picks.isNotEmpty()) {
                    withContext(Dispatchers.IO) { moodPicks.fromPicks(outcome.picks) }
                } else {
                    withContext(Dispatchers.IO) { moodPicks.fromTerms(profile.searchTerms) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MoodPickSearch(error = e.message ?: "${target.short} could not be searched.")
            }
            _state.update {
                it.copy(
                    mood = it.mood.copy(
                        analyzing = false,
                        profile = profile,
                        mix = mix,
                        found = search.hits,
                        foundNote = foundNote(search, fromModel = outcome.picks.isNotEmpty()),
                        librarySize = candidates.size,
                        untaggedCount = untagged,
                        error = if (mix.isEmpty() && search.hits.isEmpty()) {
                            "Nothing in your library yet — add a song from your Jellyfin server, or attach audio you own."
                        } else null,
                        queueIndex = -1,
                        queuePlaying = false
                    )
                )
            }
            // A request that came in by voice is answered out loud, in the same words the card
            // shows; the mix itself is on the Mood tab.
            if (announce) {
                val found = search.hits.size
                val said = buildString {
                    append("That reads as ${profile.label}.")
                    append(
                        when {
                            found > 0 && mix.isEmpty() ->
                                " ${found} song${if (found == 1) "" else "s"} found " +
                                    "${target.phrase} on the Mood tab."
                            found > 0 ->
                                " ${found} song${if (found == 1) "" else "s"} found " +
                                    "${target.phrase}, and ${mix.size} in your library."
                            mix.isEmpty() -> " Nothing in your library fits it yet."
                            else -> " ${mix.size} song${if (mix.size == 1) "" else "s"} on the Mood tab."
                        }
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

    /**
     * Turns the model reading on or off.
     *
     * Turning it on for an external provider is where the listener first opts in, so that is the
     * first place the destination prompt appears — but only when the provider is actually ready
     * to send. A half-configured card still turns on; the prompt follows at the first request,
     * when there is a real endpoint to name. Nothing is sent before either question is answered.
     */
    fun setMoodAiEnabled(enabled: Boolean) {
        if (!enabled) {
            // Turning the model off also answers any prompt that was waiting: there is nothing
            // left to approve, and the on-device reading stays exactly where it was.
            pendingMoodConsent = null
            moodPrefs.aiEnabled = false
            _state.update {
                it.copy(mood = it.mood.copy(aiEnabled = false, aiNote = null, consentRequest = null))
            }
            notify("Model reading off. The on-device engine still reads your mood offline.")
            return
        }
        if (pendingMoodConsent != null) return
        val mood = _state.value.mood
        val destination = MoodAiConsent.destination(
            mood.aiProvider, mood.ollamaUrl, mood.openAiUrl
        )
        if (mood.aiProvider != MoodAiProvider.ON_DEVICE && mood.aiReady &&
            destination != null && !moodPrefs.hasConsent(destination)
        ) {
            requestMoodConsent(MoodConsentAction.ENABLE, destination)
            return
        }
        enableMoodAiNow()
    }

    /** Completes the switch once the destination is either on-device or already approved. */
    private fun enableMoodAiNow() {
        moodPrefs.aiEnabled = true
        val mood = _state.value.mood
        _state.update { it.copy(mood = it.mood.copy(aiEnabled = true, aiNote = null)) }
        notify(
            when {
                mood.aiProvider == MoodAiProvider.ON_DEVICE ->
                    "Already on the on-device engine — nothing leaves the device."
                mood.aiReady -> "${mood.aiProvider.short} reading on. Your mood description and its context leave the device."
                else -> missingProviderSetting(mood.aiProvider)
            }
        )
    }

    /**
     * Shows the destination question and parks the action that needed it. The prompt names the
     * endpoint the request would actually be built from, not what was typed, so a pasted
     * `…/api/chat` and a bare host are described the same way.
     */
    private fun requestMoodConsent(
        action: MoodConsentAction,
        destination: MoodAiDestination,
        quickMoodId: String? = null,
        announce: Boolean = false
    ) {
        pendingMoodConsent = PendingMoodConsent(action, destination, quickMoodId, announce)
        _state.update {
            it.copy(
                mood = it.mood.copy(
                    consentRequest = MoodAiConsentPrompt(
                        providerLabel = destination.provider.label,
                        endpoint = destination.endpoint
                    )
                )
            )
        }
    }

    /**
     * The listener approved the endpoint on screen. The current setting is compared with the one
     * they were shown first: if the provider or address moved while the dialog was open, the old
     * approval is not silently reused for the new destination — the question is asked again.
     */
    fun confirmMoodAiConsent() {
        val pending = pendingMoodConsent ?: return
        pendingMoodConsent = null
        _state.update { it.copy(mood = it.mood.copy(consentRequest = null)) }
        val current = MoodAiConsent.destination(
            moodPrefs.aiProvider, moodPrefs.ollamaUrl, moodPrefs.openAiUrl
        )
        if (current != pending.destination) {
            notify("That provider or address changed while the prompt was open. Nothing was sent.")
            when (pending.action) {
                MoodConsentAction.ENABLE -> setMoodAiEnabled(true)
                MoodConsentAction.ANALYZE -> analyzeMood(pending.quickMoodId, pending.announce)
                MoodConsentAction.TEST -> testMoodAi()
            }
            return
        }
        moodPrefs.rememberConsent(pending.destination)
        _state.update { it.copy(mood = it.mood.copy(aiConsentAt = moodPrefs.consentAt())) }
        when (pending.action) {
            MoodConsentAction.ENABLE -> enableMoodAiNow()
            MoodConsentAction.ANALYZE -> analyzeMood(pending.quickMoodId, pending.announce)
            MoodConsentAction.TEST -> testMoodAi()
        }
    }

    /**
     * The listener declined. Nothing is sent, and a reading that was waiting on the answer falls
     * back to the on-device engine — the switch goes off rather than leaving a model selected
     * that will keep asking before every request.
     */
    fun cancelMoodAiConsent() {
        val pending = pendingMoodConsent
        pendingMoodConsent = null
        _state.update { it.copy(mood = it.mood.copy(consentRequest = null)) }
        if (pending == null) return
        val leaveModelOff = pending.action != MoodConsentAction.TEST
        if (leaveModelOff) moodPrefs.aiEnabled = false
        _state.update {
            it.copy(
                mood = it.mood.copy(
                    aiEnabled = if (leaveModelOff) false else it.mood.aiEnabled,
                    aiNote = if (leaveModelOff) null else it.mood.aiNote
                )
            )
        }
        notify(
            when (pending.action) {
                MoodConsentAction.TEST -> "Nothing was sent. The endpoint was not tested."
                MoodConsentAction.ENABLE ->
                    "Nothing was sent. The model reading stays off; the on-device engine is ready."
                MoodConsentAction.ANALYZE ->
                    "Nothing was sent. Mood readings stay on this device."
            }
        )
    }

    /**
     * Withdraws the approval for the endpoint currently remembered. The next model request — a
     * reading, a test, or switching the model on — asks again and names the destination, and in
     * the meantime the on-device engine keeps reading. Nothing else is touched: the key, the
     * address and the model stay as they are, so re-approving is one tap on the prompt.
     */
    fun forgetMoodAiConsent() {
        pendingMoodConsent = null
        moodPrefs.forgetConsent()
        _state.update {
            it.copy(mood = it.mood.copy(aiConsentAt = null, consentRequest = null))
        }
        notify(
            "Endpoint approval forgotten. The next model request will ask again, naming the " +
                "destination; readings stay on this device until then."
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
                    },
                    openAiModelLabels = if (provider == MoodAiProvider.OPENAI_COMPATIBLE) {
                        it.mood.openAiModelLabels
                    } else {
                        emptyMap()
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
                        normalized == null -> "That does not look like an address. Try https://192.168.0.20:11434"
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

    /** Plays one found song, with the rest of the findings queued behind it. */
    fun playMoodFound(hit: AssistantHit) {
        val playable = AssistantAnswer.playable(_state.value.mood.found)
        val index = playable.indexOfFirst { it.itemId != null && it.itemId == hit.itemId }
            .takeIf { it >= 0 } ?: playable.indexOfFirst { it.url == hit.url }
        if (playable.isEmpty() || index < 0) {
            hub.playEntry(hit.toQueueEntry())
            return
        }
        hub.play(playable.map { it.toQueueEntry() }, index)
    }

    /** Queues everything the reading found, in the order it was listed. */
    fun playAllMoodFound() {
        val playable = AssistantAnswer.playable(_state.value.mood.found)
        if (playable.isEmpty()) {
            notify("Nothing in these findings can play on this phone yet.")
            return
        }
        hub.play(playable.map { it.toQueueEntry() }, 0)
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
                    openAiModelLabels = emptyMap(),
                    aiReady = moodAi.configured,
                    aiNote = "Arena's gateway is filled in. Paste a key from the Keys page below, " +
                        "then press Test: on Arena that sends one short reading as well, because " +
                        "a model list proves nothing there. Arena's free-router keys are issued " +
                        "for its own coding harnesses (Claude Code, the Code tab in Claude " +
                        "Desktop, OpenCode) and are refused to everything else, this app " +
                        "included; a key from a workspace with API access reads here."
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
            notify("Add your Ollama server's address first, for example https://192.168.0.20:11434.")
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
                .onSuccess { listing ->
                    val models = listing.models
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
                                openAiModelLabels = if (openAi) listing.labels else it.mood.openAiModelLabels,
                                openAiModel = if (openAi) moodPrefs.openAiModel else it.mood.openAiModel,
                                aiNote = when {
                                    swapped && MoodAiUrls.isArenaRouter(adapted) ->
                                        "Arena: \u201C${before.openAiModel}\u201D is not enabled for " +
                                            "this key, so \u201C$adapted\u201D is selected — the same " +
                                            "choice Arena's own setup script makes."
                                    swapped ->
                                        "Arena: this key has no router alias, so \u201C$adapted\u201D " +
                                            "is selected from the models it is enabled for — the " +
                                            "same choice Arena's own setup script makes."
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
     * What to say about a search, in one line, without overstating what happened: how many of the
     * named songs were found, what could not be matched, and — when nothing came back at all —
     * whether that was the server's complaint, a missing connection, or simply no match.
     */
    private fun foundNote(search: MoodPickSearch, fromModel: Boolean): String? {
        val target = MoodSearchTarget.JELLYFIN
        return when {
            search.hits.isNotEmpty() && search.unmatched > 0 ->
                "${search.hits.size} of the songs named were found ${target.phrase}; " +
                    "${search.unmatched} could not be matched to anything real and were left out."
            search.hits.isNotEmpty() -> null
            search.error != null -> search.error
            search.unmatched > 0 -> "None of the songs named could be found ${target.phrase}."
            fromModel -> "Nothing that was named could be found ${target.phrase}."
            else -> "Nothing came back from ${target.short} for that."
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
        val presetAlias = MoodAiUrls.isArenaRouter(mood.openAiModel)
        if (mood.openAiModel.isNotBlank() && !presetAlias) return mood.openAiModel
        return MoodAiUrls.arenaModelFrom(models) ?: mood.openAiModel
    }

    /** Probes the selected provider and reports exactly what happened. */
    fun testMoodAi() {
        val mood = _state.value.mood
        if (mood.consentRequest != null) {
            notify("Answer the destination prompt first — nothing has been sent yet.")
            return
        }
        if (mood.aiBusy) return
        if (!moodAi.configured) {
            notify(missingProviderSetting(mood.aiProvider))
            return
        }
        // A test asks the endpoint to read a short sample mood too — on Arena, that request is
        // the one its usage page counts — so it leaves the device for the same destination and
        // needs the same answer before it is sent.
        val destination = MoodAiConsent.destination(
            mood.aiProvider, mood.ollamaUrl, mood.openAiUrl
        )
        if (destination != null && !moodPrefs.hasConsent(destination)) {
            requestMoodConsent(MoodConsentAction.TEST, destination)
            return
        }
        runMoodAiTest()
    }

    /** The probe itself, once the destination may be used. */
    private fun runMoodAiTest() {
        _state.update { it.copy(mood = it.mood.copy(aiBusy = true, aiNote = null)) }
        viewModelScope.launch {
            runCatching { moodAi.testConnection() }
                .onSuccess { note ->
                    // A passing test says the endpoint answers; it does not say readings will use
                    // it. That is the switch at the top of the card, and it is off on a fresh
                    // install — so say so here, where someone who has just pasted a key is
                    // looking, rather than leaving them to wonder why nothing ever goes out.
                    val current = _state.value.mood
                    val shown =
                        if (!current.aiEnabled && current.aiProvider != MoodAiProvider.ON_DEVICE) {
                            "$note Readings still come from the on-device engine until the switch " +
                                "at the top of this card is on."
                        } else {
                            note
                        }
                    _state.update { it.copy(mood = it.mood.copy(aiBusy = false, aiNote = shown)) }
                    notify(shown)
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
            "Add your Ollama server's address (for example https://192.168.0.20:11434). " +
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

    /**
     * Searches the one catalogue this app still connects to: the listener's own Jellyfin server.
     *
     * There is no provider to choose any more, so there is no provider switcher either — the
     * Search tab asks the server the Connect tab is signed in to, and says so when there is none.
     */
    fun search(query: String) {
        if (_state.value.searching) return
        if (query.isBlank()) {
            _state.update { it.copy(results = emptyList(), searchError = null) }
            return
        }
        if (!jellyfin.connected) {
            _state.update {
                it.copy(
                    results = emptyList(),
                    searchError = "Connect your Jellyfin server on the Connect tab to search its library."
                )
            }
            return
        }
        _state.update { it.copy(searching = true, results = emptyList(), searchError = null) }
        viewModelScope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) { jellyfin.searchTracks(query) }
                _state.update { it.copy(results = tracks) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(searchError = e.message ?: "Search is unavailable.") }
            } finally {
                _state.update {
                    it.copy(searching = false, jellyfinConnected = jellyfin.connected)
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
                notify("That link is not a song this app can play. Leave it empty, or pick a song from your Jellyfin server.")
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

    fun removeOfflineAudio(playlistId: String, itemId: String) {
        if (rejectMutationWhileSyncing()) return
        val item = playlists.find(playlistId)?.items?.firstOrNull { it.id == itemId }
        val localFile = item?.localFile
        if (item == null || localFile == null) {
            notify("That song has no offline copy to remove.")
            return
        }
        if ("$playlistId:$itemId" in _state.value.jellyfinDownloads) {
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
            current.jellyfinDownloads.isNotEmpty() ->
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

    /**
     * A change made in the last few seconds may still be waiting on the backup debounce. Take one
     * snapshot before the view model goes away rather than losing it; the write happens on a
     * short-lived daemon thread, because private storage is not something to touch on the main
     * thread even while the app is closing.
     */
    override fun onCleared() {
        if (managedBackupJob?.isActive == true && localBackups.enabled) {
            val pinsNow = pins.all()
            val playlistsNow = playlists.all()
            val rulesNow = automations.all()
            Thread({
                runCatching { localBackups.write(pinsNow, playlistsNow, rulesNow) }
            }, "soundtrail-backup-flush").apply { isDaemon = true }.start()
        }
        super.onCleared()
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
                        syncRecoveryKey = recoveryKey,
                        syncSessionId = cloudSync.sessionId,
                        syncSessionExpiresAt = cloudSync.sessionExpiresAt,
                        syncRollbackWarning = cloudSync.hasRollbackWarning
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
                        syncRecoveryKey = null,
                        syncSessionId = cloudSync.sessionId,
                        syncSessionExpiresAt = cloudSync.sessionExpiresAt,
                        syncRollbackWarning = cloudSync.hasRollbackWarning
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

    /**
     * Asks the server whether this device's session is still the one it issued, and renews it when
     * expiry is inside a week.
     *
     * Called on app start and before every sync. The throttle to once a day lives in
     * [CloudSyncService.refreshSession], because renewing invalidates the old token the moment the
     * server answers — doing it per request would be a self-inflicted sign-out.
     *
     * A network failure here is deliberately quiet: the check is an optimisation, and the sync that
     * follows reports its own failures. A *lost* session is not quiet, because every later request
     * would fail against a token the server has already revoked.
     */
    private suspend fun refreshSyncSession(force: Boolean = false) {
        if (!cloudSync.connected) return
        val outcome = try {
            withContext(Dispatchers.IO) { cloudSync.refreshSession(force) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        _state.update {
            it.copy(
                syncSessionId = cloudSync.sessionId,
                syncSessionExpiresAt = cloudSync.sessionExpiresAt
            )
        }
        if (outcome == SessionRenewal.Lost) {
            cloudSync.forgetSession()
            _state.update {
                it.copy(
                    syncConnected = false,
                    syncSessionId = null,
                    syncSessions = emptyList(),
                    syncError = "Your sync session ended. Sign in again to keep syncing.",
                    syncFailed = false
                )
            }
            notify("Your sync session ended on the server. Sign in again — your library on this device is untouched.")
        }
    }

    /** The account's signed-in devices, newest first. Ids and dates only; never a token. */
    fun loadSyncSessions() {
        if (_state.value.syncSessionsLoading || !cloudSync.connected) return
        _state.update { it.copy(syncSessionsLoading = true, syncSessionsError = null) }
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { cloudSync.sessions() }
                _state.update { it.copy(syncSessions = list.sessions) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(syncSessionsError = e.message ?: "Could not list the signed-in devices.")
                }
            } finally {
                _state.update { it.copy(syncSessionsLoading = false) }
            }
        }
    }

    /**
     * Signs one device out.
     *
     * If the id turns out to be this device's — which the server says with `signedOut` — this
     * handles it exactly like signing out here: the credentials go, the local library stays.
     */
    fun signOutSyncSession(sessionId: String) {
        if (_state.value.syncBusy) return
        _state.update { it.copy(syncBusy = true) }
        viewModelScope.launch {
            try {
                when (val outcome = withContext(Dispatchers.IO) { cloudSync.signOutSession(sessionId) }) {
                    SessionSignOut.RevokedOther -> {
                        notify("That device was signed out.")
                        val list = withContext(Dispatchers.IO) { cloudSync.sessions() }
                        _state.update { it.copy(syncSessions = list.sessions, syncSessionsError = null) }
                    }
                    SessionSignOut.SignedOutThisDevice -> {
                        _state.update {
                            it.copy(
                                syncConnected = false,
                                syncHasRecoveryKey = cloudSync.hasRecoveryKey,
                                syncSessions = emptyList(),
                                syncSessionId = null,
                                syncRecoveryKey = null
                            )
                        }
                        notify("That was this device's own session, so this device is signed out too. Your library here is untouched.")
                    }
                    is SessionSignOut.Refused -> notify(outcome.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.message ?: "Could not sign that device out.")
            } finally {
                _state.update { it.copy(syncBusy = false) }
            }
        }
    }

    /**
     * Changes the account password.
     *
     * The server signs every session out, this one included, and the token in its response is the
     * only one still working — [CloudSyncService.changePassword] stores that pair before it
     * returns, so the success message below is never claiming a state the app does not have.
     *
     * An unknown outcome claims neither success nor failure. The server may have changed the
     * password and revoked this device, or may never have seen the request, so the answer comes
     * from asking the server rather than from guessing.
     */
    fun changeSyncPassword(current: String, next: String, confirm: String) {
        if (_state.value.syncPasswordBusy) return
        if (next != confirm) {
            notify("The two new passwords do not match.")
            return
        }
        _state.update { it.copy(syncPasswordBusy = true) }
        viewModelScope.launch {
            try {
                when (val outcome = withContext(Dispatchers.IO) { cloudSync.changePassword(current, next) }) {
                    is PasswordChange.Changed -> {
                        // The new token and session id are already stored — changePassword writes
                        // them before it returns — so this state update is a report, not the thing
                        // that makes the sign-in real.
                        _state.update {
                            it.copy(
                                syncSessionId = cloudSync.sessionId,
                                syncSessionExpiresAt = cloudSync.sessionExpiresAt,
                                syncSessions = emptyList(),
                                syncPasswordDone = System.currentTimeMillis()
                            )
                        }
                        val revoked = if (outcome.revokedSessions > 0) {
                            " (${outcome.revokedSessions} ${if (outcome.revokedSessions == 1) "session" else "sessions"})"
                        } else ""
                        notify(
                            "Password changed. All other devices have been signed out$revoked. " +
                                "Your recovery key is unchanged — the password signs you in, it never decrypts anything."
                        )
                    }
                    PasswordChange.WrongCurrentPassword -> notify("That is not your current password.")
                    is PasswordChange.Refused -> notify(outcome.message)
                    PasswordChange.Unknown -> {
                        val check = withContext(Dispatchers.IO) {
                            runCatching { cloudSync.refreshSession(force = true) }.getOrNull()
                        }
                        if (check == SessionRenewal.Lost || !cloudSync.connected) {
                            cloudSync.forgetSession()
                            _state.update {
                                it.copy(syncConnected = false, syncSessions = emptyList(), syncSessionId = null)
                            }
                            notify(
                                "The password change did not come back, and this device is no longer signed in. " +
                                    "The server may have changed it and signed every device out. Sign in again to " +
                                    "find out which — your library on this device is untouched."
                            )
                        } else {
                            _state.update {
                                it.copy(
                                    syncSessionId = cloudSync.sessionId,
                                    syncSessionExpiresAt = cloudSync.sessionExpiresAt
                                )
                            }
                            notify(
                                "The server did not answer, so the password may or may not have changed. This " +
                                    "device is still signed in. If your other devices ask for a password, use the new one."
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.message ?: "Could not change the password.")
            } finally {
                _state.update { it.copy(syncPasswordBusy = false) }
            }
        }
    }

    /**
     * Puts the library back on a server whose history went backwards.
     *
     * Offered only when [CloudSyncService.sync] reported a rollback, and it is an offer rather than
     * something the app does on its own: a server restored from a backup is a legitimate thing for
     * the person who runs it to have done, and re-pushing writes to their server.
     */
    fun repushSyncJournal() {
        if (_state.value.syncRepushBusy) return
        _state.update { it.copy(syncRepushBusy = true) }
        viewModelScope.launch {
            try {
                val uploaded = withContext(Dispatchers.IO) { cloudSync.repushJournal() }
                _state.update {
                    it.copy(
                        syncRollbackWarning = cloudSync.hasRollbackWarning,
                        syncPendingChanges = cloudSync.pendingChangeCount()
                    )
                }
                notify(
                    if (uploaded == 0) "Nothing needed re-pushing — this device has no stored changes."
                    else "Re-pushed $uploaded encrypted ${if (uploaded == 1) "record" else "records"} to the server."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.message ?: "Could not re-push your library to the server.")
            } finally {
                _state.update { it.copy(syncRepushBusy = false) }
            }
        }
    }

    fun syncCloudData() = runCloudSync(showMessages = true)

    fun setCloudSyncAutoEnabled(enabled: Boolean) {
        cloudSync.setAutoSyncEnabled(enabled)
        _state.update { it.copy(syncAutoEnabled = enabled, syncError = null, syncFailed = false) }
        if (enabled && cloudSync.connected && cloudSync.hasRecoveryKey) runCloudSync(showMessages = false)
    }

    private fun runCloudSync(showMessages: Boolean) {
        if (_state.value.syncBusy) return
        if (_state.value.backupBusy || _state.value.offlineAudioBusy ||
            _state.value.jellyfinDownloads.isNotEmpty()
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
                // Renew before pushing rather than after a 401 has already cost the listener a
                // sync: the old token is dead the moment the server answers a renewal.
                refreshSyncSession()
                if (!cloudSync.connected) return@launch
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
                        syncError = result.compactionWarning ?: result.rollbackWarning,
                        syncRollbackWarning = cloudSync.hasRollbackWarning
                    )
                }
                refreshOfflineAudioState()
                if (showMessages) {
                    val warning = result.compactionWarning?.let { " History checkpoint warning: $it" }.orEmpty()
                    // A server whose history went backwards is a warning, not a failure: the sync
                    // completed, the library here is intact, and restoring a server is a legitimate
                    // thing for its owner to have done.
                    val rollback = result.rollbackWarning?.let { " $it" }.orEmpty()
                    notify(
                        "Synced ${counted(result.pins, "pin")}, ${counted(result.playlists, "playlist")}, " +
                            "${counted(result.automations, "automation")}. " +
                            "${result.uploaded} changes sent, ${result.downloaded} received.$warning$rollback"
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
                        syncRecoveryKey = null, syncLastSyncAt = cloudSync.lastSyncAt,
                        syncSessions = emptyList(), syncSessionId = null, syncSessionExpiresAt = 0L,
                        syncRollbackWarning = false
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
                        syncFailed = false, syncError = null,
                        syncSessions = emptyList(), syncSessionId = null, syncSessionExpiresAt = 0L,
                        syncRollbackWarning = false
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

    // ----- Managed backups: rolling snapshots the app keeps for itself -----

    /**
     * Writes a snapshot immediately, even when automatic snapshots are switched off — pressing
     * the button is itself a request to keep this state.
     */
    fun backupLocalNow() {
        if (_state.value.managedBackups.busy) return
        managedBackupJob?.cancel()
        _state.update { it.copy(managedBackups = it.managedBackups.copy(busy = true)) }
        viewModelScope.launch {
            val written = writeManagedBackup(evenWhenDisabled = true)
            notify(
                if (written == null) {
                    "Couldn't write an on-device backup. Check that this device has free storage."
                } else {
                    "On-device backup saved — ${counted(written.pins, "pin")}, " +
                        "${counted(written.playlists, "playlist")}, " +
                        "${counted(written.automations, "automation")}."
                }
            )
        }
    }

    /**
     * Debounced after any store write. The existing job is cancelled, so a long series of edits
     * ends with one snapshot a few seconds after the last one rather than a file per keystroke.
     */
    private fun scheduleManagedBackup() {
        if (!localBackups.enabled) return
        managedBackupJob?.cancel()
        managedBackupJob = viewModelScope.launch {
            delay(MANAGED_BACKUP_DEBOUNCE_MS)
            writeManagedBackup()
        }
    }

    private suspend fun writeManagedBackup(evenWhenDisabled: Boolean = false): ManagedBackup? {
        var written: ManagedBackup? = null
        val kept = withContext(Dispatchers.IO) {
            runCatching {
                written = localBackups.write(
                    pins.all(), playlists.all(), automations.all(),
                    evenWhenDisabled = evenWhenDisabled
                )
            }
            // A listing that fails says nothing about what is on disk, so the screen keeps the
            // list it already had rather than claiming every snapshot disappeared.
            runCatching { localBackups.list() }.getOrNull()
        }
        _state.update {
            it.copy(
                managedBackups = it.managedBackups.copy(
                    backups = kept ?: it.managedBackups.backups,
                    busy = false
                )
            )
        }
        return written
    }

    /** Turns the automatic snapshots on or off; existing files are left alone. */
    fun setManagedBackupsEnabled(enabled: Boolean) {
        localBackups.enabled = enabled
        _state.update { it.copy(managedBackups = it.managedBackups.copy(enabled = enabled)) }
        if (enabled) {
            scheduleManagedBackup()
            notify("Automatic on-device backups on. The newest ${ManagedBackupStore.MAX_KEPT} snapshots are kept.")
        } else {
            managedBackupJob?.cancel()
            notify("Automatic on-device backups off. Snapshots already here are kept.")
        }
    }

    /**
     * Restores one snapshot through the same additive merge as an imported file: anything
     * missing comes back, and anything edited since is kept as it is.
     */
    fun restoreManagedBackup(fileName: String) {
        if (_state.value.managedBackups.busy) return
        _state.update { it.copy(managedBackups = it.managedBackups.copy(busy = true)) }
        viewModelScope.launch {
            try {
                val brought = withContext(Dispatchers.IO) { localBackups.read(fileName) }
                val result = mergeBackupContents(brought)
                notify(
                    if (result.total == 0) {
                        "That snapshot had nothing new — everything in it is already on this device."
                    } else {
                        "Restored ${counted(result.pins, "pin")}, " +
                            "${counted(result.playlists, "playlist")}, " +
                            "${counted(result.songs, "song")}, and " +
                            "${counted(result.automations, "automation")} from the on-device " +
                            "backup. Items already here were kept."
                    }
                )
            } catch (e: BackupException) {
                notify(e.message ?: "Couldn't restore that on-device backup.")
            } catch (e: Exception) {
                notify("Couldn't restore that on-device backup.")
            } finally {
                _state.update {
                    it.copy(managedBackups = it.managedBackups.copy(busy = false))
                }
            }
        }
    }

    fun deleteManagedBackups() {
        if (_state.value.managedBackups.busy) return
        _state.update { it.copy(managedBackups = it.managedBackups.copy(busy = true)) }
        viewModelScope.launch {
            val (removed, left) = withContext(Dispatchers.IO) {
                val gone = runCatching { localBackups.clear() }.getOrDefault(0)
                gone to runCatching { localBackups.list() }.getOrDefault(emptyList())
            }
            _state.update {
                it.copy(managedBackups = it.managedBackups.copy(backups = left, busy = false))
            }
            notify("Removed ${counted(removed, "on-device backup")}.")
        }
    }

    private data class BackupMergeResult(
        val pins: Int,
        val playlists: Int,
        val songs: Int,
        val automations: Int
    ) {
        val total: Int get() = pins + playlists + songs + automations
    }

    /** The one merge path shared by the document picker and the managed snapshots. */
    private fun mergeBackupContents(brought: BackupContents): BackupMergeResult {
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
        return BackupMergeResult(addedPins, addedPlaylists, addedSongs, addedRules)
    }

    // ----- Backup: export and import of everything local -----

    /**
     * Writes every pin, playlist, and automation to the file the user just picked.
     * The file goes wherever the system file picker sends it; nothing is uploaded.
     */
    fun exportBackup(uri: Uri) {
        if (_state.value.syncBusy || _state.value.backupBusy || _state.value.offlineAudioBusy ||
            _state.value.jellyfinDownloads.isNotEmpty()
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
            _state.value.jellyfinDownloads.isNotEmpty()
        ) return
        _state.update { it.copy(backupBusy = true) }
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { readText(uri) }
                    ?: throw BackupException("Couldn't read that file.")
                val brought = withContext(Dispatchers.IO) { BackupFormat.parse(text) }
                val result = mergeBackupContents(brought)
                if (result.total == 0) {
                    notify("Nothing new in that backup — everything it holds is already on this device.")
                } else {
                    notify(
                        "Imported ${counted(result.pins, "new pin")}, " +
                            "${counted(result.playlists, "playlist")}, " +
                            "${counted(result.songs, "song")}, and " +
                            "${counted(result.automations, "automation")}. " +
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
                notify("Pick a song from your Jellyfin server, or attach an audio file you own.")
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
