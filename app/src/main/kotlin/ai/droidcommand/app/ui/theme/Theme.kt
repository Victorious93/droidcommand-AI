package ai.droidcommand.app.ui.theme

import ai.droidcommand.config.ThemeAccent
import ai.droidcommand.config.ThemePreference
import ai.droidcommand.security.FleetThemePalette
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

data class AccentSwatch(
    val primary: Color,
    val bright: Color,
    val container: Color,
    val secondary: Color,
    val tertiary: Color,
)

fun ThemeAccent.swatch(): AccentSwatch = when (this) {
    ThemeAccent.BLUE -> AccentSwatch(AccentBlue, AccentBlueBright, AccentBlueContainer, AccentIndigo, AccentCyan)
    ThemeAccent.TEAL -> AccentSwatch(AccentTeal, AccentTealBright, AccentTealContainer, AccentCyan, AccentBlueBright)
    ThemeAccent.PURPLE -> AccentSwatch(AccentPurple, AccentPurpleBright, AccentPurpleContainer, AccentIndigo, AccentPurpleBright)
    ThemeAccent.ORANGE -> AccentSwatch(AccentOrange, AccentOrangeBright, AccentOrangeContainer, AccentOrangeBright, AccentCyan)
    ThemeAccent.GREEN -> AccentSwatch(AccentGreen, AccentGreenBright, AccentGreenContainer, AccentSuccess, AccentCyan)
}

fun buildAppColorScheme(preference: ThemePreference, dark: Boolean): ColorScheme {
    val swatch = preference.accent.swatch()
    return if (dark) {
        darkColorScheme(
            primary = swatch.primary,
            onPrimary = OnAccent,
            primaryContainer = swatch.container,
            onPrimaryContainer = swatch.bright,
            secondary = swatch.secondary,
            onSecondary = OnAccent,
            secondaryContainer = UserBubbleDark,
            onSecondaryContainer = TextPrimary,
            tertiary = swatch.tertiary,
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
    } else {
        lightColorScheme(
            primary = swatch.primary,
            onPrimary = OnAccent,
            primaryContainer = swatch.container,
            onPrimaryContainer = swatch.bright,
            secondary = swatch.secondary,
            onSecondary = OnAccent,
            secondaryContainer = UserBubbleLight,
            onSecondaryContainer = TextOnLight,
            tertiary = swatch.tertiary,
            onTertiary = OnAccentDark,
            background = BackgroundLight,
            onBackground = TextOnLight,
            surface = SurfaceLight,
            onSurface = TextOnLight,
            surfaceVariant = CardLight,
            onSurfaceVariant = TextSecondaryLight,
            surfaceContainer = SurfaceLight,
            surfaceContainerLow = BackgroundLight,
            surfaceContainerHigh = CardLight,
            surfaceContainerHighest = CardLight,
            outline = BorderLight,
            outlineVariant = BorderLight,
            error = AccentRed,
            onError = OnAccent,
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
        )
    }
}

@Composable
fun DroidCommandTheme(
    preference: ThemePreference = ThemePreference(),
    fleetPalette: FleetThemePalette = FleetThemePalette(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = preference.resolvesToDark(systemDark)
    val context = LocalContext.current
    val useDynamic = preference.dynamicColor && Build.VERSION.SDK_INT >= 31
    val base = if (useDynamic) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        buildAppColorScheme(preference, dark)
    }
    MaterialTheme(
        colorScheme = base.withFleetPalette(fleetPalette),
        content = content,
    )
}
