package ai.droidcommand.app.ui.settings

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.remotecontrol.DeliveryMode
import ai.droidcommand.remotecontrol.Macro
import ai.droidcommand.remotecontrol.MacroTriggerKind
import ai.droidcommand.remotecontrol.RemoteAction
import ai.droidcommand.remotecontrol.RemoteCommandMapping
import ai.droidcommand.remotecontrol.TargetDevice
import ai.droidcommand.security.FleetIntegrationClass
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Settings skeleton: mappings, target devices, macros. VictorSMS whitelist and
 * code word live in VictorSMS, not here. Live privileged execution is a stub.
 */
@Composable
fun RemoteControlSettingsSection(viewModel: RemoteControlViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsState()
    SectionLabel("Remote Control")
    DroidCard(modifier = Modifier.testTag("settings_remote_control")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fleet remote commands and macros", style = MaterialTheme.typography.titleMedium)
            Text(
                "One frozen envelope (remote.command / macro.step / macro.run) across EXTERNAL_NODE, " +
                    "BOUND_COMPANION, and TERMUX_IPC. Authorization is package+cert, never a payload " +
                    "field. VictorSMS sender whitelist and code word are configured in VictorSMS " +
                    "(external-control OFF by default). Destructive actions always prompt — YOLO cannot " +
                    "skip them, even if the envelope says destructive=false. Live wipe, power-off, lock, " +
                    "and RustDesk are not implemented; Run still opens the approval dialog, then a stub failure.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            ui.lastPreview?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            ui.lastRun?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    TargetEditor(viewModel)
    ui.config.targets.forEach { TargetRow(it, viewModel) }
    MappingEditor(viewModel)
    ui.config.mappings.forEach { MappingRow(it, viewModel) }
    if (ui.config.mappings.isEmpty()) {
        DroidCard {
            Text(
                "No mappings yet. A mapping needs a voice phrase, an SMS actionRef, or both.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    SectionLabel("Macros")
    MacroEditor(viewModel)
    ui.config.macros.forEach { MacroRow(it, viewModel) }
    if (ui.config.macros.isEmpty()) {
        DroidCard {
            Text(
                "No macros yet. The Macros bot here names an ordered list of fleet steps and binds a trigger. Dry-run validates; Run still asks, then the stub fails.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    SectionLabel("Status tray")
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Consumed node status, published for display. No live Messenger bind.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.statusTray.isEmpty()) {
                Text("No snapshots yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                ui.statusTray.forEach { row ->
                    Text(
                        "${row.sourceNodeRef} · ${row.actionRef}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    SectionLabel("Local backup")
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Exports mappings, targets, macros, and the status tray to a file on this device. API keys and VictorSMS secrets are not included.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.exportBackup() }) { Text("Export local backup") }
                OutlinedButton(onClick = { viewModel.importBackup() }) { Text("Restore local backup") }
            }
        }
    }
}

@Composable
private fun TargetEditor(viewModel: RemoteControlViewModel) {
    var nodeId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("") }
    var integrationClass by remember { mutableStateOf(FleetIntegrationClass.EXTERNAL_NODE) }
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Target devices", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = nodeId, onValueChange = { nodeId = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Node id") })
            OutlinedTextField(value = displayName, onValueChange = { displayName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Display name") })
            OutlinedTextField(value = packageName, onValueChange = { packageName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Package (optional)") })
            FleetIntegrationClass.entries.filter { it != FleetIntegrationClass.HOST_PROCESS_TOOL }.forEach { candidate ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = integrationClass == candidate, onClick = { integrationClass = candidate })
                    Text(candidate.name)
                }
            }
            Button(
                onClick = { viewModel.saveTarget(nodeId, displayName, integrationClass, packageName) },
                enabled = nodeId.isNotBlank() && displayName.isNotBlank(),
            ) { Text("Save target") }
        }
    }
}

@Composable
private fun TargetRow(device: TargetDevice, viewModel: RemoteControlViewModel) {
    DroidCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(device.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${device.nodeId} · ${device.integrationClass}" + (device.packageName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { viewModel.removeTarget(device.nodeId) }) { Text("Remove") }
        }
    }
}

