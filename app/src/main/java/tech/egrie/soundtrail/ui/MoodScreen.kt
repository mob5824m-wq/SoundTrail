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
import tech.egrie.soundtrail.mood.MoodAiProvider
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
    /** Switch between the on-device engine, Ollama, and a hosted model. */
    val onSetAiProvider: (MoodAiProvider) -> Unit,
    val onSetAiModel: (String) -> Unit,
    val onSetOllamaUrl: (String) -> Unit,
    val onSetOllamaModel: (String) -> Unit,
    /** The address of an OpenAI-compatible endpoint, and the model to ask for there. */
    val onSetOpenAiUrl: (String) -> Unit,
    val onSetOpenAiModel: (String) -> Unit,
    /** Fills in Arena's gateway (address and router alias) so only the key is left to paste. */
    val onUseArena: () -> Unit,
    val onDetectModels: () -> Unit,
    val onTestAi: () -> Unit,
    val onSaveAiKey: (String) -> Unit,
    val onClearAiKey: () -> Unit,
    val onOpenInYouTubeMusic: () -> Unit
)

@Composable
internal fun MoodScreen(state: AppState, actions: MoodActions, onNotice: (String) -> Unit) {
    val mood = state.mood

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

        MoodReadingCard(mood, actions, onNotice)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Where mood readings come from, and the setup each choice needs.
 *
 * The on-device engine is first and needs nothing, Ollama keeps the model on hardware the
 * listener controls, and the hosted key is last. The card says plainly what leaves the device for
 * whichever is selected, because that is the whole reason to have a choice.
 */
@Composable
private fun MoodReadingCard(mood: MoodUiState, actions: MoodActions, onNotice: (String) -> Unit) {
    var keyDraft by rememberSaveable(mood.aiProvider) { mutableStateOf("") }
    var showKeyField by rememberSaveable(mood.aiProvider) { mutableStateOf(false) }
    val provider = mood.aiProvider

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
                    when {
                        !mood.aiEnabled -> "On-device \u00b7 works offline, nothing sent"
                        provider == MoodAiProvider.ON_DEVICE -> "On-device \u00b7 nothing sent"
                        provider == MoodAiProvider.OLLAMA -> "Ollama \u00b7 ${mood.ollamaModel}"
                        provider == MoodAiProvider.OPENAI_COMPATIBLE ->
                            "OpenAI-compatible \u00b7 " +
                                mood.openAiModel.ifBlank { "no model chosen yet" }
                        else -> "Gemini \u00b7 ${mood.aiModel}"
                    },
                    color = Palette.secondary, style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(
                checked = mood.aiEnabled,
                onCheckedChange = { enabled ->
                    actions.onSetAiEnabled(enabled)
                    if (enabled && provider != MoodAiProvider.ON_DEVICE && !mood.aiReady) {
                        showKeyField = provider == MoodAiProvider.GEMINI
                    }
                }
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("Run the reading", style = MaterialTheme.typography.labelMedium, color = Palette.muted)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MoodAiProvider.values().forEach { option ->
                MoodPill(
                    text = option.short,
                    selected = provider == option,
                    onClick = { actions.onSetAiProvider(option) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(provider.blurb, color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)

        when (provider) {
            MoodAiProvider.ON_DEVICE -> {
                Spacer(Modifier.height(6.dp))
                Text(
                    "A small, explainable model reads phrases, negation, emoji, the time of day, " +
                        "and \u2014 only while the automation watch is on \u2014 whether you are walking, " +
                        "running, or driving. No key, no account, no network.",
                    color = Palette.muted, style = MaterialTheme.typography.labelMedium
                )
            }

            MoodAiProvider.OLLAMA -> {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = mood.ollamaUrl,
                    onValueChange = actions.onSetOllamaUrl,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Ollama address") },
                    placeholder = { Text("http://192.168.0.20:11434") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(
                        if (mood.aiBusy) "Checking\u2026" else "Find models",
                        actions.onDetectModels,
                        Modifier.weight(1f),
                        enabled = !mood.aiBusy && mood.ollamaUrl.isNotBlank()
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = actions.onTestAi, enabled = !mood.aiBusy) {
                        Text("Test", color = Palette.lilac)
                    }
                }
                if (mood.ollamaModels.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        mood.ollamaModels.forEach { name ->
                            MoodPill(
                                text = name,
                                selected = mood.ollamaModel == name,
                                onClick = { actions.onSetOllamaModel(name) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = mood.ollamaModel,
                    onValueChange = actions.onSetOllamaModel,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Model") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Ollama has to be reachable from this device: start it with " +
                        "OLLAMA_HOST=0.0.0.0:11434, and on a phone use the computer's address on " +
                        "your network \u2014 \u201Clocalhost\u201D here means this device. Local models " +
                        "can take up to a minute; the app waits three.",
                    color = Palette.muted, style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showKeyField = !showKeyField }) {
                    Text(
                        if (showKeyField) "Hide token" else "Add a token (only if your server asks)",
                        color = Palette.lilac
                    )
                }
                if (showKeyField) {
                    KeyField("Ollama token", keyDraft, { keyDraft = it }, mood, provider, actions)
                }
            }

            MoodAiProvider.OPENAI_COMPATIBLE -> {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = mood.openAiUrl,
                    onValueChange = actions.onSetOpenAiUrl,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Endpoint address") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(
                        if (mood.aiBusy) "Checking\u2026" else "Find models",
                        actions.onDetectModels,
                        Modifier.weight(1f),
                        enabled = !mood.aiBusy && mood.openAiUrl.isNotBlank()
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = actions.onTestAi, enabled = !mood.aiBusy) {
                        Text("Test", color = Palette.lilac)
                    }
                }
                if (mood.openAiModels.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        mood.openAiModels.forEach { name ->
                            MoodPill(
                                text = name,
                                selected = mood.openAiModel == name,
                                onClick = { actions.onSetOpenAiModel(name) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = mood.openAiModel,
                    onValueChange = actions.onSetOpenAiModel,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Model") },
                    placeholder = { Text("gpt-4o-mini") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = actions.onUseArena) {
                        Text("Use Arena (preview)", color = Palette.lilac)
                    }
                    if (MoodAiUrls.isArena(MoodAiUrls.normalizeOpenAi(mood.openAiUrl))) {
                        TextButton(
                            onClick = {
                                onNotice(
                                    "Create a key at ${MoodAiUrls.ARENA_KEYS_URL} \u2014 copy it " +
                                        "while the dialog is open; it is not shown again."
                                )
                            }
                        ) {
                            Text("Where do I get a key?  \u2197", color = Palette.lilac)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showKeyField = !showKeyField }) {
                    Text(
                        if (showKeyField) "Hide API key" else "Add the API key (only if it asks for one)",
                        color = Palette.lilac
                    )
                }
                if (showKeyField) {
                    KeyField("API key", keyDraft, { keyDraft = it }, mood, provider, actions)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Anything that speaks OpenAI's chat-completions API works here: OpenAI, " +
                        "OpenRouter, Arena (tap Use Arena to fill it in), a model running on your " +
                        "own machine, or a bridge in front of a service that has no API of its " +
                        "own. Paste the address however you found it \u2014 a whole " +
                        "\u2026/v1/chat/completions endpoint is trimmed for you, and a bare host " +
                        "gets /v1. A server that wants no key is fine: leave the key empty and " +
                        "none is sent.",
                    color = Palette.muted, style = MaterialTheme.typography.labelMedium
                )
            }

            MoodAiProvider.GEMINI -> {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { showKeyField = !showKeyField }) {
                    Text(
                        if (showKeyField) "Hide API key" else "Set up the API key (bring your own)",
                        color = Palette.lilac
                    )
                }
                if (showKeyField || (mood.aiEnabled && !mood.aiReady)) {
                    KeyField("Google AI Studio API key", keyDraft, { keyDraft = it }, mood, provider, actions)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = mood.aiModel,
                        onValueChange = actions.onSetAiModel,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Model name") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    TextButton(onClick = { onNotice("Open ${provider.keyUrl} to create or manage a key.") }) {
                        Text("Where do I get a key?  \u2197", color = Palette.lilac)
                    }
                }
            }
        }

        if (provider != MoodAiProvider.ON_DEVICE) {
            Spacer(Modifier.height(8.dp))
            mood.aiNote?.let { note ->
                Text(note, color = Palette.secondary, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                "Only your mood description, the energy hint, the hour, and whether you are moving " +
                    "are sent. Your songs, playlists, pins, and places never leave the device, and any " +
                    "failure falls back to the on-device reading.",
                color = Palette.muted, style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/** The credential field, shared by the providers that take one. */
@Composable
private fun KeyField(
    label: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    mood: MoodUiState,
    provider: MoodAiProvider,
    actions: MoodActions
) {
    Spacer(Modifier.height(6.dp))
    provider.keyHint.takeIf { it.isNotBlank() }?.let { hint ->
        Text(hint, style = MaterialTheme.typography.labelMedium, color = Palette.secondary)
        Spacer(Modifier.height(6.dp))
    }
    OutlinedTextField(
        value = draft,
        onValueChange = { onDraftChange(it.trim().take(200)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
    )
    Spacer(Modifier.height(8.dp))
    if (mood.aiKeySet) {
        Text(
            "Saved on this device, encrypted with Android Keystore. It is never synced, never " +
                "backed up, and never included in exports.",
            color = Palette.primary, style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(6.dp))
    }
    PrimaryButton(
        if (mood.aiKeySet) "Replace" else "Save",
        { actions.onSaveAiKey(draft); onDraftChange("") },
        Modifier.fillMaxWidth(),
        enabled = draft.isNotBlank()
    )
    if (mood.aiKeySet) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Check, null, tint = Palette.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Saved", color = Palette.primary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = actions.onClearAiKey) {
                Text("Remove", color = Palette.coral)
            }
        }
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
