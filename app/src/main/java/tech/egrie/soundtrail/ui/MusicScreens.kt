package tech.egrie.soundtrail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import tech.egrie.soundtrail.AppState
import tech.egrie.soundtrail.SearchProvider
import tech.egrie.soundtrail.sync.CloudSyncService
import tech.egrie.soundtrail.integrations.GoogleAuthState
import tech.egrie.soundtrail.integrations.MusicLinkParser
import tech.egrie.soundtrail.integrations.MusicProvider
import tech.egrie.soundtrail.integrations.SongInfo
import tech.egrie.soundtrail.integrations.TrackResult
import tech.egrie.soundtrail.integrations.SOUNDCLOUD_REDIRECT_URI
import tech.egrie.soundtrail.integrations.SPOTIFY_REDIRECT_URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun MusicSearchScreen(
    state: AppState, onSearch: (String) -> Unit, onSelectProvider: (SearchProvider) -> Unit,
    onConnect: () -> Unit, onSelectTrack: (TrackResult) -> Unit, onPlayTrack: (TrackResult) -> Unit,
    onAddLink: (String) -> Unit,
    onLookup: (String, String) -> Unit, onSelectLookup: (SongInfo) -> Unit,
    onSearchYoutubeMusic: (String) -> Unit, onOpenYoutubeLibrary: () -> Unit,
    onNotice: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var youtubeMusicQuery by rememberSaveable { mutableStateOf("") }
    var youtubeLink by rememberSaveable { mutableStateOf("") }
    var lookupArtist by rememberSaveable { mutableStateOf("") }
    var lookupTitle by rememberSaveable { mutableStateOf("") }
    var hasSearched by rememberSaveable { mutableStateOf(false) }
    var hasLookedUp by rememberSaveable { mutableStateOf(false) }
    var youtubeExpanded by rememberSaveable { mutableStateOf(false) }
    var lookupExpanded by rememberSaveable { mutableStateOf(false) }
    fun search() { hasSearched = query.isNotBlank(); onSearch(query) }
    fun lookUp() {
        hasLookedUp = lookupArtist.isNotBlank() && lookupTitle.isNotBlank()
        lookupExpanded = true
        onLookup(lookupArtist, lookupTitle)
    }

    val provider = state.searchProvider
    val connected = when (provider) {
        SearchProvider.SPOTIFY -> state.spotifyConnected
        SearchProvider.SOUNDCLOUD -> state.soundcloudConnected
        SearchProvider.JELLYFIN -> state.jellyfinConnected
        SearchProvider.YOUTUBE_DATA -> state.youtubeDataConnected
    }
    val accent = when (provider) {
        SearchProvider.SPOTIFY -> Palette.spotify
        SearchProvider.SOUNDCLOUD -> Palette.soundcloud
        SearchProvider.JELLYFIN -> Palette.jellyfin
        SearchProvider.YOUTUBE_DATA -> Palette.youtube
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(Modifier.height(18.dp))
            BrandHeader()
            Spacer(Modifier.height(20.dp))
            Eyebrow("SEARCH MUSIC")
            Spacer(Modifier.height(5.dp))
            Text("Find a song", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(4.dp))
            Text("Choose a service, or use another source below.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.secondary)
            Spacer(Modifier.height(14.dp))
            SurfaceCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(accent.copy(alpha = .17f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.MusicNote, null, tint = accent, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Search ${provider.label}", style = MaterialTheme.typography.titleLarge)
                }
                // Provider choices remain reachable on narrow screens as the list grows.
                Spacer(Modifier.height(10.dp))
                ProviderSwitch(provider, onSelectProvider)
                Spacer(Modifier.height(8.dp))
                if (connected) {
                    Text(
                        if (provider == SearchProvider.YOUTUBE_DATA) {
                            "Read-only search of your playlists${state.youtubeDataAccount?.let { " · $it" }.orEmpty()}."
                        } else "Search tracks from your connected account.",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(13.dp))
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(if (provider == SearchProvider.YOUTUBE_DATA) "Song, artist, or playlist" else "Song, artist, or album")
                        }, singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() })
                    )
                    Spacer(Modifier.height(11.dp))
                    PrimaryButton("Search ${provider.label}", { search() }, Modifier.fillMaxWidth(),
                        enabled = !state.searching && query.isNotBlank())
                } else {
                    Text("Connect ${provider.label} to search its music catalog.",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("Connect ${provider.label}", onConnect, Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(10.dp))
            SurfaceCard(Modifier.fillMaxWidth()) {
                DisclosureHeader(
                    title = "YouTube / YouTube Music",
                    subtitle = "Search the app or paste a song link — kept separate",
                    icon = Icons.Rounded.Link,
                    accent = Palette.youtube,
                    expanded = youtubeExpanded,
                    onToggle = { youtubeExpanded = !youtubeExpanded }
                )
                if (youtubeExpanded) {
                    Spacer(Modifier.height(12.dp))
                    Text("music.youtube.com links stay as YouTube Music and youtube.com / youtu.be links stay as YouTube, so every row says where it came from. Liked videos from your Google account are imported into one liked list — the same list your own likes live in, one song per link — because the import cannot see YouTube Music's account likes, which no third-party app can. All playback happens directly in Soundtrail.",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = youtubeMusicQuery, onValueChange = { youtubeMusicQuery = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Search YouTube Music") },
                        placeholder = { Text("Song or artist") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (youtubeMusicQuery.isNotBlank()) onSearchYoutubeMusic(youtubeMusicQuery)
                        })
                    )
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Search YouTube Music", {
                        onSearchYoutubeMusic(youtubeMusicQuery)
                    }, Modifier.fillMaxWidth(), enabled = youtubeMusicQuery.isNotBlank())
                    TextButton(onClick = onOpenYoutubeLibrary) {
                        Text("Open your YouTube Music library  ↗", color = Palette.youtube)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Or paste a YouTube or YouTube Music song link", style = MaterialTheme.typography.labelMedium,
                        color = Palette.secondary)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = youtubeLink, onValueChange = { youtubeLink = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("youtube.com/watch?v=… or music.youtube.com/watch?v=…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
                    )
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Pin this link", {
                        val link = MusicLinkParser.fromText(youtubeLink)
                        if (link?.provider == MusicProvider.YOUTUBE_MUSIC || link?.provider == MusicProvider.YOUTUBE) onAddLink(link.url)
                        else onNotice("Paste a YouTube or YouTube Music song link (not a playlist or search page). YouTube and YouTube Music are kept separate.")
                    }, Modifier.fillMaxWidth(), enabled = youtubeLink.isNotBlank())
                }
            }
            if (state.searching) {
                Spacer(Modifier.height(19.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Finding tracks…", color = Palette.secondary)
                }
            }
            if (state.searchError != null) {
                Spacer(Modifier.height(14.dp))
                Text(state.searchError, color = Palette.coral, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.results.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                SectionHeading(if (provider == SearchProvider.YOUTUBE_DATA) "Playlist videos" else "Tracks")
                Spacer(Modifier.height(4.dp))
            } else if (hasSearched && !state.searching && state.searchError == null && connected) {
                Spacer(Modifier.height(14.dp))
                Text(
                    if (provider == SearchProvider.YOUTUBE_DATA) "No matching videos found in the playlists scanned." else "No tracks found. Try another search.",
                    color = Palette.secondary
                )
            }
        }
        items(state.results, key = { it.url }) { track ->
            TrackRow(
                track,
                onPin = { onSelectTrack(track) },
                // Spotify is the one provider that plays in full somewhere else; its row says so.
                onPlay = if (track.provider == MusicProvider.SPOTIFY) {
                    { onPlayTrack(track) }
                } else null
            )
        }
        item {
            SurfaceCard(Modifier.fillMaxWidth()) {
                val lookupSummary = when {
                    state.lookupSearching -> "Searching…"
                    state.lookupResults.isNotEmpty() -> "${state.lookupResults.size} matches"
                    state.lookupError != null -> "Couldn’t complete lookup"
                    else -> "Find a track by artist and title"
                }
                DisclosureHeader(
                    title = "Song lookup",
                    subtitle = lookupSummary,
                    icon = Icons.Rounded.Cloud,
                    accent = Palette.lilac,
                    expanded = lookupExpanded,
                    onToggle = { lookupExpanded = !lookupExpanded }
                )
                if (lookupExpanded) {
                    Spacer(Modifier.height(10.dp))
                    Text("Free lookup; no account needed.",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = lookupArtist, onValueChange = { lookupArtist = it },
                            modifier = Modifier.weight(1f), singleLine = true,
                            label = { Text("Artist") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                        )
                        OutlinedTextField(
                            value = lookupTitle, onValueChange = { lookupTitle = it },
                            modifier = Modifier.weight(1f), singleLine = true,
                            label = { Text("Song title") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onSearch = { lookUp() })
                        )
                    }
                    Spacer(Modifier.height(9.dp))
                    PrimaryButton("Look up song", { lookUp() }, Modifier.fillMaxWidth(),
                        enabled = !state.lookupSearching &&
                            lookupArtist.isNotBlank() && lookupTitle.isNotBlank())
                    if (state.lookupSearching) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Searching…", color = Palette.secondary)
                        }
                    }
                    if (state.lookupError != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(state.lookupError, color = Palette.coral,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (hasLookedUp && !state.lookupSearching && state.lookupResults.isEmpty() &&
                        state.lookupError == null) {
                        Spacer(Modifier.height(10.dp))
                        Text("No song found. Check the spelling or try another search.",
                            color = Palette.secondary)
                    }
                }
                if (state.lookupResults.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    SectionHeading("Matches")
                }
            }
        }
        items(state.lookupResults, key = { "${it.title}|${it.artist}|${it.album}" }) { song ->
            LookupRow(song) { onSelectLookup(song) }
        }
    }
}

