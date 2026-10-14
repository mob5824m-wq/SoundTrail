package tech.egrie.soundtrail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import tech.egrie.soundtrail.AppState
import tech.egrie.soundtrail.PinInput
import tech.egrie.soundtrail.SearchProvider
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.integrations.TrackResult
import tech.egrie.soundtrail.playback.NowPlaying
import java.io.Serializable
import java.util.Locale

internal data class PinDraft(
    val nonce: Long = System.nanoTime(),
    val title: String = "",
    val artist: String = "",
    val url: String = "",
    /** Set when the pin was started from a long-press on the map; prefills the editor. */
    val lat: Double? = null,
    val lon: Double? = null
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

private enum class Tab(val label: String) {
    HOME("Explore"), SEARCH("Search"), MOOD("Mood"), PINS("Pins"), LISTS("Lists"), CONNECT("Connect")
}

private data class PinWithDistance(val pin: MusicPin, val distanceMeters: Double?)

/** Calculate each distance once; screens remember the result until pins or location change. */
private fun orderPins(pins: List<MusicPin>, location: GeoPoint?): List<PinWithDistance> {
    val withDistance = pins.map { pin ->
        PinWithDistance(pin, location?.distanceTo(pin.point))
    }
    return if (location == null) withDistance else {
        withDistance.sortedBy { it.distanceMeters ?: Double.POSITIVE_INFINITY }
    }
}

@Composable
internal fun SoundtrailApp(
    state: AppState,
    messages: Flow<String>,
    incomingDraft: PinDraft?,
    onIncomingConsumed: () -> Unit,
    openListsSignal: Int,
    nowPlaying: StateFlow<NowPlaying?>,
    lists: ListsActions,
    mood: MoodActions,
    onLocate: () -> Unit,
    onSearch: (String) -> Unit,
    onSelectSearchProvider: (SearchProvider) -> Unit,
    onLookup: (String, String) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onConnectSoundcloud: (String, String) -> Unit,
    onDisconnectSoundcloud: () -> Unit,
    onConnectJellyfin: (String, String, String) -> Unit,
    onDisconnectJellyfin: () -> Unit,
    onOpenJellyfin: () -> Unit,
    onConnectYouTubeData: () -> Unit,
    onConnectYouTubeOAuth: (String) -> Unit,
    onDisconnectYouTubeData: () -> Unit,
    onOpenYouTubeSetup: () -> Unit,
    onImportYouTubePlaylists: () -> Unit,
    onSetYouTubeAutoImport: (Boolean) -> Unit,
    backupBusy: Boolean,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onCreateCloudSyncAccount: (server: String, email: String, password: String) -> Unit,
    onSignInCloudSync: (server: String, email: String, password: String) -> Unit,
    onUnlockCloudSync: (String) -> Unit,
    onSyncCloudData: () -> Unit,
    onShowCloudRecoveryKey: () -> Unit,
    onForgetCloudRecoveryKey: () -> Unit,
    onDismissCloudRecoveryKey: () -> Unit,
    onSetCloudSyncAutoEnabled: (Boolean) -> Unit,
    onDisconnectCloudSync: () -> Unit,
    onDeleteCloudSyncAccount: () -> Unit,
    onTogglePlayback: () -> Unit,
    onStopPlayback: () -> Unit,
    onPlay: (MusicPin) -> Unit,
    onMap: (GeoPoint) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSpotify: () -> Unit,
    onOpenYoutube: () -> Unit,
    onSearchYoutubeMusic: (String) -> Unit,
    onOpenYoutubeLibrary: () -> Unit,
    onOpenSoundcloud: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenMicroG: () -> Unit,
    onOpenMicroGRe: () -> Unit,
    onOpenSoundcloudApps: () -> Unit,
    onSavePin: (PinInput) -> Boolean,
    onNotice: (String) -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    // Keep an unfinished pin editor intact across rotation and process recreation.
    var draft by rememberSaveable { mutableStateOf<PinDraft?>(null) }
    var pendingDelete by remember { mutableStateOf<MusicPin?>(null) }
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(messages) { messages.collectLatest { snackbars.showSnackbar(it) } }
    LaunchedEffect(incomingDraft?.nonce) {
        if (incomingDraft != null) {
            draft = incomingDraft
            onIncomingConsumed()
        }
    }
    LaunchedEffect(openListsSignal) { if (openListsSignal > 0) tab = Tab.LISTS }
    BackHandler(draft != null) { draft = null }

    pendingDelete?.let { pin ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove this pin?") },
            text = { Text("${pin.title} will be removed from this device. Your music account is not affected.") },
            confirmButton = {
                TextButton(onClick = { onDelete(pin.id); pendingDelete = null }) {
                    Text("Remove", color = Palette.coral)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep it") }
            },
            containerColor = Palette.elevated
        )
    }

    Scaffold(
        containerColor = Palette.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = {
            SnackbarHost(snackbars) { data ->
                Snackbar(data, containerColor = Palette.elevated, contentColor = Palette.text)
            }
        },
        bottomBar = {
            Column {
                // Streaming from the user's own Jellyfin server keeps its controls in the app.
                NowPlayingDock(nowPlaying, onTogglePlayback, onStopPlayback)
                if (draft == null) BottomTabs(tab) { tab = it }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val editor = draft
            if (editor != null) {
                PinEditor(
                    draft = editor,
                    currentLocation = state.location,
                    locating = state.locating,
                    onBack = { draft = null },
                    onLocate = onLocate,
                    onSave = { input ->
                        if (onSavePin(input)) { draft = null; tab = Tab.PINS; true } else false
                    }
                )
            } else when (tab) {
                Tab.HOME -> HomeScreen(
                    state, onNewPin = { draft = PinDraft() }, onLocate = onLocate,
                    onSeePins = { tab = Tab.PINS },
                    onPlay = onPlay, onMap = onMap, onDelete = { pendingDelete = it },
                    onOpenMood = { tab = Tab.MOOD },
                    onOpenYoutube = onOpenYoutube
                )
                Tab.SEARCH -> MusicSearchScreen(
                    state, onSearch, onSelectProvider = onSelectSearchProvider,
                    onConnect = { tab = Tab.CONNECT },
                    onSelectTrack = { draft = PinDraft(title = it.title, artist = it.artist, url = it.url) },
                    onAddLink = { draft = PinDraft(url = it) },
                    onLookup = onLookup,
                    onSelectLookup = {
                        draft = PinDraft(title = it.title, artist = it.artist, url = it.url.orEmpty())
                    },
                    onSearchYoutubeMusic = onSearchYoutubeMusic,
                    onOpenYoutubeLibrary = onOpenYoutubeLibrary,
                    onNotice = onNotice
                )
                Tab.PINS -> PinsScreen(
                    state, onNewPin = { draft = PinDraft() }, onLocate = onLocate,
                    onPlay = onPlay, onMap = onMap, onDelete = { pendingDelete = it },
                    onDropPin = { point ->
                        draft = PinDraft(lat = point.latitude, lon = point.longitude)
                    }
                )
                Tab.MOOD -> MoodScreen(state, mood, onNotice)
                Tab.LISTS -> ListsScreen(state, lists, onNotice)
                Tab.CONNECT -> ConnectionsScreen(
                    state, onConnect, onDisconnect, onConnectSoundcloud, onDisconnectSoundcloud,
                    onOpenSpotify, onOpenYoutube, onOpenSoundcloud,
                    onOpenDashboard, onOpenMicroG, onOpenMicroGRe, onOpenSoundcloudApps,
                    onConnectJellyfin, onDisconnectJellyfin, onOpenJellyfin,
                    onConnectYouTubeData, onConnectYouTubeOAuth, onDisconnectYouTubeData, onOpenYouTubeSetup,
                    onImportYouTubePlaylists, onSetYouTubeAutoImport,
                    backupBusy, onExportBackup, onImportBackup,
                    state.syncBusy, onCreateCloudSyncAccount, onSignInCloudSync,
                    onUnlockCloudSync, onSyncCloudData, onShowCloudRecoveryKey,
                    onForgetCloudRecoveryKey, onDismissCloudRecoveryKey, onSetCloudSyncAutoEnabled,
                    onDisconnectCloudSync, onDeleteCloudSyncAccount
                )
            }
        }
    }
}

/** Keep frequent player-progress updates scoped to this small bottom-bar subtree. */
@Composable
private fun NowPlayingDock(
    state: StateFlow<NowPlaying?>,
    onToggle: () -> Unit,
    onStop: () -> Unit
) {
    val current by state.collectAsState()
    current?.let { NowPlayingBar(it, onToggle = onToggle, onStop = onStop) }
}

@Composable
private fun BottomTabs(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.background).navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Tab.entries.forEach { tab ->
            val selectedTab = tab == selected
            val icon = when (tab) {
                Tab.HOME -> Icons.Rounded.Home
                Tab.SEARCH -> Icons.Rounded.Search
                Tab.MOOD -> Icons.Rounded.Favorite
                Tab.PINS -> Icons.Rounded.Bookmark
                Tab.LISTS -> Icons.Rounded.QueueMusic
                Tab.CONNECT -> Icons.Rounded.Settings
            }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(if (selectedTab) Palette.elevated else Color.Transparent)
                    .clickable { onSelect(tab) }.padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, contentDescription = tab.label,
                    tint = if (selectedTab) Palette.primary else Palette.muted,
                    modifier = Modifier.size(23.dp))
                Spacer(Modifier.height(3.dp))
                Text(tab.label, color = if (selectedTab) Palette.text else Palette.muted,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    state: AppState,
    onNewPin: () -> Unit,
    onLocate: () -> Unit,
    onSeePins: () -> Unit,
    onPlay: (MusicPin) -> Unit,
    onMap: (GeoPoint) -> Unit,
    onDelete: (MusicPin) -> Unit,
    onOpenMood: () -> Unit,
    onOpenYoutube: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        BrandHeader()
        Spacer(Modifier.height(20.dp))
        HomeHero(onNewPin)
        Spacer(Modifier.height(14.dp))
        MoodBanner(state, onOpenMood, onOpenYoutube)
        Spacer(Modifier.height(14.dp))
        LocationBanner(state.location, state.locating, onLocate)
        Spacer(Modifier.height(20.dp))
        SectionHeading(
            title = if (state.location != null && state.pins.isNotEmpty()) "Around you" else "Your soundspots",
            action = if (state.pins.isNotEmpty()) "View all" else null,
            onAction = if (state.pins.isNotEmpty()) onSeePins else null
        )
        Spacer(Modifier.height(10.dp))
        val nearby = remember(state.pins, state.location) {
            orderPins(state.pins, state.location)
        }
        if (nearby.isEmpty()) {
            SurfaceCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LocationOn, null, tint = Palette.primary,
                        modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("No soundspots yet", style = MaterialTheme.typography.titleMedium)
                        Text("Add a song to start your collection.",
                            style = MaterialTheme.typography.bodyMedium, color = Palette.secondary)
                    }
                }
            }
        } else {
            nearby.take(2).forEach { nearbyPin ->
                val pin = nearbyPin.pin
                PinCard(pin, nearbyPin.distanceMeters,
                    onPlay = { onPlay(pin) }, onMap = { onMap(pin.point) }, onDelete = { onDelete(pin) })
                Spacer(Modifier.height(10.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun HomeHero(onNewPin: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF344238), Color(0xFF242D29), Color(0xFF20252C))))
    ) {
        Canvas(Modifier.matchParentSize()) {
            val center = Offset(size.width * .88f, size.height * .36f)
            for (radius in listOf(.17f, .31f, .45f)) {
                drawCircle(Palette.primary.copy(alpha = .16f), radius = size.width * radius,
                    center = center, style = Stroke(width = 1.dp.toPx()))
            }
            drawCircle(Palette.primary.copy(alpha = .35f), radius = 6.dp.toPx(),
                center = Offset(size.width * .73f, size.height * .18f))
            drawCircle(Palette.coral.copy(alpha = .7f), radius = 4.dp.toPx(),
                center = Offset(size.width * .66f, size.height * .71f))
            drawCircle(Palette.primary.copy(alpha = .75f), radius = 3.dp.toPx(),
                center = Offset(size.width * .95f, size.height * .78f))
        }
        Column(Modifier.fillMaxSize().padding(23.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Eyebrow("MUSIC, TIED TO PLACE")
            Column {
                Text("Every place\nhas a song.", style = MaterialTheme.typography.displayLarge,
                    color = Palette.text)
                Spacer(Modifier.height(8.dp))
                Text("Save a song to remember the moment.",
                    style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD2DCCE))
            }
            PrimaryButton("Add a soundspot  ↗", onNewPin)
        }
    }
}

