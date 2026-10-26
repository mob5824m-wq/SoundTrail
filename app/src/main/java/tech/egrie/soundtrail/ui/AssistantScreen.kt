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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.egrie.soundtrail.AppState
import tech.egrie.soundtrail.AssistantUiState
import tech.egrie.soundtrail.assistant.AssistantHit

/**
 * Everything the Ask tab can ask the app to do.
 *
 * The microphone and the speaker are not the app's own services: they belong to the activity,
 * which owns the permission and the recognizer's lifetime, so [onMic] and the availability flags
 * come from there. Everything else is the view model's.
 */
internal data class AssistantActions(
    val onInput: (String) -> Unit,
    val onAsk: (String) -> Unit,
    /** Starts listening, or ends it when the microphone is already open. */
    val onMic: () -> Unit,
    val onSetSpeakReplies: (Boolean) -> Unit,
    val onPlayHit: (AssistantHit) -> Unit,
    val onOpenMood: () -> Unit,
    /** False when this phone has no speech recognition, which hides the button. */
    val micAvailable: Boolean,
    /** False when this phone cannot speak this language, which hides that switch. */
    val speakerAvailable: Boolean
)

/** Two examples that show what a spoken request sounds like, without being a manual. */
private val examples = listOf(
    "Play Sunsets by The xx",
    "Something calm for a long drive"
)

@Composable
internal fun AssistantScreen(state: AppState, actions: AssistantActions, onNotice: (String) -> Unit) {
    val ask = state.assistant

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        BrandHeader()
        Spacer(Modifier.height(18.dp))
        Eyebrow("Ask")
        Spacer(Modifier.height(6.dp))
        Text("Say it, and it finds it", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Ask for a song by talking or typing. Soundtrail looks in your own pins and " +
                "playlists first — those play with no connection at all — and then, with your " +
                "Jellyfin server connected, searches its library and plays what it finds here. " +
                "Describe a feeling instead and the mood engine takes over.",
            color = Palette.secondary, style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(16.dp))
        AskCard(ask, actions, onNotice)

        if (ask.reply != null) {
            Spacer(Modifier.height(16.dp))
            ReplyCard(ask, actions)
        }

        if (ask.reply == null) {
            Spacer(Modifier.height(16.dp))
            SurfaceCard(Modifier.fillMaxWidth()) {
                Text("Try saying", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                examples.forEach { example ->
                    Text(
                        "“$example”",
                        color = Palette.secondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "What the search is: your own Jellyfin server's music library, searched with the " +
                "account you connected on the Connect tab, so what comes back is limited to what " +
                "that server lets you read. Nothing you say is sent anywhere but your own pins, " +
                "playlists and that server.",
            color = Palette.muted, style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AskCard(ask: AssistantUiState, actions: AssistantActions, onNotice: (String) -> Unit) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp)
                    .clip(CircleShape)
                    .background(
                        if (ask.listening) Palette.primary.copy(alpha = .22f)
                        else Palette.primary.copy(alpha = .12f)
                    )
                    .clickable(enabled = actions.micAvailable) {
                        if (actions.micAvailable) actions.onMic()
                        else onNotice("This phone has no speech recognition available.")
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Mic,
                    contentDescription = if (ask.listening) "Stop listening" else "Speak",
                    tint = if (ask.listening) Palette.primary else Palette.muted,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        ask.listening && ask.partial.isNotBlank() -> ask.partial
                        ask.listening -> "Listening…"
                        actions.micAvailable -> "Tap the microphone and ask"
                        else -> "Type your request"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (ask.listening) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Say a song, an artist, or how you feel.",
                        color = Palette.muted, style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = ask.input,
            onValueChange = actions.onInput,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Song, artist, or how you feel") },
            placeholder = { Text("Sunsets by The xx") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.onAsk(ask.input) })
        )
        Spacer(Modifier.height(11.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                if (ask.thinking) "Looking…" else "Ask",
                { actions.onAsk(ask.input) },
                Modifier.weight(1f),
                enabled = !ask.thinking && ask.input.isNotBlank()
            )
            if (ask.listening) {
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = actions.onMic) { Text("Stop", color = Palette.coral) }
            }
        }

        if (ask.thinking) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Looking in your library, then on your Jellyfin server…", color = Palette.secondary)
            }
        }

        if (actions.speakerAvailable) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ask.speakReplies) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                    null,
                    tint = if (ask.speakReplies) Palette.primary else Palette.muted,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Answer out loud",
                    color = Palette.secondary, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = ask.speakReplies,
                    onCheckedChange = actions.onSetSpeakReplies
                )
            }
        }
    }
}

@Composable
private fun ReplyCard(ask: AssistantUiState, actions: AssistantActions) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.MusicNote, null, tint = Palette.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                ask.reply.orEmpty(),
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (ask.question.isNotBlank() && ask.question != ask.reply) {
            Spacer(Modifier.height(6.dp))
            Text(
                "You asked: “${ask.question}”",
                color = Palette.muted, style = MaterialTheme.typography.labelMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }

        if (ask.hits.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = actions.onOpenMood) {
                    Text("Open the Mood tab", color = Palette.lilac)
                }
            }
            return@SurfaceCard
        }

        Spacer(Modifier.height(14.dp))
        ask.hits.forEach { hit ->
            HitRow(hit, actions)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun HitRow(hit: AssistantHit, actions: AssistantActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(Palette.elevated)
                .clickable(enabled = hit.playable) { actions.onPlayHit(hit) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.PlayArrow, contentDescription = "Play ${hit.title}",
                tint = if (hit.playable) Palette.primary else Palette.muted
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(hit.artist.takeIf(String::isNotBlank), hit.detail.takeIf(String::isNotBlank))
                    .joinToString(" · "),
                color = Palette.secondary, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}
