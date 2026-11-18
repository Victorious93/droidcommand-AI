package ai.droidcommand.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A labelled switch. The label column takes whatever width the switch leaves (weight 1), so a long label wraps beside
 * the switch instead of running underneath it, and the whole row is the tap target. Put a test tag on [modifier], not
 * on the switch: the switch itself carries no click handler.
 */
@Composable
fun DroidSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f))
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A checkbox with a label that wraps beside it. The whole row is the tap target. */
@Composable
fun DroidCheckRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * Equal-width segmented tabs for switching between the parts of one screen. Built from plain surfaces (no
 * experimental Material API), and each segment reports itself as a selected tab to screen readers.
 */
@Composable
fun DroidTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val outer = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(outer)
            .background(scheme.surfaceVariant)
            .border(1.dp, scheme.outline, outer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selected) scheme.primaryContainer else Color.Transparent,
                modifier = Modifier
                    .weight(1f)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) }),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What a list shows when it has nothing yet: an icon, one line of what is missing, one line of what to do, and the actions. */
@Composable
fun DroidEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        actions()
    }
}

/**
 * A short notice with a tone-matched icon: "Preview", "Not connected", a download that failed. The words always say
 * it; the colour and icon only back them up. Pass [onDismiss] for a notice the owner can close.
 */
@Composable
fun DroidNote(
    text: String,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.Neutral,
    icon: ImageVector? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val (container, content) = pillColors(tone)
    val shown = icon ?: when (tone) {
        PillTone.Neutral -> Icons.Filled.Info
        PillTone.Success -> Icons.Filled.CheckCircle
        PillTone.Warning -> Icons.Filled.Warning
        PillTone.Error -> Icons.Filled.Error
    }
    Surface(color = container, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = if (onDismiss == null) 12.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(shown, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = content, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** A hairline between the rows of a [DroidGroup]. */
@Composable
fun DroidRowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 16.dp))
}

/** The trailing slot of a tappable [DroidListRow] that also reports a state: a [StatusPill], then the chevron. */
@Composable
fun StatusPillWithChevron(text: String, tone: PillTone = PillTone.Neutral) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusPill(text, tone)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
