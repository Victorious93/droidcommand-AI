package ai.droidcommand.app.ui.settings

import ai.droidcommand.app.theme.AppThemeSettings
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.theme.swatch
import ai.droidcommand.config.ThemeAccent
import ai.droidcommand.config.ThemeMode
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ThemePickerViewModel @Inject constructor(
    val settings: AppThemeSettings,
) : ViewModel()

@Composable
fun ThemePickerSection(viewModel: ThemePickerViewModel = hiltViewModel()) {
    val preference by viewModel.settings.state.collectAsState()
    DroidCard(modifier = Modifier.testTag("theme_picker")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Look", style = MaterialTheme.typography.titleMedium)
            Text(
                "Change the colors of this app. Your choice stays on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Brightness", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = preference.mode == mode,
                        onClick = { viewModel.settings.setMode(mode) },
                        label = { Text(modeLabel(mode)) },
                        modifier = Modifier.testTag("theme_mode_${mode.name.lowercase()}"),
                    )
                }
            }
            Text("Accent color", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeAccent.entries.forEach { accent ->
                    val selected = preference.accent == accent
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(accent.swatch().primary)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            )
                            .clickable { viewModel.settings.setAccent(accent) }
                            .testTag("theme_accent_${accent.name.lowercase()}"),
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Use this phone's colors")
                        Text(
                            "Matches the wallpaper colors on Android 12 and newer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = preference.dynamicColor,
                        onCheckedChange = viewModel.settings::setDynamicColor,
                        modifier = Modifier.testTag("theme_dynamic"),
                    )
                }
            }
        }
    }
}

private fun modeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "Match phone"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}
