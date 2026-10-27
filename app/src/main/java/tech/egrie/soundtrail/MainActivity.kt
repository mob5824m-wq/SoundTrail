package tech.egrie.soundtrail

import android.Manifest
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
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
import tech.egrie.soundtrail.integrations.MusicLinkParser
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

    /**
     * What to run once the device unlock has been confirmed.
     *
     * Held here rather than captured by the launcher, because the launcher has to be registered
     * before `onCreate` and cannot close over a lambda that does not exist yet. If the process is
     * recreated while the system's unlock screen is up, this is lost and nothing is shown when the
     * result comes back — which is the safe direction, and one more tap away.
     */
    private var pendingUnlockAction: (() -> Unit)? = null

    private val unlockLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingUnlockAction
        pendingUnlockAction = null
        // Only a confirmed credential runs the action. A cancellation runs nothing and says
        // nothing: backing out of an unlock prompt is a decision, not a failure to report.
        if (result.resultCode == RESULT_OK) action?.invoke()
    }

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
            // The recovery key decrypts the whole synced library, so while it is on screen this
            // window is FLAG_SECURE: no screenshot, no screen recording, no thumbnail in recents.
            // It is cleared the moment the key is hidden and again on dispose, so leaving the
            // screen with the key showing cannot leave the rest of the app un-capturable.
            val recoveryKeyVisible = state.syncRecoveryKey != null
            DisposableEffect(recoveryKeyVisible) {
                if (recoveryKeyVisible) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            }

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
                        onNewPlaylist = model::createPlaylist,
                        onDeletePlaylist = model::deletePlaylist,
                        onAddSong = { playlistId, title, artist, url ->
                            model.addToPlaylist(playlistId, title, artist, url)
                        },
                        onRemoveSong = model::removePlaylistItem,
                        onPlaySong = ::playPlaylistItem,
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
                        onPlayAllFound = model::playAllMoodFound
                    ),
                    onLocate = onLocate,
                    onSearch = model::search,
                    onLookup = model::lookupSong,
                    onConnectJellyfin = model::connectJellyfin,
                    onDisconnectJellyfin = model::disconnectJellyfin,
                    onOpenJellyfin = { if (!apps.openWeb(model.jellyfinServerUrl())) model.notify("No browser found.") },
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
                    // Re-displaying a *stored* key is gated on this device's own unlock. The key
                    // shown once at account creation is not: nobody has had a chance to save it
                    // yet, and putting a prompt in front of that would be a way to lose it.
                    onShowCloudRecoveryKey = {
                        requireUnlock("your saved recovery key") { model.showCloudRecoveryKey() }
                    },
                    onCopyRecoveryKey = ::copyRecoveryKey,
                    onForgetCloudRecoveryKey = model::forgetCloudRecoveryKey,
                    onDismissCloudRecoveryKey = model::dismissCloudRecoveryKey,
                    onSetCloudSyncAutoEnabled = model::setCloudSyncAutoEnabled,
                    onLoadSyncSessions = model::loadSyncSessions,
                    onSignOutSyncSession = model::signOutSyncSession,
                    onChangeSyncPassword = model::changeSyncPassword,
                    onRepushSyncJournal = model::repushSyncJournal,
                    onDisconnectCloudSync = model::disconnectCloudSync,
                    onDeleteCloudSyncAccount = model::deleteCloudSyncAccount,
                    onTogglePlayback = model::togglePlayback,
                    onStopPlayback = model::stopPlayback,
                    onPlay = ::play,
                    onMap = ::showMap,
                    onDelete = model::deletePin,
                    // Every search result is a song on the listener's own server, so a tap plays
                    // it here through the same player as everything else. Nothing is handed off.
                    onPlayTrack = model::playTrackResult,
                    assistant = AssistantActions(
                        onInput = model::setAssistantInput,
                        onAsk = { text -> model.ask(text) },
                        onMic = toggleMic,
                        onSetSpeakReplies = model::setAssistantSpeakReplies,
                        onPlayHit = model::playAssistantHit,
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
        // No authorization redirect is caught here any more: the browser sign-in flows that
        // needed an app.soundtrail:// deep link belonged to the removed streaming services.
        // What is left is a shared piece of text, which can still start a pin.
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val link = MusicLinkParser.fromText(text)
            if (link != null) incomingDraft = PinDraft(url = link.url)
            else model.notify("That shared text is not a song this app can play. Pick a song from your Jellyfin server instead.")
            clearHandledIntent()
        }
    }

    private fun clearHandledIntent() {
        // Avoid consuming a share intent a second time on activity recreation.
        setIntent(Intent(this, MainActivity::class.java).apply { action = Intent.ACTION_MAIN })
    }

    /**
     * Runs [action] only after the listener has unlocked this device.
     *
     * The PIN, pattern or password the listener already set, on the system's own confirm-credential
     * screen, which offers an enrolled biometric first on a device that has one. This is the gate on
     * re-displaying a stored recovery key: the
     * key decrypts the entire synced library, so "the app happened to be open" is not sufficient
     * authority to read it out, and a phone handed to somebody else must not become a way to copy
     * it.
     *
     * A device with no screen lock at all fails **closed**. There is nothing to authenticate
     * against, so the key is not shown and the listener is told what to set up; showing it anyway
     * would make the gate decorative.
     *
     * This is the platform's own confirm-device-credential screen through [KeyguardManager], not
     * `androidx.biometric`. The library would do the same job but drags in a `FragmentActivity`
     * base class and an AppCompat theme parent, and this app adds no dependency for it: what the
     * gate has to prove is that the person holding the phone is the person who locked it, and the
     * keyguard proves exactly that, with the biometric the listener enrolled offered first on a
     * device that has one.
     */
    private fun requireUnlock(what: String, action: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isDeviceSecure) {
            model.notify(
                "This device has no screen lock, so $what cannot be shown again here. Set a PIN, " +
                    "pattern or password in Android's settings, or use the copy you saved when the " +
                    "account was created."
            )
            return
        }
        val prompt = keyguard.createConfirmDeviceCredentialIntent(
            "Unlock Soundtrail",
            "Confirm it is you to show $what."
        )
        if (prompt == null) {
            // No credential to confirm against, so the key is not shown: a gate that opens itself
            // when it cannot be locked is not a gate.
            model.notify("This device cannot ask for its unlock, so $what cannot be shown again here.")
            return
        }
        pendingUnlockAction = action
        unlockLauncher.launch(prompt)
    }

    /**
     * Copies the recovery key as a *sensitive* clipboard item, then clears it again.
     *
     * On Android 13 and later `EXTRA_IS_SENSITIVE` is what keeps the system from showing the key in
     * the clipboard preview and tells whatever reads it next what it is holding. Below 13 there is
     * no such flag, so the copy still works and the delayed clear is the only protection available
     * — hence "where Android allows it" in the wording the listener sees.
     *
     * The clear is best-effort and has to be: Android 10 and later refuse clipboard writes from an
     * app that is not in focus, so a listener who switches to their password manager before the
     * minute is up keeps the key, which is what they were doing. It is attempted only when the clip
     * still holds this exact key, so it can never overwrite something copied afterwards.
     */
    private fun copyRecoveryKey(key: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            model.notify("This device has no clipboard to copy to.")
            return
        }
        val clip = ClipData.newPlainText("Soundtrail recovery key", key)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        clipboard.setPrimaryClip(clip)
        model.notify("Recovery key copied. Save it somewhere private — it leaves the clipboard in a minute.")
        window.decorView.postDelayed({
            runCatching {
                val current = clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                if (current == key) clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, CLIPBOARD_CLEAR_AFTER_MS)
    }

    /** All music now plays directly in the app via the unified player. */
    private fun play(pin: MusicPin) {
        model.playPin(pin)
    }

    /** Plays directly in-app: a song from the listener's Jellyfin server, or an attached file. */
    private fun playPlaylistItem(item: PlaylistItem) {
        model.playPlaylistItemUnified(item)
    }

    private fun showMap(point: GeoPoint) {
        if (!apps.showOnMap(point)) model.notify("No maps app or browser found.")
    }

    private companion object {
        /** How long a copied recovery key stays on the clipboard before the clear is attempted. */
        const val CLIPBOARD_CLEAR_AFTER_MS = 60_000L
    }
}
