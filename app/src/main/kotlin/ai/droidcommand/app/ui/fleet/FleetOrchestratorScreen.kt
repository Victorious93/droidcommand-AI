package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.fleet.FleetRowUi
import ai.droidcommand.app.fleet.lastCommandLine
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.theme.AccentRed
import ai.droidcommand.app.ui.theme.AccentSuccess
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.security.FleetReachability
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Live on-device fleet, one row per catalogued member. "Check status" is read-only and never asks for approval.
 * "Send command" always goes through the approval dialog, and is disabled (with the reason shown) for every node that
 * is read-only, has no runner, is only planned, or does not expose a command channel. Nothing is pre-filled: a node the
 * hub has not checked says "Not checked yet".
 */
@Composable
fun FleetOrchestratorScreen(viewModel: FleetOrchestratorViewModel = hiltViewModel()) {
    val rows by viewModel.rows.collectAsState()
    var commandFor by remember { mutableStateOf<FleetRowUi?>(null) }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitleWithHelp("Approved commands", ScreenHelpCatalog.ORCHESTRATOR)
        Text(
            "Check status only asks whether the other app is there. Send command always asks you first. " +
                "Status is remembered only until this app restarts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = viewModel::refreshAll, modifier = Modifier.fillMaxWidth().testTag("fleet_refresh_all")) {
            Text("Check all statuses")
        }
        rows.forEach { row ->
            FleetNodeCard(
                row = row,
                formatTime = { timeFormat.format(Date(it)) },
                onCheck = { viewModel.checkStatus(row.node.id) },
                onSend = { commandFor = row },
            )
        }
    }

    commandFor?.let { row ->
        SendCommandDialog(
            row = row,
            onDismiss = { commandFor = null },
            onSend = { capabilityId, inputJson ->
                commandFor = null
                viewModel.sendCommand(row.node.id, capabilityId, inputJson)
            },
        )
    }
}

@Composable
private fun FleetNodeCard(row: FleetRowUi, formatTime: (Long) -> String, onCheck: () -> Unit, onSend: () -> Unit) {
    DroidCard(modifier = Modifier.testTag("fleet_row_${row.node.id}")) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReachabilityDot(row.state.reachability)
                Spacer(Modifier.width(8.dp))
                Text(row.node.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(row.reachabilityLabel, style = MaterialTheme.typography.labelLarge, color = reachabilityColor(row.state.reachability))
            }
            Text(row.classLabel, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(row.controlLabel, style = MaterialTheme.typography.bodySmall)
            Text(row.state.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(lastCommandLine(row.state.lastCommand, formatTime), style = MaterialTheme.typography.bodySmall)
            row.state.lastCommand?.let { last ->
                Text(last.result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            row.state.checkedAtMs?.let {
                Text("Checked at ${formatTime(it)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onCheck,
                    enabled = row.canCheckStatus,
                    modifier = Modifier.weight(1f).testTag("fleet_check_${row.node.id}"),
                ) { Text(if (row.busy) "Working…" else "Check status") }
                Button(
                    onClick = onSend,
                    enabled = row.canSend,
                    modifier = Modifier.weight(1f).testTag("fleet_send_${row.node.id}"),
                ) { Text("Send command") }
            }
            row.sendDisabledReason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ReachabilityDot(reachability: FleetReachability) {
    Spacer(Modifier.size(12.dp).clip(CircleShape).background(reachabilityColor(reachability)))
}

private fun reachabilityColor(reachability: FleetReachability): Color = when (reachability) {
    FleetReachability.REACHABLE -> AccentSuccess
    FleetReachability.OFFLINE -> AccentRed
    FleetReachability.UNKNOWN -> TextSecondary
}

@Composable
private fun SendCommandDialog(row: FleetRowUi, onDismiss: () -> Unit, onSend: (String, String) -> Unit) {
    var capabilityId by remember { mutableStateOf("") }
    var inputJson by remember { mutableStateOf("{}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Command for ${row.node.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "You will be asked to approve the exact command before anything is sent.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = capabilityId,
                    onValueChange = { capabilityId = it },
                    label = { Text("Capability id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("fleet_capability_id"),
                )
                OutlinedTextField(
                    value = inputJson,
                    onValueChange = { inputJson = it },
                    label = { Text("Input (JSON)") },
                    modifier = Modifier.fillMaxWidth().testTag("fleet_input_json"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(capabilityId.trim(), inputJson) }, enabled = capabilityId.isNotBlank()) {
                Text("Continue to approval")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