/**
 * The two things a listener asks for most often, one tap from the home screen: "match my mood",
 * and "open YouTube Music".
 *
 * The YouTube Music button is deliberately independent of the optional Google connection. Playing
 * music in the app you are already signed in to, or letting a browser open music.youtube.com, is
 * not an API call — so it keeps working across app updates, reinstalls, and revoked grants, with
 * no sign-in prompt ever.
 */
@Composable
private fun MoodBanner(state: AppState, onOpenMood: () -> Unit, onOpenYoutube: () -> Unit) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Palette.primary.copy(alpha = .15f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Favorite, null, tint = Palette.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("What's your mood?", style = MaterialTheme.typography.titleMedium)
                val mood = state.mood
                Text(
                    when {
                        mood.analyzing -> "Reading it…"
                        mood.profile != null && mood.mix.isNotEmpty() ->
                            "${mood.profile.label} \u00b7 ${mood.mix.size} songs matched"
                        mood.profile != null -> "${mood.profile.label} \u00b7 add songs to fill the mix"
                        else -> "Say how you feel; get a mix from your own library."
                    },
                    color = Palette.secondary, style = MaterialTheme.typography.bodyMedium
                )
            }
            TextButton(onClick = onOpenMood) {
                Text(if (state.mood.profile == null) "Open" else "See mix", color = Palette.primary)
            }
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton("Open YouTube Music  \u2197", onOpenYoutube, Modifier.fillMaxWidth())
        Text(
            if (state.youtubeAppInstalled) {
                "Opens the app on this device. No Google sign-in is involved — the read-only " +
                    "connection on the Connect tab is only for playlist import and search."
            } else {
                "Opens music.youtube.com in a browser. No Google sign-in is involved."
            },
            color = Palette.muted, style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun LocationBanner(point: GeoPoint?, locating: Boolean, onLocate: () -> Unit) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Palette.primaryDark, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MyLocation, null, tint = Palette.primary, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (point == null) "Find your location" else "You’re here",
                    style = MaterialTheme.typography.titleMedium)
                Text(if (point == null) "Only used when you ask" else
                    String.format(Locale.getDefault(), "%.4f, %.4f", point.latitude, point.longitude),
                    style = MaterialTheme.typography.bodyMedium, color = Palette.secondary)
            }
            TextButton(onClick = onLocate, enabled = !locating) {
                if (locating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (point == null) "Locate" else "Refresh", color = Palette.primary)
            }
        }
    }
}