@Composable
private fun MappingEditor(viewModel: RemoteControlViewModel) {
    var id by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var voicePhrase by remember { mutableStateOf("") }
    var smsCommandId by remember { mutableStateOf("") }
    var targetNodeId by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(RemoteAction.CUSTOM) }
    var destructive by remember { mutableStateOf(false) }
    val delivery = action.allowedDelivery
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add or replace a mapping", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = id, onValueChange = { id = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Id") })
            OutlinedTextField(value = displayName, onValueChange = { displayName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Display name") })
            OutlinedTextField(value = voicePhrase, onValueChange = { voicePhrase = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Voice phrase (optional)") })
            OutlinedTextField(value = smsCommandId, onValueChange = { smsCommandId = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("SMS actionRef (optional)") })
            OutlinedTextField(value = targetNodeId, onValueChange = { targetNodeId = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Target node id") })
            RemoteAction.entries.forEach { candidate ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = action == candidate, onClick = {
                        action = candidate
                        if (candidate.destructiveByDefinition) destructive = true
                    })
                    Text(candidate.name)
                }
            }
            Text("Delivery: $delivery", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Destructive")
                Switch(
                    checked = destructive || action.destructiveByDefinition,
                    onCheckedChange = { destructive = it },
                    enabled = !action.destructiveByDefinition,
                )
            }
            Button(
                onClick = {
                    viewModel.saveMapping(id, displayName, voicePhrase, smsCommandId, targetNodeId, action, delivery, destructive)
                },
                enabled = id.isNotBlank() && displayName.isNotBlank() && targetNodeId.isNotBlank(),
            ) { Text("Save mapping") }
        }
    }
}

@Composable
private fun MappingRow(mapping: RemoteCommandMapping, viewModel: RemoteControlViewModel) {
    var phrase by remember { mutableStateOf("") }
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(mapping.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                "${mapping.id} · ${mapping.action} · ${mapping.deliveryMode} · ${mapping.targetNodeId}" +
                    if (mapping.effectiveDestructive) " · destructive" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            mapping.voicePhrase?.let {
                Text("Voice: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            mapping.smsCommandId?.let {
                Text("SMS actionRef: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(value = phrase, onValueChange = { phrase = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Test voice phrase") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.previewVoice(phrase) }, enabled = phrase.isNotBlank()) { Text("Match") }
                Button(onClick = { viewModel.requestRun(mapping.id) }) { Text("Run (asks first)") }
                OutlinedButton(onClick = { viewModel.removeMapping(mapping.id) }) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun MacroEditor(viewModel: RemoteControlViewModel) {
    var id by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var voicePhrase by remember { mutableStateOf("") }
    var smsCommandId by remember { mutableStateOf("") }
    var scheduleExpr by remember { mutableStateOf("") }
    var targetNodeId by remember { mutableStateOf("") }
    var actionRef by remember { mutableStateOf("") }
    var triggerKind by remember { mutableStateOf(MacroTriggerKind.MANUAL) }
    var integrationClass by remember { mutableStateOf(FleetIntegrationClass.EXTERNAL_NODE) }
    var destructive by remember { mutableStateOf(false) }
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Create or replace a macro", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = id, onValueChange = { id = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Id") })
            OutlinedTextField(value = displayName, onValueChange = { displayName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Display name") })
            Text("Trigger", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MacroTriggerKind.entries.forEach { candidate ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = triggerKind == candidate, onClick = { triggerKind = candidate })
                    Text(candidate.name)
                }
            }
            OutlinedTextField(value = voicePhrase, onValueChange = { voicePhrase = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Voice phrase") })
            OutlinedTextField(value = smsCommandId, onValueChange = { smsCommandId = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("SMS actionRef") })
            OutlinedTextField(value = scheduleExpr, onValueChange = { scheduleExpr = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Schedule (stored only)") })
            OutlinedTextField(value = targetNodeId, onValueChange = { targetNodeId = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("First step target") })
            OutlinedTextField(value = actionRef, onValueChange = { actionRef = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("First step actionRef") })
            FleetIntegrationClass.entries.filter { it != FleetIntegrationClass.HOST_PROCESS_TOOL }.forEach { candidate ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = integrationClass == candidate, onClick = { integrationClass = candidate })
                    Text(candidate.name)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("First step destructive")
                Switch(checked = destructive, onCheckedChange = { destructive = it })
            }
            Button(
                onClick = {
                    viewModel.saveMacro(
                        id, displayName, triggerKind, voicePhrase, smsCommandId, scheduleExpr,
                        targetNodeId, integrationClass, actionRef, destructive,
                    )
                },
                enabled = id.isNotBlank() && displayName.isNotBlank() && targetNodeId.isNotBlank() && actionRef.isNotBlank(),
            ) { Text("Save macro") }
        }
    }
}

@Composable
private fun MacroRow(macro: Macro, viewModel: RemoteControlViewModel) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(macro.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                "${macro.id} · ${macro.trigger.kind} · ${macro.steps.size} steps" +
                    if (macro.hasDestructiveStep) " · destructive" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.dryRunMacro(macro.id) }) { Text("Dry-run") }
                Button(onClick = { viewModel.requestMacroRun(macro.id) }) { Text("Run (asks first)") }
                OutlinedButton(onClick = { viewModel.removeMacro(macro.id) }) { Text("Delete") }
            }
        }
    }
}
