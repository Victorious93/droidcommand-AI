package ai.droidcommand.app.ui.settings

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import ai.droidcommand.remotecontrol.ActionOption
import ai.droidcommand.remotecontrol.ActionOptions
import ai.droidcommand.remotecontrol.DraftResult
import ai.droidcommand.remotecontrol.DraftStep
import ai.droidcommand.remotecontrol.Macro
import ai.droidcommand.remotecontrol.MacroDraft
import ai.droidcommand.remotecontrol.MacroDraftBuilder
import ai.droidcommand.remotecontrol.MacroTemplates
import ai.droidcommand.remotecontrol.MacroWhen
import ai.droidcommand.remotecontrol.RemoteControlConfig
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * "When ... do ..." macro builder. Every choice is a chip or one short field; the owner never types an id or an
 * action reference. All validation lives in [MacroDraftBuilder] (JVM-tested). This card only draws it.
 */
@Composable
fun MacroBuilderCard(
    viewModel: RemoteControlViewModel,
    config: RemoteControlConfig,
    editing: Macro?,
    onDone: () -> Unit,
) {
    var draft by remember(editing?.id) { mutableStateOf(editing?.let(MacroDraftBuilder::draftOf) ?: MacroDraft()) }
    var triedToSave by remember(editing?.id) { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    val actions = remember(config.mappings) { ActionOptions.fromMappings(config.mappings) }
    val existingIds = remember(config.macros) { config.macros.map { it.id }.toSet() }
    val preview = remember(draft, config) {
        MacroDraftBuilder.build(draft, config.targets, actions, existingIds, editing?.id)
    }

    DroidCard(modifier = Modifier.testTag("macro_builder")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (editing == null) "New macro" else "Edit macro",
                style = MaterialTheme.typography.titleMedium,
            )

            if (editing == null) {
                Text("Start from", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MacroTemplates.all.forEach { template ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                draft = template.draft.copy(name = draft.name)
                                triedToSave = false
                            },
                            label = { Text(template.title) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                modifier = Modifier.fillMaxWidth().testTag("macro_name"),
                singleLine = true,
                label = { Text("Name") },
            )

            Text("When", style = MaterialTheme.typography.titleSmall)
            WhenChips(draft.whenTrigger) { draft = draft.copy(whenTrigger = it) }
            WhenFields(draft.whenTrigger) { draft = draft.copy(whenTrigger = it) }

            Text("Do", style = MaterialTheme.typography.titleSmall)
            if (config.targets.isEmpty()) {
                Hint("No devices yet. Add one under Target devices above, then come back.")
            }
            if (actions.isEmpty()) {
                Hint("No commands yet. Save one under Command mappings above. A step can only do what a saved command does.")
            }
            draft.steps.forEachIndexed { index, step ->
                StepEditor(
                    position = index + 1,
                    step = step,
                    config = config,
                    actions = actions,
                    canRemove = draft.steps.size > 1,
                    onChange = { changed -> draft = draft.copy(steps = draft.steps.toMutableList().also { it[index] = changed }) },
                    onRemove = { draft = draft.copy(steps = draft.steps.toMutableList().also { it.removeAt(index) }) },
                )
            }
            if (draft.steps.size < MacroDraftBuilder.MAX_STEPS) {
                DroidTextButton(onClick = { draft = draft.copy(steps = draft.steps + DraftStep()) }) { Text("Add another step") }
            }

            when (preview) {
                is DraftResult.Ready -> {
                    Text("Preview", style = MaterialTheme.typography.titleSmall)
                    MacroDraftBuilder.describe(preview.macro).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    preview.warnings.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                is DraftResult.Problems -> if (triedToSave) {
                    preview.problems.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            savedMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DroidPrimaryButton(
                    onClick = {
                        when (val result = viewModel.saveMacroDraft(draft, editing?.id)) {
                            is DraftResult.Ready -> {
                                savedMessage = "Saved \"${result.macro.displayName}\"."
                                draft = MacroDraft()
                                triedToSave = false
                                onDone()
                            }
                            is DraftResult.Problems -> triedToSave = true
                        }
                    },
                    modifier = Modifier.testTag("macro_save"),
                ) { Text("Save macro") }
                if (editing != null) {
                    DroidSecondaryButton(onClick = onDone) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WhenChips(current: MacroWhen, onPick: (MacroWhen) -> Unit) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = current is MacroWhen.Manual, onClick = { onPick(MacroWhen.Manual) }, label = { Text("I tap Run") })
        FilterChip(selected = current is MacroWhen.Voice, onClick = { onPick(MacroWhen.Voice("")) }, label = { Text("I say") })
        FilterChip(selected = current is MacroWhen.TextCommand, onClick = { onPick(MacroWhen.TextCommand("")) }, label = { Text("A text arrives") })
        FilterChip(selected = current is MacroWhen.Daily, onClick = { onPick(MacroWhen.Daily(8, 0)) }, label = { Text("Every day") })
    }
}

@Composable
private fun WhenFields(current: MacroWhen, onChange: (MacroWhen) -> Unit) {
    when (current) {
        MacroWhen.Manual -> Unit
        is MacroWhen.Voice -> OutlinedTextField(
            value = current.phrase,
            onValueChange = { onChange(MacroWhen.Voice(it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Phrase") },
        )
        is MacroWhen.TextCommand -> OutlinedTextField(
            value = current.commandId,
            onValueChange = { onChange(MacroWhen.TextCommand(it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Command word (lowercase)") },
        )
        is MacroWhen.Daily -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = current.hour.toString(),
                onValueChange = { onChange(current.copy(hour = it.toIntOrNull() ?: -1)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Hour (0-23)") },
            )
            OutlinedTextField(
                value = current.minute.toString(),
                onValueChange = { onChange(current.copy(minute = it.toIntOrNull() ?: -1)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Minute") },
            )
        }
    }
}

@Composable
private fun StepEditor(
    position: Int,
    step: DraftStep,
    config: RemoteControlConfig,
    actions: List<ActionOption>,
    canRemove: Boolean,
    onChange: (DraftStep) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Step $position", style = MaterialTheme.typography.bodyMedium)
            if (canRemove) DroidTextButton(onClick = onRemove) { Text("Remove") }
        }
        Hint("On this device")
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            config.targets.forEach { target ->
                FilterChip(
                    selected = step.targetNodeId == target.nodeId,
                    onClick = { onChange(step.copy(targetNodeId = target.nodeId)) },
                    label = { Text(target.displayName) },
                )
            }
        }
        Hint("Do this")
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { action ->
                FilterChip(
                    selected = step.actionRef == action.actionRef,
                    onClick = { onChange(step.copy(actionRef = action.actionRef)) },
                    label = { Text(if (action.destructive) "${action.label} (asks first)" else action.label) },
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Stop everything if this step fails", style = MaterialTheme.typography.bodySmall)
            Switch(checked = step.stopOnFailure, onCheckedChange = { onChange(step.copy(stopOnFailure = it)) })
        }
    }
}
