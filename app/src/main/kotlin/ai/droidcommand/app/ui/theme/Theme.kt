package ai.droidcommand.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark-only, by explicit request — no light scheme, no dynamic-color branch, no theme toggle.
// Every `MaterialTheme.colorScheme.*` call site in this module reads through this scheme, so
// wrapping the app in it repaints each screen; the OpenDroid-style extras (neon title, cyan
// label, purple user bubble) are the named constants in Color.kt, used by `ui.components`.
private val DroidCommandDarkColorScheme = darkColorScheme(
    primary = AccentGreenButton,
    onPrimary = OnAccentDark,
    primaryContainer = AccentGreenContainer,
    onPrimaryContainer = AccentNeonGreen,
    secondary = AccentPurple,
    onSecondary = TextPrimary,
    secondaryContainer = UserBubbleDark,
    onSecondaryContainer = TextPrimary,
    tertiary = AccentCyan,
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
    errorContainer = Color(0xFF3A1210),
    onErrorContainer = Color(0xFFFFB4AB),
)

@Composable
fun DroidCommandTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DroidCommandDarkColorScheme,
        content = content,
    )
}