@Composable
private fun DisclosureHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).background(accent.copy(alpha = .15f), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = Palette.muted, style = MaterialTheme.typography.labelMedium)
        }
        Icon(
            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) "Collapse $title" else "Expand $title",
            tint = Palette.muted
        )
    }
}

@Composable
private fun ProviderSwitch(selected: SearchProvider, onSelect: (SearchProvider) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(50)).background(Palette.elevated).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        SearchProvider.entries.forEach { candidate ->
            val on = candidate == selected
            Text(
                candidate.label,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) Palette.primary else Color.Transparent)
                    .clickable { onSelect(candidate) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                color = if (on) Palette.background else Palette.secondary,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun LookupRow(song: SongInfo, onPin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface)
            .clickable(onClick = onPin).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(15.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF3A2F4A), Color(0xFF7A6B9B)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Cloud, null, tint = Palette.text, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = Palette.secondary)
            val detail = listOf(song.album, song.genre, song.mood)
                .filter(String::isNotBlank).joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium, color = Palette.muted)
            }
            if (song.url != null) {
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (song.spotifyUrl != null) ProviderPill(MusicProvider.SPOTIFY)
                    if (song.youtubeUrl != null) ProviderPill(MusicProvider.YOUTUBE_MUSIC)
                }
            } else {
                Text("Metadata only — add a song link when pinning",
                    style = MaterialTheme.typography.labelMedium, color = Palette.muted)
            }
        }
        Icon(Icons.Rounded.Add, contentDescription = "Pin ${song.title}", tint = Palette.primary)
        Spacer(Modifier.width(5.dp))
    }
}

