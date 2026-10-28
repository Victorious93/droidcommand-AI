package ai.droidcommand.app.ui.theme

import ai.droidcommand.app.R
import ai.droidcommand.security.FleetThemePalette
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Overlay provider `palette.*` onto the existing scheme. A missing or
 * invalid hex leaves the app default — this never invents a color.
 */
fun ColorScheme.withFleetPalette(palette: FleetThemePalette): ColorScheme {
    val background = parseFleetColor(palette.background)
    val surface = parseFleetColor(palette.surface)
    val primary = parseFleetColor(palette.primary)
    val secondary = parseFleetColor(palette.secondary)
    val onBackground = parseFleetColor(palette.onBackground)
    val muted = parseFleetColor(palette.muted)
    val danger = parseFleetColor(palette.danger)
    val tile = parseFleetColor(palette.tile)
    return copy(
        primary = primary ?: this.primary,
        secondary = secondary ?: this.secondary,
        background = background ?: this.background,
        onBackground = onBackground ?: this.onBackground,
        surface = surface ?: this.surface,
        onSurface = onBackground ?: this.onSurface,
        onSurfaceVariant = muted ?: this.onSurfaceVariant,
        error = danger ?: this.error,
        surfaceVariant = tile ?: this.surfaceVariant,
        surfaceContainer = surface ?: this.surfaceContainer,
        surfaceContainerLow = background ?: this.surfaceContainerLow,
        surfaceContainerHigh = tile ?: this.surfaceContainerHigh,
        surfaceContainerHighest = tile ?: this.surfaceContainerHighest,
    )
}

internal fun parseFleetColor(hex: String?): Color? {
    val raw = hex?.trim().orEmpty()
    if (!raw.startsWith("#")) return null
    val digits = raw.substring(1)
    val value = when (digits.length) {
        6 -> digits.toLongOrNull(16)?.let { 0xFF000000L or it }
        8 -> digits.toLongOrNull(16)
        else -> null
    } ?: return null
    return Color(value.toInt())
}

/** Quiet, dismissible hint when VictorLaunch has not pinned this install. */
@Composable
fun FleetThemeTrustHint(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.fleet_theme_trust_hint),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.fleet_theme_trust_dismiss))
            }
        }
    }
}
