package ai.droidcommand.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark-only. Every MaterialTheme.colorScheme call site in this module reads through this scheme.
private val DroidCommandDarkColorScheme = darkColorScheme(
    primary = AccentBlueButton,
    onPrimary = OnAccentDark,
    primaryContainer = AccentBlueContainer,
    onPrimaryContainer = AccentBlue,
    secondary = AccentIndigo,
    onSecondary = TextPrimary,
    secondaryContainer = UserBubbleDark,
    onSecondaryContainer = TextPrimary,
    tertiary = AccentSky,
    onTertiary = OnAccentDark,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = CardDark,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceDark,
    surfaceContainerLow = BackgroundDark,
    surfaceContainerHigh = CardDark,
    surfaceContainerHighest = CardDark,
    outline = BorderDark,
    outlineVariant = BorderDark,
    error = AccentRed,
    onError = TextPrimary,
    errorContainer = Color(0xFF3A1218),
    onErrorContainer = Color(0xFFFFB4AB),
)

@Composable
fun DroidCommandTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DroidCommandDarkColorScheme,
        content = content,
    )
}
