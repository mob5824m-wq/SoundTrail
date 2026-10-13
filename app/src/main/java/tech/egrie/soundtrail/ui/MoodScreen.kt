package tech.egrie.soundtrail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.egrie.soundtrail.AppState
import tech.egrie.soundtrail.MoodUiState
import tech.egrie.soundtrail.mood.MoodAiService
import tech.egrie.soundtrail.mood.MoodEngine
import tech.egrie.soundtrail.mood.MoodMixTrack
import tech.egrie.soundtrail.mood.MoodProfile
import tech.egrie.soundtrail.mood.MoodSource

/** Everything the Mood tab can ask the app to do. */
internal data class MoodActions(
    val onTextChange: (String) -> Unit,
    val onEnergyHint: (Int?) -> Unit,
    val onAnalyze: () -> Unit,
    val onQuickMood: (String) -> Unit,
    val onPlayMix: () -> Unit,
    val onPlayTrack: (MoodMixTrack) -> Unit,
    val onStopMix: () -> Unit,
    val onSaveMix: () -> Unit,
    val onImproveTags: () -> Unit,
    val onSetTagLookup: (Boolean) -> Unit,
    val onSetAiEnabled: (Boolean) -> Unit,
    val onSetAiModel: (String) -> Unit,
    val onSaveAiKey: (String) -> Unit,
    val onClearAiKey: () -> Unit,
    val onOpenInYouTubeMusic: () -> Unit
)