/**
 * The saved Google connection's health, with a one-tap reconnect.
 *
 * Nothing here can sign the listener out. Tokens are written durably to Android-Keystore
 * encrypted storage that app updates do not touch, they are never deleted because of a failed
 * refresh or a network outage, and the Desktop Client ID (and, on a Play-services/microG device,
 * the account itself) stays put — so even the one case Google genuinely invalidates — a revoke,
 * a password change, or its own 7-day Testing-mode expiry — costs one tap, not a re-setup.
 */
@Composable
private fun GoogleConnectionStatus(state: AppState, onConnectYouTubeOAuth: (String) -> Unit) {
    val health = state.youtubeAuth
    val accent = when (health.state) {
        GoogleAuthState.CONNECTED -> Palette.primary
        GoogleAuthState.OFFLINE -> Palette.lilac
        GoogleAuthState.NEEDS_RECONNECT, GoogleAuthState.UNREADABLE -> Palette.coral
        GoogleAuthState.NOT_CONNECTED -> Palette.muted
    }
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).background(accent.copy(alpha = .16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Cloud, null, tint = accent, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Google connection", style = MaterialTheme.typography.titleMedium)
                Text(health.state.label, color = accent, style = MaterialTheme.typography.bodyMedium)
            }
            health.method?.let { method ->
                Text(method, color = Palette.muted, style = MaterialTheme.typography.labelMedium)
            }
        }
        health.detail?.let { detail ->
            Spacer(Modifier.height(8.dp))
            Text(detail, color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
        }
        health.hint?.let { hint ->
            Spacer(Modifier.height(6.dp))
            Text(hint, color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
        }
        if (health.needsAttention) {
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                if (state.connectingYouTubeOAuth) "Waiting for Google…" else "Reconnect Google (one tap)",
                { onConnectYouTubeOAuth("") },
                Modifier.fillMaxWidth(),
                enabled = !state.connectingYouTubeOAuth
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "App updates never clear this connection: the saved tokens live in this device's " +
                "encrypted storage, are renewed by themselves, and are re-checked when the app starts.",
            color = Palette.muted, style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
internal fun ConnectionsScreen(
    state: AppState,
    onConnect: (String) -> Unit, onDisconnect: () -> Unit,
    onConnectSoundcloud: (String, String) -> Unit, onDisconnectSoundcloud: () -> Unit,
    onOpenSpotify: () -> Unit, onOpenYoutube: () -> Unit, onOpenSoundcloud: () -> Unit,
    onOpenDashboard: () -> Unit, onOpenMicroG: () -> Unit, onOpenMicroGRe: () -> Unit, onOpenSoundcloudApps: () -> Unit,
    onConnectJellyfin: (String, String, String) -> Unit, onDisconnectJellyfin: () -> Unit,
    onOpenJellyfin: () -> Unit,
    onConnectYouTubeData: () -> Unit, onConnectYouTubeOAuth: (String) -> Unit, onDisconnectYouTubeData: () -> Unit,
    onOpenYouTubeSetup: () -> Unit,
    onImportYouTubePlaylists: () -> Unit, onSetYouTubeAutoImport: (Boolean) -> Unit,
    backupBusy: Boolean, onExportBackup: () -> Unit, onImportBackup: () -> Unit,
    syncBusy: Boolean,
    onCreateCloudSyncAccount: (String, String, String) -> Unit,
    onSignInCloudSync: (String, String, String) -> Unit,
    onUnlockCloudSync: (String) -> Unit,
    onSyncCloudData: () -> Unit,
    onShowCloudRecoveryKey: () -> Unit,
    onForgetCloudRecoveryKey: () -> Unit,
    onDismissCloudRecoveryKey: () -> Unit,
    onSetCloudSyncAutoEnabled: (Boolean) -> Unit,
    onDisconnectCloudSync: () -> Unit,
    onDeleteCloudSyncAccount: () -> Unit
) {
    // When the user has no saved server, pre-fill the field with the default public server so
    // that sign-up and sign-in just work; self-hosters overwrite it with their own URL.
    var syncServer by rememberSaveable(state.syncServer) {
        mutableStateOf(state.syncServer.ifBlank { CloudSyncService.DEFAULT_SYNC_SERVER })
    }
    var syncEmail by rememberSaveable(state.syncEmail) { mutableStateOf(state.syncEmail) }
    var syncPassword by remember { mutableStateOf("") }
    var syncRecoveryInput by remember { mutableStateOf("") }
    var confirmDeleteCloudAccount by remember { mutableStateOf(false) }
    var clientId by rememberSaveable(state.spotifyClientId) { mutableStateOf(state.spotifyClientId) }
    var soundcloudId by rememberSaveable(state.soundcloudClientId) { mutableStateOf(state.soundcloudClientId) }
    var soundcloudSecret by rememberSaveable { mutableStateOf("") }
    var jellyfinServer by rememberSaveable(state.jellyfinServer) { mutableStateOf(state.jellyfinServer) }
    var jellyfinUser by rememberSaveable(state.jellyfinName) { mutableStateOf(state.jellyfinName) }
    var jellyfinPassword by rememberSaveable { mutableStateOf("") }
    var youtubeOAuthId by rememberSaveable(state.youtubeOAuthClientId) { mutableStateOf(state.youtubeOAuthClientId) }
    var expandedService by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        BrandHeader()
        Spacer(Modifier.height(20.dp))
        Eyebrow("YOUR SERVICES")
        Spacer(Modifier.height(5.dp))
        Text("Connections", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text("Set up only the music services you use. Pins and playlists stay local unless you enable optional, end-to-end encrypted sync to a server you control.",
            color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        SurfaceCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                    expandedService = if (expandedService == "spotify") null else "spotify"
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(43.dp).background(Palette.spotify.copy(alpha = .17f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Palette.spotify)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Spotify", style = MaterialTheme.typography.titleLarge)
                    Text(if (state.spotifyConnected) "Connected" else "Tap to set up",
                        color = if (state.spotifyConnected) Palette.spotify else Palette.secondary,
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (state.spotifyConnected) Icon(Icons.Rounded.Check, "Connected", tint = Palette.spotify)
                Icon(
                    if (expandedService == "spotify") Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expandedService == "spotify") "Collapse Spotify" else "Set up Spotify",
                    tint = Palette.muted
                )
            }
            if (expandedService == "spotify") {
                Spacer(Modifier.height(15.dp))
                if (state.spotifyConnected) {
                    Text("Signed in${state.spotifyName?.let { " as $it" }.orEmpty()}. Search tracks and save them to places.",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(13.dp))
                    PrimaryButton("Open Spotify", onOpenSpotify, Modifier.fillMaxWidth())
                    TextButton(onClick = onDisconnect) { Text("Disconnect on this device", color = Palette.coral) }
                } else {
                    Text("Use a Spotify Developer app's public Client ID. No client secret is stored or needed.",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Text("Redirect URI to register in your Spotify dashboard:",
                        style = MaterialTheme.typography.labelMedium, color = Palette.secondary)
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(SPOTIFY_REDIRECT_URI, color = Palette.primary, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(13.dp))
                    OutlinedTextField(
                        value = clientId, onValueChange = { clientId = it.trim().take(32) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Spotify Client ID") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(if (state.connecting) "Connecting…" else "Connect Spotify",
                        { onConnect(clientId) }, Modifier.fillMaxWidth(), enabled = !state.connecting)
                    TextButton(onClick = onOpenDashboard) {
                        Text("Open Spotify Developer Dashboard  ↗", color = Palette.spotify)
                    }
                    Text("Developer-mode apps may require a Premium app owner and an allowlisted test account.",
                        style = MaterialTheme.typography.labelMedium, color = Palette.muted)
                }
            }
        }
        Spacer(Modifier.height(13.dp))
        SurfaceCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                    expandedService = if (expandedService == "soundcloud") null else "soundcloud"
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(43.dp).background(Palette.soundcloud.copy(alpha = .15f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Cloud, null, tint = Palette.soundcloud)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("SoundCloud", style = MaterialTheme.typography.titleLarge)
                    Text(if (state.soundcloudConnected) "Connected" else "Tap to set up",
                        color = if (state.soundcloudConnected) Palette.soundcloud else Palette.secondary,
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (state.soundcloudConnected) Icon(Icons.Rounded.Check, "Connected", tint = Palette.soundcloud)
                Icon(
                    if (expandedService == "soundcloud") Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expandedService == "soundcloud") "Collapse SoundCloud" else "Set up SoundCloud",
                    tint = Palette.muted
                )
            }
            if (expandedService == "soundcloud") {
                Spacer(Modifier.height(15.dp))
                if (state.soundcloudConnected) {
                    Text("Signed in${state.soundcloudName?.let { " as $it" }.orEmpty()}. Search tracks and save them to places.",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(13.dp))
                    PrimaryButton("Open SoundCloud", onOpenSoundcloud, Modifier.fillMaxWidth())
                    TextButton(onClick = onDisconnectSoundcloud) {
                        Text("Disconnect on this device", color = Palette.coral)
                    }
                } else {
                    Text("SoundCloud's API needs a registered app (Artist Pro or an approved API request). " +
                        "SoundCloud treats every client as confidential, so the Client Secret is required too; " +
                        "it is stored encrypted with Android Keystore and never leaves this device.",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Text("Redirect URI to register in your SoundCloud app settings:",
                        style = MaterialTheme.typography.labelMedium, color = Palette.secondary)
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(SOUNDCLOUD_REDIRECT_URI, color = Palette.primary, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(13.dp))
                    OutlinedTextField(
                        value = soundcloudId, onValueChange = { soundcloudId = it.trim().take(64) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("SoundCloud Client ID") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = soundcloudSecret, onValueChange = { soundcloudSecret = it.take(128) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("SoundCloud Client Secret") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password, imeAction = ImeAction.Done
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(
                        if (state.connectingSoundcloud) "Connecting…" else "Connect SoundCloud",
                        { onConnectSoundcloud(soundcloudId, soundcloudSecret) },
                        Modifier.fillMaxWidth(), enabled = !state.connectingSoundcloud
                    )
                    TextButton(onClick = onOpenSoundcloudApps) {
                        Text("Open SoundCloud for Developers  ↗", color = Palette.soundcloud)
                    }
                    Text("App registration is limited; see SoundCloud's docs for current access requirements.",
                        style = MaterialTheme.typography.labelMedium, color = Palette.muted)
                }
            }
        }
        Spacer(Modifier.height(13.dp))
        SurfaceCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                    expandedService = if (expandedService == "jellyfin") null else "jellyfin"
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(43.dp).background(Palette.jellyfin.copy(alpha = .18f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Dns, null, tint = Palette.jellyfin)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Jellyfin", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            state.jellyfinConnected -> "Connected"
                            state.connectingJellyfin -> "Connecting…"
                            else -> "Tap to set up"
                        },
                        color = if (state.jellyfinConnected) Palette.jellyfin else Palette.secondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (state.jellyfinConnected) Icon(Icons.Rounded.Check, "Connected", tint = Palette.jellyfin)
                Icon(
                    if (expandedService == "jellyfin") Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expandedService == "jellyfin") "Collapse Jellyfin" else "Set up Jellyfin",
                    tint = Palette.muted
                )
            }
            if (expandedService == "jellyfin") {
                Spacer(Modifier.height(15.dp))
                if (state.jellyfinConnected) {
                    Text(
                        state.jellyfinServerName.ifBlank { "Your server" } +
                            if (state.jellyfinName.isBlank()) "" else " · signed in as ${state.jellyfinName}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(state.jellyfinServer, color = Palette.secondary,
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Search and stream this library, or download eligible tracks to this device " +
                        "for offline playback. The server checks download permissions; Soundtrail never " +
                        "changes your library, and audio files are not cloud-synced.",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(13.dp))
                    PrimaryButton("Open Jellyfin  ↗", onOpenJellyfin, Modifier.fillMaxWidth())
                    TextButton(onClick = onDisconnectJellyfin) {
                        Text("Disconnect on this device", color = Palette.coral)
                    }
                } else {
                    Text("Point Soundtrail at the Jellyfin server you run yourself to search, stream, " +
                        "and download eligible tracks for offline play. Jellyfin enforces your account's " +
                        "permissions; Soundtrail never changes items in the library.",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(13.dp))
                    OutlinedTextField(
                        value = jellyfinServer, onValueChange = { jellyfinServer = it.trim().take(200) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Server address") },
                        placeholder = { Text("http://192.168.1.20:8096") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = jellyfinUser, onValueChange = { jellyfinUser = it.trim().take(80) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Jellyfin username") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = jellyfinPassword, onValueChange = { jellyfinPassword = it.take(200) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Jellyfin password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password, imeAction = ImeAction.Done
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(
                        if (state.connectingJellyfin) "Connecting…" else "Connect Jellyfin",
                        { onConnectJellyfin(jellyfinServer, jellyfinUser, jellyfinPassword) },
                        Modifier.fillMaxWidth(),
                        enabled = !state.connectingJellyfin && jellyfinServer.isNotBlank() &&
                            jellyfinUser.isNotBlank() && jellyfinPassword.isNotEmpty()
                    )
                    Text("The password goes only to that server and is never stored; Jellyfin returns " +
                        "an access token instead, kept encrypted with Android Keystore. Servers on " +
                        "your own network usually speak plain http://.",
                        style = MaterialTheme.typography.labelMedium, color = Palette.muted)
                }
            }
        }
        Spacer(Modifier.height(13.dp))
        SurfaceCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                    expandedService = if (expandedService == "youtube") null else "youtube"
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(43.dp).background(Palette.youtube.copy(alpha = .16f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Palette.youtube)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("YouTube Music", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            state.youtubeDataConnected -> "Playlists & search connected"
                            state.youtubeAppInstalled -> "Music app detected"
                            else -> "Tap to set up"
                        },
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (state.youtubeDataConnected) Icon(Icons.Rounded.Check, "Playlists & search connected", tint = Palette.youtube)
                Icon(
                    if (expandedService == "youtube") Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expandedService == "youtube") "Collapse YouTube Music" else "Set up YouTube Music",
                    tint = Palette.muted
                )
            }
            if (expandedService == "youtube") {
                Spacer(Modifier.height(13.dp))
                GoogleConnectionStatus(state, onConnectYouTubeOAuth)
                Spacer(Modifier.height(13.dp))
                Text("YouTube playlist import & search", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                if (state.youtubeDataConnected) {
                    Text(
                        "Read-only access${state.youtubeDataAccount?.let { " · $it" }.orEmpty()}. " +
                            "Playlists (including Liked videos and Uploads) are imported into your " +
                            "library so you can pin or automate them. The YouTube Data API cannot " +
                            "reach YouTube Music-only collections like saved songs/albums or " +
                            "auto-generated mixes (Supermix, Daylist, etc.).",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(
                        if (state.youtubeImporting) "Importing playlists…" else "Import playlists now",
                        onImportYouTubePlaylists, Modifier.fillMaxWidth(),
                        enabled = !state.youtubeImporting
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Import automatically when connected", style = MaterialTheme.typography.titleMedium)
                            val importTime = if (state.youtubeLastImportedAt > 0L)
                                " · last imported ${formatSyncTime(state.youtubeLastImportedAt)}" else ""
                            Text(
                                "Pulls new videos into matching Soundtrail playlists.$importTime",
                                color = Palette.muted, style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Switch(
                            checked = state.youtubeAutoImportEnabled,
                            onCheckedChange = onSetYouTubeAutoImport
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onDisconnectYouTubeData) {
                        Text("Disconnect and request revocation", color = Palette.coral)
                    }
                } else {
                    val connectingAny = state.connectingYouTubeData || state.connectingYouTubeOAuth
                    if (state.gmsSignInAvailable) {
                        Text("Optional read-only playlist search. Connect uses the single Google (microG) account on " +
                            "this device, or opens the Google sign-in screen if there is none or several. Device support " +
                            "depends on Google services and OAuth setup; no private microG data is read.",
                            color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        PrimaryButton(
                            if (state.connectingYouTubeData) "Connecting…" else "Connect Google account",
                            onConnectYouTubeData, Modifier.fillMaxWidth(),
                            enabled = !connectingAny
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    Text(
                        "Or sign in with a Client ID — no Google services needed",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            state.microGReInstalled ->
                                "MicroG RE is installed. It only provides Google sign-in to Morphe/ReVanced patched " +
                                    "apps, so Soundtrail cannot use its accounts. Sign in below with your own Desktop " +
                                    "OAuth Client ID instead: Google opens in your browser, and the tokens stay " +
                                    "encrypted on this device."
                            !state.gmsSignInAvailable ->
                                "No Google Play services or standard microG was found, so the Google-account button " +
                                    "is hidden. Use your own Desktop OAuth Client ID instead: Google opens in your " +
                                    "browser, and the tokens stay encrypted on this device."
                            else ->
                                "Handy with MicroG RE or without a device Google account. Use your own Desktop OAuth " +
                                    "Client ID: Google opens in your browser, and the tokens stay encrypted on this device."
                        },
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = youtubeOAuthId, onValueChange = { youtubeOAuthId = it.trim().take(120) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Google Desktop Client ID") },
                        placeholder = { Text("123…-abc….apps.googleusercontent.com") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(
                        if (state.connectingYouTubeOAuth) "Connecting…" else "Connect with Client ID",
                        { onConnectYouTubeOAuth(youtubeOAuthId) }, Modifier.fillMaxWidth(),
                        enabled = !connectingAny
                    )
                    TextButton(onClick = onOpenYouTubeSetup) {
                        Text("Configure Google Cloud / YouTube API  ↗", color = Palette.youtube)
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text(state.servicesLabel, color = Palette.lilac, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(7.dp))
                Text("All music now plays directly in Soundtrail. Google sign-in powers playlist search AND playlist import; YouTube audio plays in-app via your self-hosted server or offline files.",
                    color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(15.dp))
                PrimaryButton("Open YouTube Music", onOpenYoutube, Modifier.fillMaxWidth())
                Text(
                    "Opening YouTube Music never needs this connection, or any Google sign-in: it " +
                        "hands the app a link, exactly as a browser would. The read-only connection " +
                        "above is only for importing and searching your own playlists.",
                    color = Palette.muted, style = MaterialTheme.typography.labelMedium
                )
                TextButton(onClick = onOpenMicroG) { Text("Learn about microG  ↗", color = Palette.lilac) }
                if (state.microGReInstalled || !state.gmsSignInAvailable) {
                    TextButton(onClick = onOpenMicroGRe) { Text("MicroG RE (Morphe/ReVanced)  ↗", color = Palette.lilac) }
                }
            }
        }
        Spacer(Modifier.height(13.dp))
        SurfaceCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(43.dp).background(Palette.lilac.copy(alpha = .17f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Cloud, null, tint = Palette.lilac)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Cloud sync", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (state.syncConnected) {
                            "Connected · ${state.syncEmail}${if (state.syncUsingCustomServer) " · self-hosted" else " · public server"}"
                        } else {
                            "Default public server · self-host your own anytime"
                        },
                        color = if (state.syncConnected) Palette.primary else Palette.secondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (syncBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(13.dp))
            Text(
                "Sync pins, playlists, automations, edits, and deletions between devices. Out of " +
                    "the box the app connects to the public Soundtrail server " +
                    "(${CloudSyncService.DEFAULT_SYNC_SERVER.removePrefix("https://")}). You can instead point it at " +
                    "your own self-hosted server by changing the URL below. Library data is " +
                    "encrypted on this device; the server stores ciphertext plus account metadata. " +
                    "The recovery key is yours alone. A password reset cannot restore synced data " +
                    "without that key.",
                color = Palette.secondary, style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(13.dp))
            if (!state.syncConnected) {
                OutlinedTextField(
                    value = syncServer, onValueChange = { syncServer = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Sync server URL (HTTPS)") },
                    placeholder = { Text(CloudSyncService.DEFAULT_SYNC_SERVER) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(
                        onClick = { syncServer = CloudSyncService.DEFAULT_SYNC_SERVER },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Use public server", color = Palette.lilac, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Self-hosters: replace with your own server's HTTPS URL.",
                        color = Palette.muted,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = syncEmail, onValueChange = { syncEmail = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Account email") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = syncPassword, onValueChange = { syncPassword = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Account password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
                )
                Spacer(Modifier.height(6.dp))
                Text("Use a unique password (10–128 characters). The server stores a salted scrypt hash, never your plaintext password.",
                    color = Palette.muted, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(11.dp))
                PrimaryButton(
                    if (syncBusy) "Creating account…" else "Create account and recovery key",
                    onClick = {
                        onCreateCloudSyncAccount(syncServer, syncEmail, syncPassword)
                        syncPassword = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !syncBusy && syncServer.isNotBlank() && syncEmail.isNotBlank() && syncPassword.isNotBlank()
                )
                Spacer(Modifier.height(5.dp))
                TextButton(
                    onClick = {
                        onSignInCloudSync(syncServer, syncEmail, syncPassword)
                        syncPassword = ""
                    }, enabled = !syncBusy && syncServer.isNotBlank() && syncEmail.isNotBlank() && syncPassword.isNotBlank()
                ) { Text("Already have an account? Sign in", color = Palette.lilac) }
            } else {
                Text(
                    if (state.syncUsingCustomServer)
                        "Self-hosted server: ${state.syncEffectiveServer}"
                    else
                        "Public server: ${state.syncEffectiveServer}",
                    color = Palette.muted, style = MaterialTheme.typography.labelMedium
                )
                if (state.syncLastSyncAt > 0L) {
                    Spacer(Modifier.height(4.dp))
                    Text("Last synced ${formatSyncTime(state.syncLastSyncAt)}",
                        color = Palette.muted, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                } else {
                    Spacer(Modifier.height(4.dp))
                }
                val syncStatus = when {
                    syncBusy -> if (state.syncPendingChanges > 0) "Syncing · ${state.syncPendingChanges} changes in the local queue" else "Syncing your library…"
                    state.syncFailed -> if (state.syncPendingChanges > 0) "Sync failed · ${state.syncPendingChanges} local changes remain queued" else "Sync failed · retry to check the server"
                    state.syncLastSyncAt == 0L -> "First sync will merge this device's library with the server."
                    state.syncPendingChanges > 0 -> "${state.syncPendingChanges} ${if (state.syncPendingChanges == 1) "change" else "changes"} waiting to sync"
                    else -> "All changes are synced on this device."
                }
                Text(syncStatus, color = if (state.syncFailed) Palette.coral else Palette.muted,
                    style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sync automatically when Soundtrail opens", style = MaterialTheme.typography.titleMedium)
                        Text("It will not sync in the background while the app is closed.",
                            color = Palette.muted, style = MaterialTheme.typography.labelMedium)
                    }
                    Switch(checked = state.syncAutoEnabled, onCheckedChange = onSetCloudSyncAutoEnabled)
                }
                state.syncError?.let { error ->
                    Spacer(Modifier.height(5.dp))
                    Text(if (state.syncFailed) "Sync failed: $error" else "Sync notice: $error",
                        color = if (state.syncFailed) Palette.coral else Palette.secondary,
                        style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(8.dp))
                if (state.syncHasRecoveryKey) {
                    PrimaryButton(
                        when {
                            syncBusy -> "Syncing…"
                            state.syncFailed -> "Retry sync"
                            else -> "Sync now"
                        },
                        onSyncCloudData, Modifier.fillMaxWidth(), enabled = !syncBusy
                    )
                    Spacer(Modifier.height(5.dp))
                    TextButton(onClick = onShowCloudRecoveryKey, enabled = !syncBusy) {
                        Text("Show saved recovery key", color = Palette.lilac)
                    }
                    TextButton(onClick = onForgetCloudRecoveryKey, enabled = !syncBusy) {
                        Text("Use a different recovery key", color = Palette.secondary)
                    }
                } else {
                    Text("Enter the recovery key created with this account. It is never uploaded.",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = syncRecoveryInput, onValueChange = { syncRecoveryInput = it.trim() },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Recovery key") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton(
                        "Unlock and sync",
                        onClick = { onUnlockCloudSync(syncRecoveryInput); syncRecoveryInput = "" },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !syncBusy && syncRecoveryInput.isNotBlank()
                    )
                }
                state.syncRecoveryKey?.let { key ->
                    Spacer(Modifier.height(9.dp))
                    Text("Save this 256-bit recovery key somewhere private. Anyone with it and your account can decrypt your synced library; Soundtrail cannot recover it.",
                        color = Palette.coral, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(key, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            color = Palette.primary, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = onDismissCloudRecoveryKey) {
                        Text("Hide recovery key", color = Palette.secondary)
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onDisconnectCloudSync, enabled = !syncBusy) {
                    Text("Sign out on this device", color = Palette.coral)
                }
                TextButton(onClick = { confirmDeleteCloudAccount = true }, enabled = !syncBusy) {
                    Text("Delete account and remote sync data…", color = Palette.coral)
                }
            }
        }
        if (confirmDeleteCloudAccount) {
            AlertDialog(
                onDismissRequest = { confirmDeleteCloudAccount = false },
                title = { Text("Delete sync account?") },
                text = { Text("This permanently deletes the account and encrypted sync history from your server. It does not delete this device's pins, playlists, automations, or audio files.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDeleteCloudAccount = false
                        onDeleteCloudSyncAccount()
                    }, enabled = !syncBusy) {
                        Text("Delete remote account", color = Palette.coral)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteCloudAccount = false }) { Text("Cancel") }
                },
                containerColor = Palette.elevated
            )
        }
        Spacer(Modifier.height(13.dp))
        SurfaceCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(43.dp).background(Palette.primary.copy(alpha = .15f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Backup, null, tint = Palette.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Backup & restore", style = MaterialTheme.typography.titleLarge)
                    Text(if (backupBusy) "Working…" else "Everything local, in one file",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                }
                if (backupBusy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(13.dp))
            Text("Write every pin, playlist, and automation to a JSON file you own — or read " +
                "one back on this or another device. The file goes only where you send it; " +
                "Soundtrail uploads nothing.",
                color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(13.dp))
            PrimaryButton(if (backupBusy) "Working…" else "Export backup",
                onExportBackup, Modifier.fillMaxWidth(), enabled = !backupBusy)
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Import backup",
                onImportBackup, Modifier.fillMaxWidth(), enabled = !backupBusy)
            Spacer(Modifier.height(9.dp))
            Text("A backup carries pins, playlists with their song links, and automations. " +
                "Account logins and imported audio files stay on this device — reconnect " +
                "accounts and re-attach audio after a restore. Importing keeps everything " +
                "already here and only adds what the file has.",
                color = Palette.muted, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(13.dp))
        SurfaceCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = Palette.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
                Text("Private by design", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(7.dp))
            Text("Location is requested only when you choose it. Saved pin locations enter sync only as end-to-end encrypted records when you enable self-hosted sync. Pins are rendered on-device; map requests use public tile coordinates. Official read-only YouTube API access is separate from rights-confirmed audio-only extraction; the extractor receives no OAuth token or app-session data. No background location tracking.",
                color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(27.dp))
    }
}

private fun formatSyncTime(timestamp: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
