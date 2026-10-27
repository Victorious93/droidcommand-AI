package ai.droidcommand.app.ui.theme

import ai.droidcommand.security.FleetThemePalette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark-only app chrome — no second theme picker. VictorLaunch is the sole
// writer of a live fleet palette; [fleetPalette] overlays provider values
// onto this scheme. Missing fields keep the constants below (Color.kt).
// Every `MaterialTheme.colorScheme.*` call site in this module reads through
// this scheme, so wrapping the app in it repaints each screen; the
// OpenDroid-style extras (bright-blue title, cyan label, indigo user bubble)
// are the named constants in Color.kt, used by `ui.components`.
private val DroidCommandDarkColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = OnAccent,
    primaryContainer = AccentBlueContainer,
    onPrimaryContainer = AccentBlueBright,
    secondary = AccentIndigo,
    onSecondary = OnAccent,
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
    errorContainer = Color(0xFF3A1416),
    onErrorContainer = Color(0xFFFFB4AB),
)

@Composable
fun DroidCommandTheme(
    fleetPalette: FleetThemePalette = FleetThemePalette(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DroidCommandDarkColorScheme.withFleetPalette(fleetPalette),
        content = content,
    )
}
