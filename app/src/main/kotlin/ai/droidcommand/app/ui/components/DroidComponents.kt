package ai.droidcommand.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Letter-spaced bright-blue screen header. Pass uppercase = false for a user-chosen mode label. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, uppercase: Boolean = true) {
    Text(
        text = if (uppercase) text.uppercase() else text,
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 3.sp,
    )
}

/** Small uppercase grey section label ("PLAN SEQUENCE STAGE" style). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
}

/** Rounded, 1dp-bordered card on the elevated surface colour — the repeated container in OpenDroid's screens. */
@Composable
fun DroidCard(
    modifier: Modifier = Modifier,
    borderColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.outline,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clip(shape).clickable(onClick = onClick) else it },
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/** Tone of a [StatusPill]. Success stays green on every theme so "ready" never looks like the brand accent. */
enum class PillTone { Neutral, Success, Warning, Error }

/** Container and content colours for a [PillTone]. Shared by [StatusPill] and the notice banner so they cannot drift apart. */
@Composable
internal fun pillColors(tone: PillTone): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        PillTone.Neutral -> scheme.surface to scheme.onSurfaceVariant
        PillTone.Success -> Color(0xFF3DDC84).copy(alpha = 0.16f) to Color(0xFF3DDC84)
        PillTone.Warning -> Color(0xFFFFB74D).copy(alpha = 0.16f) to Color(0xFFFFB74D)
        PillTone.Error -> scheme.error.copy(alpha = 0.16f) to scheme.error
    }
}

/** Small rounded status label: "Saved", "Not set", "Needs attention". The text always says it; colour only backs it up. */
@Composable
fun StatusPill(text: String, tone: PillTone = PillTone.Neutral, modifier: Modifier = Modifier) {
    val (container, content) = pillColors(tone)
    Surface(color = container, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            text = text,
            color = content,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * One tappable line: leading icon, title, one-line subtitle and a trailing slot (a [StatusPill], a switch, or the
 * default chevron). Replaces the "paragraph of help text above a full-width button" pattern so a screen of
 * settings is scannable instead of a wall of cards.
 */
@Composable
fun DroidListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    },
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

/** A bordered card that holds [DroidListRow]s with no inner padding, so the rows run edge to edge. */
@Composable
fun DroidGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        content = content,
    )
}
