package ai.droidcommand.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * The app's one button style. Every screen uses these instead of raw Material buttons so shape, height,
 * padding and colours stay the same everywhere. Colours come from [MaterialTheme.colorScheme], so the
 * light/dark choice, the accent picker, dynamic colour and the fleet palette all still apply.
 *
 * - [DroidPrimaryButton]: the main action on a card or screen (filled accent).
 * - [DroidSecondaryButton]: other actions (outlined, accent text).
 * - [DroidTextButton]: low-emphasis actions such as dialog buttons and "Cancel".
 * - [DroidDangerButton]: anything that deletes, wipes or overwrites (filled red). The confirmation dialog
 *   in front of such an action is still the caller's job; this only makes the risk visible.
 * - [DroidIconButton]: icon-only actions.
 */
object DroidButtonStyle {
    val Shape: Shape = RoundedCornerShape(14.dp)
    val MinHeight = 44.dp
    val Padding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
    val TextPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
}

@Composable
fun DroidPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = DroidButtonStyle.MinHeight),
        enabled = enabled,
        shape = DroidButtonStyle.Shape,
        contentPadding = DroidButtonStyle.Padding,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 2.dp),
        content = content,
    )
}

@Composable
fun DroidSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = DroidButtonStyle.MinHeight),
        enabled = enabled,
        shape = DroidButtonStyle.Shape,
        contentPadding = DroidButtonStyle.Padding,
        border = BorderStroke(
            1.dp,
            if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline,
        ),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        content = content,
    )
}

@Composable
fun DroidTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = DroidButtonStyle.Shape,
        contentPadding = DroidButtonStyle.TextPadding,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        content = content,
    )
}

@Composable
fun DroidDangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = DroidButtonStyle.MinHeight),
        enabled = enabled,
        shape = DroidButtonStyle.Shape,
        contentPadding = DroidButtonStyle.Padding,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 2.dp),
        content = content,
    )
}

/** Icon-only action. [filled] gives it a tonal circle for the one main icon action on a bar (e.g. Send). */
@Composable
fun DroidIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    colors: IconButtonColors? = null,
    content: @Composable () -> Unit,
) {
    if (filled) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = colors ?: IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            content = content,
        )
    } else {
        IconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = colors ?: IconButtonDefaults.iconButtonColors(),
            content = content,
        )
    }
}