@Composable
internal fun MoodScreen(state: AppState, actions: MoodActions, onNotice: (String) -> Unit) {
    val mood = state.mood
    var aiKeyDraft by rememberSaveable { mutableStateOf("") }
    var showAiSetup by rememberSaveable { mutableStateOf(false) }
    val provider = MoodAiService.Provider.GEMINI

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        BrandHeader()
        Spacer(Modifier.height(20.dp))
        Eyebrow("MOOD ENGINE")
        Spacer(Modifier.height(5.dp))
        Text("Music that matches you", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Say how you feel in your own words. Soundtrail reads it, then builds a mix from " +
                "your own pins and playlists.",
            color = Palette.secondary, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(14.dp))

        MoodInputCard(mood, actions)
        Spacer(Modifier.height(10.dp))

        mood.profile?.let { profile ->
            MoodResultCard(profile, mood, actions)
            Spacer(Modifier.height(10.dp))
        }

        if (mood.mix.isNotEmpty()) {
            SectionHeading("Your mix", action = null, onAction = null)
            Spacer(Modifier.height(8.dp))
            mood.mix.forEach { track ->
                MoodTrackRow(
                    track = track,
                    playing = mood.queuePlaying && mood.queueIndex >= 0 &&
                        mood.mix.getOrNull(mood.queueIndex)?.key == track.key,
                    onPlay = { actions.onPlayTrack(track) }
                )
                Spacer(Modifier.height(8.dp))
            }
        } else if (mood.profile != null && !mood.analyzing) {
            SurfaceCard(Modifier.fillMaxWidth()) {
                Text("Nothing to play from your library yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Pins and playlist songs are what a mix is built from. Add a song on the Pins tab, " +
                        "import your YouTube playlists on Connect, or start from a search below — " +
                        "YouTube Music needs no Google sign-in for that.",
                    color = Palette.secondary, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = actions.onOpenInYouTubeMusic) {
                    Text("Search this mood in YouTube Music  \u2197", color = Palette.youtube)
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        SurfaceCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(43.dp).background(Palette.lilac.copy(alpha = .16f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Cloud, null, tint = Palette.lilac)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Mood library", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${mood.librarySize} song${if (mood.librarySize == 1) "" else "s"} scored · " +
                            "${mood.untaggedCount} without sound tags",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Look up genre & mood tags", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "TheAudioDB's free metadata API. Artist and title leave the device for that " +
                            "lookup only; nothing is stored on their side.",
                        color = Palette.muted, style = MaterialTheme.typography.labelMedium
                    )
                }
                Switch(checked = mood.enrichEnabled, onCheckedChange = actions.onSetTagLookup)
            }
            Spacer(Modifier.height(8.dp))
            if (mood.enriching) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Learning sound tags ${mood.enrichProgress} / ${mood.enrichTotal}…",
                        color = Palette.secondary, style = MaterialTheme.typography.labelMedium
                    )
                }
            } else {
                TextButton(
                    onClick = actions.onImproveTags,
                    enabled = mood.profile != null && mood.untaggedCount > 0
                ) {
                    Text(
                        if (mood.untaggedCount == 0) "Every song already has sound tags"
                        else "Improve this mix with ${minOf(mood.untaggedCount, 12)} lookups",
                        color = Palette.primary
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        SurfaceCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(43.dp).background(Palette.primary.copy(alpha = .14f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Favorite, null, tint = Palette.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Mood reading", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (mood.aiEnabled && mood.aiKeySet) "AI model · ${mood.aiModel}"
                        else "On-device · works offline, nothing sent",
                        color = Palette.secondary, style = MaterialTheme.typography.bodyMedium
                    )
                }
                Switch(
                    checked = mood.aiEnabled,
                    onCheckedChange = { enabled ->
                        actions.onSetAiEnabled(enabled)
                        if (enabled && !mood.aiKeySet) showAiSetup = true
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "The on-device engine reads your words on the phone — no key, no network. Switching " +
                    "the AI model on sends only your mood description (plus the energy hint, the hour, " +
                    "and whether you are moving) to ${provider.label}; your songs, playlists, pins, and " +
                    "places never leave the device. Any failure falls back to the on-device reading.",
                color = Palette.secondary, style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = { showAiSetup = !showAiSetup }) {
                Text(
                    if (showAiSetup) "Hide AI model setup" else "Set up the AI model (bring your own key)",
                    color = Palette.lilac
                )
            }
            if (showAiSetup || (mood.aiEnabled && !mood.aiKeySet)) {
                Text(provider.keyHint, style = MaterialTheme.typography.labelMedium, color = Palette.secondary)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = aiKeyDraft,
                    onValueChange = { aiKeyDraft = it.trim().take(200) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("${provider.label} API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                Spacer(Modifier.height(8.dp))
                if (mood.aiKeySet) {
                    Text(
                        "A key is saved on this device, encrypted with Android Keystore. It is never " +
                            "synced, never backed up, and never included in exports.",
                        color = Palette.primary, style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.height(6.dp))
                }
                PrimaryButton(
                    if (mood.aiKeySet) "Replace key" else "Save key",
                    { actions.onSaveAiKey(aiKeyDraft); aiKeyDraft = "" },
                    Modifier.fillMaxWidth(),
                    enabled = aiKeyDraft.isNotBlank()
                )
                if (mood.aiKeySet) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Check, null, tint = Palette.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Key saved", color = Palette.primary, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = actions.onClearAiKey) {
                            Text("Remove key", color = Palette.coral)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = mood.aiModel,
                    onValueChange = actions.onSetAiModel,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Model name") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { onNotice("Open ${provider.keyUrl} to create or manage a key.") }) {
                    Text("Where do I get a key?  \u2197", color = Palette.lilac)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MoodInputCard(mood: MoodUiState, actions: MoodActions) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(43.dp).background(Palette.primary.copy(alpha = .14f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, null, tint = Palette.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("How do you feel?", style = MaterialTheme.typography.titleLarge)
                Text(
                    "A few words is plenty — \u201Ctired but I want something warm for the drive home\u201D",
                    color = Palette.secondary, style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = mood.text,
            onValueChange = actions.onTextChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            placeholder = { Text("Describe the mood, the moment, or what you want to hear") }
        )
        Spacer(Modifier.height(10.dp))
        Text("Quick picks", style = MaterialTheme.typography.labelMedium, color = Palette.muted)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MoodEngine.quickPicks.forEach { (id, label) ->
                MoodPill(
                    text = label,
                    selected = mood.profile?.id == id,
                    onClick = { actions.onQuickMood(id) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Energy", style = MaterialTheme.typography.labelMedium, color = Palette.muted)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MoodPill("Let the words decide", mood.energyHint == null) { actions.onEnergyHint(null) }
            MoodPill("Low", mood.energyHint == 25) { actions.onEnergyHint(25) }
            MoodPill("Medium", mood.energyHint == 55) { actions.onEnergyHint(55) }
            MoodPill("High", mood.energyHint == 85) { actions.onEnergyHint(85) }
        }
        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            if (mood.analyzing) "Reading your mood…" else "Build my mood mix",
            actions.onAnalyze,
            Modifier.fillMaxWidth(),
            enabled = !mood.analyzing
        )
        mood.error?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(message, color = Palette.coral, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MoodResultCard(
    profile: MoodProfile,
    mood: MoodUiState,
    actions: MoodActions
) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(43.dp).background(Palette.coral.copy(alpha = .16f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Favorite, null, tint = Palette.coral)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.label, style = MaterialTheme.typography.titleLarge)
                Text(profile.blurb, color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
            }
            Text("${profile.confidencePercent}%", color = Palette.primary,
                style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            when (profile.source) {
                MoodSource.ON_DEVICE -> "Read on this device"
                MoodSource.AI_MODEL -> "Read by ${profile.modelLabel ?: "an AI model"}"
            },
            color = Palette.muted, style = MaterialTheme.typography.labelMedium
        )
        profile.note?.let { note ->
            Spacer(Modifier.height(4.dp))
            Text(note, color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MoodFeatureBar("Mood", profile.valence, Palette.coral, Modifier.weight(1f))
            MoodFeatureBar("Energy", profile.energy, Palette.youtube, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Around ${profile.tempo} BPM \u00b7 dance ${percent(profile.danceability)} \u00b7 " +
                "acoustic ${percent(profile.acousticness)} \u00b7 instrumental ${percent(profile.instrumentalness)}",
            color = Palette.muted, style = MaterialTheme.typography.labelMedium
        )
        if (profile.supporting.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Also sounding like: " + profile.supporting.joinToString(", ") { (label, share) ->
                    "$label ${(share * 100).toInt()}%"
                },
                color = Palette.muted, style = MaterialTheme.typography.labelMedium
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            profile.tags.take(6).forEach { tag -> MoodPill(tag, selected = false) { } }
        }
        Spacer(Modifier.height(14.dp))
        if (mood.queuePlaying) {
            PrimaryButton("Stop the mood mix", actions.onStopMix, Modifier.fillMaxWidth())
        } else {
            PrimaryButton("Play this mix", actions.onPlayMix, Modifier.fillMaxWidth(),
                enabled = mood.mix.isNotEmpty())
        }
        if (mood.savedPlaylistName != null) {
            Text(
                "Saved as \u201C${mood.savedPlaylistName}\u201D — find it on the Lists tab.",
                color = Palette.primary, style = MaterialTheme.typography.labelMedium
            )
        } else if (mood.mix.isNotEmpty()) {
            TextButton(onClick = actions.onSaveMix) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.QueueMusic, null, tint = Palette.primary, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save this mix as a playlist", color = Palette.primary)
                }
            }
        }
        TextButton(onClick = actions.onOpenInYouTubeMusic) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = Palette.youtube, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open this mood in YouTube Music  \u2197", color = Palette.youtube)
            }
        }
    }
}

@Composable
private fun MoodTrackRow(track: MoodMixTrack, playing: Boolean, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp)
                .background(if (playing) Palette.primary else Palette.elevated, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${track.matchPercent}",
                color = if (playing) Palette.background else Palette.primary,
                style = MaterialTheme.typography.labelMedium
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(track.artist.takeIf(String::isNotBlank), track.origin).joinToString(" \u00b7 "),
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.secondary,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                track.reasons.joinToString(" \u00b7 "),
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.muted,
                style = MaterialTheme.typography.labelSmall
            )
        }
        IconButton(onClick = onPlay, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Rounded.PlayArrow, "Play ${track.title}", tint = Palette.primary)
        }
    }
}

@Composable
private fun MoodFeatureBar(label: String, value: Double, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.muted)
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Palette.elevated)) {
            Box(
                Modifier.fillMaxWidth(value.coerceIn(0.0, 1.0).toFloat())
                    .height(6.dp).background(color)
            )
        }
        Spacer(Modifier.height(3.dp))
        Text("${(value * 100).toInt()}%", color = Palette.secondary,
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MoodPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Palette.primary else Palette.elevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            color = if (selected) Palette.background else Palette.text,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

private fun percent(value: Double): String = "${(value.coerceIn(0.0, 1.0) * 100).toInt()}%"
