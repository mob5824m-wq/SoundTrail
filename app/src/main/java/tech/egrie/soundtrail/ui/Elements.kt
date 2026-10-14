package tech.egrie.soundtrail.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.egrie.soundtrail.data.MusicPin
import tech.egrie.soundtrail.integrations.MusicProvider
import tech.egrie.soundtrail.integrations.TrackResult
import tech.egrie.soundtrail.playback.NowPlaying
import java.util.Locale

internal val cardShape = RoundedCornerShape(18.dp)

@Composable
internal fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(28.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(Palette.primary, radius = size.width * .47f, center = center)
            drawCircle(Palette.background, radius = size.width * .29f, center = center)
            drawLine(
                Palette.primary, Offset(center.x + size.width * .1f, center.y - size.height * .16f),
                Offset(center.x + size.width * .1f, center.y + size.height * .11f),
                strokeWidth = size.width * .07f, cap = StrokeCap.Round
            )
            drawCircle(Palette.primary, radius = size.width * .065f,
                center = Offset(center.x + size.width * .04f, center.y + size.height * .13f))
        }
        Spacer(Modifier.width(9.dp))
        Text("soundtrail", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, letterSpacing = (-0.7f).sp)
    }
}

@Composable
internal fun Eyebrow(text: String, color: Color = Palette.primary) {
    Text(text.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelMedium,
        letterSpacing = 1.7f.sp, color = color)
}

@Composable
internal fun SectionHeading(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(action, color = Palette.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(cardShape).background(Palette.surface)
            .border(1.dp, Palette.border.copy(alpha = .42f), cardShape).padding(16.dp),
        content = content
    )
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.primary, contentColor = Palette.background,
            disabledContainerColor = Palette.elevated, disabledContentColor = Palette.muted
        )
    ) { Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
internal fun ProviderArtwork(provider: MusicProvider, modifier: Modifier = Modifier) {
    val gradient = when (provider) {
        MusicProvider.SPOTIFY -> listOf(Palette.primaryDark, Color(0xFF5A6740))
        MusicProvider.YOUTUBE -> listOf(Color(0xFF4A2E2E), Color(0xFFB04040))
        MusicProvider.YOUTUBE_MUSIC -> listOf(Color(0xFF49313D), Color(0xFF9B5960))
        MusicProvider.SOUNDCLOUD -> listOf(Color(0xFF4A2E1A), Color(0xFF9C4A1F))
        MusicProvider.JELLYFIN -> listOf(Color(0xFF2E2440), Color(0xFF7A4E9B))
    }
    Box(
        modifier.size(55.dp).clip(RoundedCornerShape(15.dp))
            .background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = Palette.text, modifier = Modifier.size(26.dp))
    }
}

@Composable
internal fun ProviderPill(provider: MusicProvider) {
    val color = when (provider) {
        MusicProvider.SPOTIFY -> Palette.spotify
        MusicProvider.YOUTUBE -> Palette.youtube
        MusicProvider.YOUTUBE_MUSIC -> Palette.youtube
        MusicProvider.SOUNDCLOUD -> Palette.soundcloud
        MusicProvider.JELLYFIN -> Palette.jellyfin
    }
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = .13f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(provider.label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun PinCard(
    pin: MusicPin, distanceMeters: Double?, onPlay: () -> Unit, onMap: () -> Unit, onDelete: () -> Unit
) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderArtwork(pin.provider)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(pin.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium)
                Text(pin.artist.ifBlank { pin.provider.label }, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = Palette.secondary,
                    style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onPlay, modifier = Modifier.size(42.dp).background(Palette.primary, CircleShape)) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${pin.title} in Soundtrail",
                    tint = Palette.background)
            }
        }
        Spacer(Modifier.height(15.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderPill(pin.provider)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.LocationOn, null, tint = Palette.primary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(3.dp))
            Text(distanceMeters?.let(::formatDistance) ?: "Saved place", color = Palette.secondary,
                style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(12.dp))
        Text(pin.place, style = MaterialTheme.typography.bodyMedium, color = Palette.text)
        if (pin.note.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(pin.note, style = MaterialTheme.typography.bodyMedium, color = Palette.secondary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onMap) { Text("View place", color = Palette.secondary) }
            TextButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(16.dp), tint = Palette.muted)
                Spacer(Modifier.width(4.dp))
                Text("Remove", color = Palette.muted)
            }
        }
    }
}

