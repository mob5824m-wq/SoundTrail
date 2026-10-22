package tech.egrie.soundtrail

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import tech.egrie.soundtrail.automation.LocationWatchService
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.data.PlaylistItem
import tech.egrie.soundtrail.integrations.DeviceApps
import tech.egrie.soundtrail.integrations.MusicLink
import tech.egrie.soundtrail.integrations.MusicLinkParser
import tech.egrie.soundtrail.integrations.MusicProvider
import tech.egrie.soundtrail.integrations.YouTubeSearch
import tech.egrie.soundtrail.location.DeviceLocation
import tech.egrie.soundtrail.ui.AssistantActions
import tech.egrie.soundtrail.ui.ListsActions
import tech.egrie.soundtrail.ui.ManagedBackupControls
import tech.egrie.soundtrail.ui.MoodActions
import tech.egrie.soundtrail.ui.Palette
import tech.egrie.soundtrail.ui.PinDraft
import tech.egrie.soundtrail.ui.SoundtrailApp
import tech.egrie.soundtrail.ui.SoundtrailTheme
import tech.egrie.soundtrail.voice.VoiceInput
import tech.egrie.soundtrail.voice.VoiceSpeaker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var model: MainViewModel
    private lateinit var apps: DeviceApps
    private var incomingDraft by mutableStateOf<PinDraft?>(null)
    private var listsOpenSignal by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Palette.background.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Palette.background.toArgb())
        )
        val provider = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))
        model = provider[MainViewModel::class.java]
        apps = DeviceApps(this)
        if (savedInstanceState == null) handleIncoming(intent)

        setContent {
            val state by model.state.collectAsState()
            val permissionRequest = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                if (grants.values.any { it }) model.locate()
                else model.notify("Location not allowed. You can enter coordinates manually.")
            }
            val watchPermissions = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                val located = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                    grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
                if (located) startWatch()
                else model.notify("Location permission is needed to watch for triggers.")
            }
            // Picking the user's own audio file for a playlist song (imported, plays offline).
            var importTarget by mutableStateOf<Pair<String, PlaylistItem>?>(null)
            val audioPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                val (playlistId, item) = importTarget ?: return@rememberLauncherForActivityResult
                importTarget = null
                if (uri != null) model.attachLocalAudio(uri, playlistId, item.id)
            }
            val toggleWatch: (Boolean) -> Unit = { enable ->
                if (!enable) {
                    LocationWatchService.stop(this)
                    model.notify("Watch stopped. Automations stay saved but won't fire.")
                } else {
                    val needed = buildList {
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.filter {
                        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (needed.isEmpty()) startWatch() else watchPermissions.launch(needed.toTypedArray())
                }
            }
            val onLocate: () -> Unit = {
                if (DeviceLocation(this).hasPermission()) model.locate()
                else permissionRequest.launch(arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ))
            }
            // The microphone and the speaker are the activity's: it owns the permission, the
            // recognizer's lifetime, and the main thread both insist on. The view model only
            // holds the words.
            val voiceIn = remember { VoiceInput(this) }
            val speaker = remember { VoiceSpeaker(this) }
            var speakerReady by remember { mutableStateOf(false) }
            DisposableEffect(Unit) {
                speaker.onAvailabilityChanged { ready -> speakerReady = ready }
                onDispose {
                    voiceIn.release()
                    speaker.shutdown()
                }
            }
            // Every answer the app decided to say out loud, as it is decided.
            LaunchedEffect(Unit) { model.speech.collect { line -> speaker.say(line) } }

            val listen: () -> Unit = {
                speaker.stop()
                if (!voiceIn.available) {
                    model.notify("This phone has no speech recognition available.")
                } else {
                    voiceIn.start(
                        onPartial = { heard -> model.setAssistantListening(true, heard) },
                        onFinal = { heard ->
                            model.setAssistantListening(false, "")
                            model.setAssistantInput(heard)
                            model.ask(heard)
                        },
                        onFailure = { sentence ->
                            model.setAssistantListening(false, "")
                            model.notify(sentence)
                        }
                    )
                    model.setAssistantListening(true, "")
                }
            }
            val micPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) listen()
                else model.notify("Microphone access is needed to listen. You can type instead.")
            }
            val toggleMic: () -> Unit = {
                when {
                    voiceIn.isListening -> voiceIn.stop()
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED -> listen()
                    else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

            // The backup file is chosen and written/read entirely through the system picker.
            val exportBackupLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri -> if (uri != null) model.exportBackup(uri) }
            val importBackupLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> if (uri != null) model.importBackup(uri) }
            SoundtrailTheme {
                SoundtrailApp(
                    state = state,
                    messages = model.messages,
                    incomingDraft = incomingDraft,
                    onIncomingConsumed = { incomingDraft = null },
                    openListsSignal = listsOpenSignal,
                    nowPlaying = model.player.state,
                    lists = ListsActions(
                        onToggleWatch = toggleWatch,
                        onToggleAutoSearch = model::setAutoSearchYoutube,
                        onNewPlaylist = model::createPlaylist,
                        onDeletePlaylist = model::deletePlaylist,
                        onAddSong = { playlistId, title, artist, url ->
                            model.addToPlaylist(playlistId, title, artist, url)
                        },
                        onRemoveSong = model::removePlaylistItem,
                        onPlaySong = ::playPlaylistItem,
                        onSearchSong = { item ->
                            // All playback is now in-app; searching YouTube Music externally is optional.
                            // For now, just play the item directly in-app if possible.
                            playPlaylistItem(item)
                        },
                        onGetSong = { item ->
                            // This external action shares a link only.
                            val link = item.url?.let(MusicLinkParser::parse)
                            val target = if (link != null && link.provider == MusicProvider.JELLYFIN) {
                                model.jellyfinWebUrl(link) ?: link.url
                            } else {
                                item.url ?: YouTubeSearch.searchUrl(item.title, item.artist)
                            }
                            if (!apps.shareUrl(target)) model.notify("Nothing can receive this link.")
                        },
                        onDownloadJellyfin = model::downloadJellyfinOffline,
                        onRemoveOfflineAudio = model::removeOfflineAudio,
                        onRemoveOfflineFile = model::removeStoredOfflineAudio,
                        onClearAllOfflineAudio = model::clearAllOfflineAudio,
                        onImportAudio = { playlistId, item ->
                            importTarget = playlistId to item
                            try {
                                audioPicker.launch(arrayOf("audio/*"))
                            } catch (e: Exception) {
                                importTarget = null
                                model.notify("No file picker is available on this device.")
                            }
                        },
                        onSaveRule = { rule -> model.saveAutomation(rule) },
                        onDeleteRule = model::deleteAutomation
                    ),
                    mood = MoodActions(
                        onTextChange = model::setMoodText,
                        onEnergyHint = model::setMoodEnergyHint,
                        onAnalyze = { model.analyzeMood() },
                        onQuickMood = { moodId -> model.analyzeMood(moodId) },
                        onPlayMix = model::playMoodMix,
                        onPlayTrack = model::playMoodTrack,
                        onStopMix = model::stopMoodMix,
                        onSaveMix = model::saveMoodMixAsPlaylist,
                        onImproveTags = { model.improveMoodMix() },
                        onSetTagLookup = model::setMoodTagLookup,
                        onSetAiEnabled = model::setMoodAiEnabled,
                        onConfirmAiConsent = model::confirmMoodAiConsent,
                        onCancelAiConsent = model::cancelMoodAiConsent,
                        onForgetAiConsent = model::forgetMoodAiConsent,
                        onSetAiProvider = model::setMoodAiProvider,
                        onSetAiModel = model::setMoodAiModel,
                        onSetOllamaUrl = model::setMoodOllamaUrl,
                        onSetOllamaModel = model::setMoodOllamaModel,
                        onSetOpenAiUrl = model::setMoodOpenAiUrl,
                        onSetOpenAiModel = model::setMoodOpenAiModel,
                        onUseArena = model::useMoodArenaPreset,
                        onDetectModels = model::detectMoodModels,
                        onTestAi = model::testMoodAi,
                        onSaveAiKey = model::saveMoodAiKey,
                        onClearAiKey = model::clearMoodAiKey,
                        onSetSearchTarget = model::setMoodSearchTarget,
                        onPlayFound = model::playMoodFound,
                        onPlayAllFound = model::playAllMoodFound,
                        onOpenFound = model::openMoodFound,
                        onOpenInYouTubeMusic = {
                            if (!model.openMoodInYouTubeMusic()) {
                                model.notify("No YouTube Music app or browser found.")
                            }
                        }
                    ),
                    onLocate = onLocate,
                    onSearch = model::search,
                    onSelectSearchProvider = model::selectSearchProvider,
                    onLookup = model::lookupSong,
                    onConnect = ::connectSpotify,
                    onDisconnect = model::disconnectSpotify,
                    onConnectSoundcloud = ::connectSoundcloud,
                    onDisconnectSoundcloud = model::disconnectSoundcloud,
                    onConnectJellyfin = model::connectJellyfin,
                    onDisconnectJellyfin = model::disconnectJellyfin,
                    onOpenJellyfin = { if (!apps.openWeb(model.jellyfinServerUrl())) model.notify("No browser found.") },
                    onConnectYouTubeOAuth = ::connectYouTubeOAuth,
                    onDisconnectYouTubeData = model::disconnectYouTubeData,
                    onOpenYouTubeSetup = {
                        if (!apps.openWeb("https://console.cloud.google.com/apis/credentials")) {
                            model.notify("No browser found.")
                        }
                    },
                    onImportYouTubePlaylists = model::importYouTubePlaylists,
                    onSetYouTubeAutoImport = model::setYouTubeAutoImport,
                    backupBusy = state.backupBusy,
                    onExportBackup = {
                        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                        try {
                            exportBackupLauncher.launch("soundtrail-backup-$stamp.json")
                        } catch (e: Exception) {
                            model.notify("No file picker is available on this device.")
                        }
                    },
                    onImportBackup = {
                        try {
                            importBackupLauncher.launch(
                                arrayOf("application/json", "text/plain", "application/octet-stream")
                            )
                        } catch (e: Exception) {
                            model.notify("No file picker is available on this device.")
                        }
                    },
                    managedBackups = ManagedBackupControls(
                        status = state.managedBackups,
                        onBackupNow = model::backupLocalNow,
                        onRestore = model::restoreManagedBackup,
                        onToggleEnabled = model::setManagedBackupsEnabled,
                        onDeleteAll = model::deleteManagedBackups
                    ),
                    onCreateCloudSyncAccount = model::createCloudSyncAccount,
                    onSignInCloudSync = model::signInCloudSync,
                    onUnlockCloudSync = model::unlockCloudSync,
                    onSyncCloudData = model::syncCloudData,
                    onShowCloudRecoveryKey = model::showCloudRecoveryKey,
                    onForgetCloudRecoveryKey = model::forgetCloudRecoveryKey,
                    onDismissCloudRecoveryKey = model::dismissCloudRecoveryKey,
                    onSetCloudSyncAutoEnabled = model::setCloudSyncAutoEnabled,
                    onDisconnectCloudSync = model::disconnectCloudSync,
                    onDeleteCloudSyncAccount = model::deleteCloudSyncAccount,
                    onTogglePlayback = model::togglePlayback,
                    onStopPlayback = model::stopPlayback,
                    onPlay = ::play,
                    onMap = ::showMap,
                    onDelete = model::deletePin,
                    onOpenSpotify = { if (!apps.openSpotify()) model.notify("No app can open Spotify.") },
                    onOpenYoutube = { if (!apps.openYouTubeMusic()) model.notify("No app can open YouTube Music.") },
                    onSearchYoutubeMusic = { query ->
                        if (!apps.searchYouTubeMusic(query)) {
                            model.notify("No YouTube Music app or browser found.")
                        }
                    },
                    onPlayTrack = { track ->
                        // The whole track in Spotify: a hand-off to an app the listener is
                        // already signed in to, which is the only way a third-party app can
                        // play a full Spotify song rather than its preview.
                        val link = MusicLinkParser.parse(track.url)
                        if (link == null || !apps.play(link)) {
                            model.notify("Nothing can open ${track.provider.label} on this device.")
                        }
                    },
                    onOpenYoutubeLibrary = {
                        if (!apps.openYouTubeMusicLibrary()) {
                            model.notify("No YouTube Music app or browser found.")
                        }
                    },
                    onOpenSoundcloud = { if (!apps.openSoundCloud()) model.notify("No app can open SoundCloud.") },
                    onOpenDashboard = {
                        if (!apps.openWeb("https://developer.spotify.com/dashboard")) model.notify("No browser found.")
                    },
                    onOpenMicroG = {
                        if (!apps.openWeb("https://microg.org/")) model.notify("No browser found.")
                    },
                    onOpenMicroGRe = {
                        if (!apps.openWeb("https://github.com/MorpheApp/MicroG-RE")) model.notify("No browser found.")
                    },
                    onOpenSoundcloudApps = {
                        if (!apps.openWeb("https://developers.soundcloud.com/docs/api/register-app")) {
                            model.notify("No browser found.")
                        }
                    },
                    assistant = AssistantActions(
                        onInput = model::setAssistantInput,
                        onAsk = { text -> model.ask(text) },
                        onMic = toggleMic,
                        onSetSpeakReplies = model::setAssistantSpeakReplies,
                        onPlayHit = model::playAssistantHit,
                        onOpenHitInYouTubeMusic = { hit ->
                            val url = hit.musicUrl
                            if (url == null || !apps.openYouTubeMusicSong(url)) {
                                model.notify("Nothing can open YouTube Music on this device.")
                            }
                        },
                        // Soundtrail fills this in itself: the Mood tab lives inside the app
                        // shell, and the shell is what knows which tab is showing.
                        onOpenMood = {},
                        micAvailable = voiceIn.available,
                        speakerAvailable = speakerReady
                    ),
                    onSavePin = model::addPin,
                    onNotice = model::notify
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.refreshDeviceStatus()
    }

    // Deliberately no stopPlayback() in onDestroy: Jellyfin streaming now continues in the
    // background under PlaybackService's notification. The bar's Stop button, the
    // notification's Stop action, or disconnecting the server ends it.

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun startWatch() {
        LocationWatchService.start(this)
        model.notify("Watching place, motion, and time from the notification. Tap a trigger to play.")
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent?.hasExtra(LocationWatchService.EXTRA_OPEN_LISTS) == true) listsOpenSignal++
        intent?.getStringExtra(LocationWatchService.EXTRA_PLAY_PLAYLIST)?.let { playlistId ->
            listsOpenSignal++
            val first = model.state.value.playlists.find { it.id == playlistId }?.items?.firstOrNull()
            if (first == null) model.notify("That playlist is empty or missing.")
            else playPlaylistItem(first)
        }
        when (intent?.action) {
            Intent.ACTION_VIEW -> if (intent.data?.scheme == "app.soundtrail") {
                intent.data?.let { uri ->
                    // YouTube OAuth uses Google's scheme:/path form (host-less) or scheme://host.
                    when {
                        uri.host == "spotify-auth" -> model.finishSpotify(uri)
                        uri.host == "soundcloud-auth" -> model.finishSoundcloud(uri)
                        uri.host == "youtube-auth" || uri.path == "/youtube-auth" -> model.finishYouTubeOAuth(uri)
                    }
                }
                clearHandledIntent()
            }
            Intent.ACTION_SEND -> if (intent.type == "text/plain") {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val link = MusicLinkParser.fromText(text)
                if (link != null) incomingDraft = PinDraft(url = link.url)
                else model.notify("Share a Spotify, SoundCloud, or YouTube Music song link.")
                clearHandledIntent()
            }
        }
    }

    private fun clearHandledIntent() {
        // Avoid consuming a single-use OAuth code or share intent again on activity recreation.
        setIntent(Intent(this, MainActivity::class.java).apply { action = Intent.ACTION_MAIN })
    }

    private fun connectSpotify(id: String) {
        try {
            val uri = model.beginSpotify(id)
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            model.cancelSpotify()
            model.notify("Install a browser to sign in with Spotify.")
        } catch (e: SecurityException) {
            model.cancelSpotify()
            model.notify("Could not open Spotify sign-in in a browser.")
        } catch (e: Exception) {
            model.notify(e.message ?: "Could not start Spotify sign-in.")
        }
    }

    private fun connectSoundcloud(id: String, secret: String) {
        try {
            val uri = model.beginSoundcloud(id, secret)
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            model.cancelSoundcloud()
            model.notify("Install a browser to sign in with SoundCloud.")
        } catch (e: SecurityException) {
            model.cancelSoundcloud()
            model.notify("Could not open SoundCloud sign-in in a browser.")
        } catch (e: Exception) {
            model.notify(e.message ?: "Could not start SoundCloud sign-in.")
        }
    }

    /**
     * Browser-based YouTube OAuth with the user's own Desktop client ID. It needs no Google
     * services, account, or SDK on the device: only a browser.
     */
    private fun connectYouTubeOAuth(id: String) {
        try {
            val uri = model.beginYouTubeOAuth(id)
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            model.cancelYouTubeOAuth()
            model.notify("Install a browser to sign in with YouTube.")
        } catch (e: SecurityException) {
            model.cancelYouTubeOAuth()
            model.notify("Could not open YouTube sign-in in a browser.")
        } catch (e: Exception) {
            model.notify(e.message ?: "Could not start YouTube sign-in.")
        }
    }

    /** All music now plays directly in the app via the unified player. */
    private fun play(pin: MusicPin) {
        model.playPin(pin)
    }

    /** Plays directly in-app: local files, Jellyfin, Spotify previews, SoundCloud, YouTube via server. */
    private fun playPlaylistItem(item: PlaylistItem) {
        model.playPlaylistItemUnified(item)
    }

    private fun showMap(point: GeoPoint) {
        if (!apps.showOnMap(point)) model.notify("No maps app or browser found.")
    }
}
