package ai.droidcommand.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Dark-only for now, by explicit request — no light scheme, no dynamic-color branch,
// no theme toggle. All `MaterialTheme.colorScheme.*`/`MaterialTheme.typography.*` call
// sites in this module (ChatScreen, SettingsScreen, SetToolScreen, MetasploitScreen,
// HomeScreen, ToolsScreen, ToolRunStatusCard) are unchanged — they read through
// whatever scheme MaterialTheme is given, so wrapping the app in this one composable
// repaints every existing screen without touching their own source.
private val DroidCommandDarkColorScheme = darkColorScheme(
    primary = AccentTeal,
    onPrimary = OnAccentTeal,
    primaryContainer = AccentTealDark,
    onPrimaryContainer = AccentTeal,
    secondary = SlateSecondary,
    onSecondary = SurfaceDarkest,
    secondaryContainer = SlateSecondaryContainer,
    onSecondaryContainer = OnSlateSecondaryContainer,
    tertiary = SlateSecondary,
    onTertiary = SurfaceDarkest,
    background = SurfaceDarkest,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceDarkElevated,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainer = SurfaceDark,
    surfaceContainerLow = SurfaceDarkest,
    surfaceContainerHigh = SurfaceDarkElevated,
    surfaceContainerHighest = SurfaceDarkElevated2,
    outline = OutlineDark,
    outlineVariant = OutlineDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
)

/**
 * App-wide theme. Wrap [MainActivity]'s content in this instead of a bare
 * `MaterialTheme { }` to replace Compose's baseline purple Material 3 scheme
 * with a neutral dark/teal one everywhere in the app.
 */
@Composable
fun DroidCommandTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DroidCommandDarkColorScheme,
        content = content,
    )
}