/**
 * One search result.
 *
 * Tapping the row pins the song — that is what the app is for. When the provider can play the
 * whole track in its own app ([onPlay]), the row also carries a play button: Spotify is the case
 * this exists for, where opening the song in Spotify plays it in full rather than as Soundtrail's
 * 30-second preview.
 */
@Composable
internal fun TrackRow(track: TrackResult, onPin: () -> Unit, onPlay: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface)
            .clickable(onClick = onPin).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProviderArtwork(track.provider, Modifier.size(46.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = Palette.secondary)
        }
        if (onPlay != null) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Palette.elevated)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.PlayArrow, contentDescription = "Play ${track.title} in ${track.provider.label}",
                    tint = Palette.primary, modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Icon(Icons.Rounded.Add, contentDescription = "Pin ${track.title}", tint = Palette.primary)
        Spacer(Modifier.width(5.dp))
    }
}

internal fun formatDistance(meters: Double): String = if (meters < 1_000) {
    "${meters.toInt()} m away"
} else {
    String.format(Locale.getDefault(), "%.1f km away", meters / 1_000)
}

/**
 * The bar above the tabs while a song plays directly in Soundtrail. The same
 * playback also lives behind PlaybackService's notification, so controls here and in the
 * notification shade always act on one player.
 */
@Composable
internal fun NowPlayingBar(nowPlaying: NowPlaying, onToggle: () -> Unit, onStop: () -> Unit) {
    val fraction = if (nowPlaying.durationMs > 0L) {
        (nowPlaying.positionMs.toFloat() / nowPlaying.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val providerColor = when (nowPlaying.provider) {
        tech.egrie.soundtrail.integrations.MusicProvider.SPOTIFY -> Palette.spotify
        tech.egrie.soundtrail.integrations.MusicProvider.SOUNDCLOUD -> Palette.soundcloud
        tech.egrie.soundtrail.integrations.MusicProvider.YOUTUBE_MUSIC -> Palette.youtube
        tech.egrie.soundtrail.integrations.MusicProvider.YOUTUBE -> Palette.youtube
        tech.egrie.soundtrail.integrations.MusicProvider.JELLYFIN -> Palette.jellyfin
    }
    Column(
        Modifier.fillMaxWidth().background(Palette.surface)
            .border(1.dp, Palette.border.copy(alpha = .7f))
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(Palette.elevated)) {
            Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(providerColor))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp).background(providerColor.copy(alpha = .18f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.MusicNote, null, tint = providerColor,
                    modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(nowPlaying.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium)
                val detail = nowPlaying.error ?: listOfNotNull(
                    nowPlaying.artist.takeIf(String::isNotBlank),
                    if (nowPlaying.buffering) "Buffering…" else playbackTime(nowPlaying)
                ).joinToString(" · ")
                Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (nowPlaying.error != null) Palette.coral else Palette.secondary,
                    style = MaterialTheme.typography.labelMedium)
            }
            if (nowPlaying.buffering) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            } else if (nowPlaying.error == null) {
                IconButton(onClick = onToggle, Modifier.size(40.dp)) {
                    if (nowPlaying.playing) {
                        Icon(Icons.Rounded.Pause, "Pause ${nowPlaying.title}", tint = Palette.primary)
                    } else {
                        Icon(Icons.Rounded.PlayArrow, "Play ${nowPlaying.title}", tint = Palette.primary)
                    }
                }
            }
            IconButton(onClick = onStop, Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Close, "Stop playback", tint = Palette.muted)
            }
        }
    }
}

private fun playbackTime(nowPlaying: NowPlaying): String = if (nowPlaying.durationMs <= 0L) {
    formatClock(nowPlaying.positionMs)
} else {
    "${formatClock(nowPlaying.positionMs)} / ${formatClock(nowPlaying.durationMs)}"
}

private fun formatClock(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