@Composable
private fun PinsScreen(
    state: AppState, onNewPin: () -> Unit, onLocate: () -> Unit,
    onPlay: (MusicPin) -> Unit, onMap: (GeoPoint) -> Unit, onDelete: (MusicPin) -> Unit,
    onDropPin: (GeoPoint) -> Unit
) {
    var showingMap by rememberSaveable { mutableStateOf(false) }
    val ordered = remember(state.pins, state.location) { orderPins(state.pins, state.location) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(18.dp))
        BrandHeader()
        Spacer(Modifier.height(20.dp))
        Eyebrow("YOUR COLLECTION")
        Spacer(Modifier.height(5.dp))
        Text("Your soundspots", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${state.pins.size} saved ${if (state.pins.size == 1) "soundspot" else "soundspots"} · " +
                    if (state.location == null) "newest first" else "nearest first",
                Modifier.weight(1f), color = Palette.secondary,
                style = MaterialTheme.typography.bodyMedium
            )
            ListMapToggle(showingMap) { showingMap = it }
        }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("+  Add a song pin", onNewPin, Modifier.fillMaxWidth())
        if (state.location == null && state.pins.isNotEmpty() && !showingMap) {
            TextButton(onClick = onLocate) { Text("Locate me to sort by distance", color = Palette.primary) }
        }
        Spacer(Modifier.height(12.dp))
        if (showingMap) {
            PinMapView(
                pins = state.pins,
                userLocation = state.location,
                onPlay = onPlay,
                onMap = onMap,
                onDelete = onDelete,
                onDropPin = onDropPin,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        } else {
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                if (ordered.isEmpty()) {
                    item {
                        SurfaceCard(Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Bookmark, null, tint = Palette.lilac,
                                modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("Your collection starts here", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(6.dp))
                            Text("Save a Spotify, SoundCloud, or YouTube Music song at your current location — or enter a place yourself.",
                                color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(ordered, key = { it.pin.id }) { nearbyPin ->
                    val pin = nearbyPin.pin
                    PinCard(pin, nearbyPin.distanceMeters,
                        onPlay = { onPlay(pin) }, onMap = { onMap(pin.point) }, onDelete = { onDelete(pin) })
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

/** List or map — the map is where pins become a place instead of a feed. */
@Composable
private fun ListMapToggle(showMap: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.surface)
            .border(1.dp, Palette.border, RoundedCornerShape(12.dp))
    ) {
        listOf(false to "List", true to "Map").forEach { (isMap, label) ->
            val active = isMap == showMap
            Box(
                Modifier.background(if (active) Palette.primary else Color.Transparent)
                    .clickable { onChange(isMap) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(label, color = if (active) Palette.background else Palette.secondary,
                    style = MaterialTheme.typography.labelMedium)
            }
        }

    }
}
