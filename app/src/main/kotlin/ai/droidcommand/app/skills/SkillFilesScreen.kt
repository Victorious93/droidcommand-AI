package ai.droidcommand.app.skills

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Skill files are instruction folders (SKILL.md) the AI can turn on.
 * These are not the older "Chat prompts" saved in the Chat library.
 */
@Composable
fun SkillFilesScreen(viewModel: SkillFilesViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsState()
    val drafts = remember { mutableStateMapOf<String, String>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("skill_files_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Skill files")
        Text(
            "These are instruction files the AI can follow. They are different from Chat prompts " +
                "(the short system text you save inside Chat). Turning a skill on adds its instructions " +
                "to later AI work. Tools a skill mentions still need your approval.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ui.error != null) {
            Text(ui.error!!, color = MaterialTheme.colorScheme.error)
        }
        if (ui.status != null) {
            Text(ui.status!!, color = MaterialTheme.colorScheme.primary)
        }
        DroidSecondaryButton(onClick = viewModel::refresh, enabled = !ui.busy) { Text("Refresh list") }
        if (ui.skills.isEmpty()) {
            Text(
                "No skill files found yet. Put a SKILL.md folder under the skills directory on this phone, then refresh.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ui.skills.forEach { row ->
            DroidCard(modifier = Modifier.testTag("skill_file_${row.name}")) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.name, style = MaterialTheme.typography.titleMedium)
                            Text(row.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = row.enabled,
                            onCheckedChange = { on -> viewModel.setEnabled(row.name, on) },
                            modifier = Modifier.testTag("skill_file_toggle_${row.name}"),
                        )
                    }
                    if (row.parameters.isNotEmpty()) {
                        SectionLabel("Settings to fill in")
                        row.parameters.forEach { param ->
                            val key = "${row.name}.${param.name}"
                            val value = drafts[key] ?: row.values[param.name].orEmpty()
                            OutlinedTextField(
                                value = value,
                                onValueChange = { drafts[key] = it },
                                label = {
                                    Text(
                                        buildString {
                                            append(param.name)
                                            if (param.required) append(" (required)")
                                            if (param.description.isNotBlank()) append(" — ").append(param.description)
                                        },
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("skill_param_${row.name}_${param.name}"),
                            )
                        }
                        DroidPrimaryButton(
                            onClick = {
                                val values = row.parameters.associate { param ->
                                    val key = "${row.name}.${param.name}"
                                    param.name to (drafts[key] ?: row.values[param.name].orEmpty())
                                }
                                viewModel.saveParameters(row.name, values)
                            },
                        ) { Text("Save settings") }
                    }
                    if (row.declaredTools.isNotEmpty()) {
                        Text(
                            "Mentions tools: " + row.declaredTools.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (row.missingRequired.isNotEmpty()) {
                        Text(
                            "Still empty: " + row.missingRequired.joinToString(", "),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        DroidTextButton(onClick = viewModel::openSkillsFolder) { Text("Open skills folder path") }
        if (ui.folderHint != null) {
            Text(ui.folderHint!!, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}
