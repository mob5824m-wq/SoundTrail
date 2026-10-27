package tech.egrie.soundtrail.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal object Palette {
    val background = Color(0xFF101114)
    val surface = Color(0xFF1B1D22)
    val elevated = Color(0xFF24272D)
    val border = Color(0xFF35383D)
    val primary = Color(0xFFD9F67A)
    val primaryDark = Color(0xFF303D19)
    val text = Color(0xFFF5F5F1)
    val secondary = Color(0xFFA8AAA9)
    val muted = Color(0xFF797D7B)
    val coral = Color(0xFFFFB69A)
    val lilac = Color(0xFFC6B5FA)

    /**
     * A warm accent for the reading's own bars (energy, tempo).
     *
     * This used to be a streaming service's brand red. The services are gone, and with them every
     * brand colour but one; the accent stays because the mood card still wants a second hue next
     * to the primary, and nothing about it says whose app it came from.
     */
    val ember = Color(0xFFFF645E)

    /** Jellyfin's own purple, for the server the user runs themselves. */
    val jellyfin = Color(0xFFAA5CC3)
}

private val colors = darkColorScheme(
    primary = Palette.primary,
    onPrimary = Palette.background,
    background = Palette.background,
    onBackground = Palette.text,
    surface = Palette.surface,
    onSurface = Palette.text,
    surfaceVariant = Palette.elevated,
    onSurfaceVariant = Palette.secondary,
    outline = Palette.border,
    error = Palette.coral
)

private val type = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 46.sp, lineHeight = 49.sp, letterSpacing = (-1.7f).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 39.sp, letterSpacing = (-1.0f).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 32.sp, letterSpacing = (-0.7f).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp)
)

@Composable
internal fun SoundtrailTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
