package tech.egrie.soundtrail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tech.egrie.soundtrail.PinInput
import tech.egrie.soundtrail.data.GeoPoint
import tech.egrie.soundtrail.integrations.MusicLinkParser
import java.util.Locale

@Composable
internal fun PinEditor(
    draft: PinDraft,
    currentLocation: GeoPoint?,
    locating: Boolean,
    onBack: () -> Unit,
    onLocate: () -> Unit,
    onSave: (PinInput) -> Boolean
) {
    var title by rememberSaveable(draft.nonce) { mutableStateOf(draft.title) }
    var artist by rememberSaveable(draft.nonce) { mutableStateOf(draft.artist) }
    var url by rememberSaveable(draft.nonce) { mutableStateOf(draft.url) }
    var place by rememberSaveable(draft.nonce) { mutableStateOf("") }
    var note by rememberSaveable(draft.nonce) { mutableStateOf("") }
    // A long-press on the map hands the editor its exact coordinates.
    var manual by rememberSaveable(draft.nonce) { mutableStateOf(draft.lat != null && draft.lon != null) }
    var latitude by rememberSaveable(draft.nonce) { mutableStateOf(draft.lat?.toString() ?: "") }
    var longitude by rememberSaveable(draft.nonce) { mutableStateOf(draft.lon?.toString() ?: "") }
    val provider = MusicLinkParser.fromText(url)?.provider

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Palette.text)
        }
        Spacer(Modifier.height(12.dp))
        Text("New soundspot", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text("Add a song and a place you want to remember.",
            color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        SurfaceCard(Modifier.fillMaxWidth()) {
            Text("Song", style = MaterialTheme.typography.titleMedium, color = Palette.primary)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                title, { title = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Song title *") }
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                artist, { artist = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Artist (optional)") }
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                url, { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Jellyfin song link *") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            if (provider != null) {
                Spacer(Modifier.height(12.dp))
                ProviderPill(provider)
            } else if (url.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Pick a song on the Search tab to fill this in — it searches your own " +
                    "Jellyfin server. A stored link is a server item reference, so it keeps " +
                    "working if your server moves, and it never contains its address or token.",
                    style = MaterialTheme.typography.labelMedium, color = Palette.coral)
            }
        }
        Spacer(Modifier.height(12.dp))
        SurfaceCard(Modifier.fillMaxWidth()) {
            Text("Place", style = MaterialTheme.typography.titleMedium, color = Palette.primary)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                place, { place = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Place name") }, placeholder = { Text("The little café on the corner") }
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LocationOn, null, tint = Palette.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (manual) "Custom coordinates" else if (currentLocation == null) "No location selected" else "Current location",
                        style = MaterialTheme.typography.titleMedium)
                    if (!manual && currentLocation != null) {
                        Text(String.format(Locale.getDefault(), "%.5f, %.5f",
                            currentLocation.latitude, currentLocation.longitude),
                            color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (locating) CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { manual = false; onLocate() }) {
                Text(if (currentLocation == null) "Use my location" else "Refresh location",
                    color = Palette.primary)
            }
            TextButton(onClick = { manual = !manual }) {
                Text(if (manual) "Use device location instead" else "Enter coordinates instead",
                    color = Palette.lilac)
            }
            if (manual) {
                Text("Latitude −90 to 90 · Longitude −180 to 180. Use a decimal point.",
                    color = Palette.secondary, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        latitude, { latitude = it }, modifier = Modifier.weight(1f), singleLine = true,
                        label = { Text("Latitude") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )
                    OutlinedTextField(
                        longitude, { longitude = it }, modifier = Modifier.weight(1f), singleLine = true,
                        label = { Text("Longitude") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                note, { note = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("A note about this place (optional)") }, minLines = 2, maxLines = 4
            )
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Save this soundspot", {
            val point = if (manual) {
                val lat = latitude.trim().toDoubleOrNull()
                val lon = longitude.trim().toDoubleOrNull()
                if (lat != null && lon != null) GeoPoint(lat, lon) else null
            } else currentLocation
            onSave(PinInput(title, artist, place, note, url, point))
        }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text("Pins are saved on this device. All music plays directly in Soundtrail.",
            color = Palette.muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(28.dp))
    }
}
