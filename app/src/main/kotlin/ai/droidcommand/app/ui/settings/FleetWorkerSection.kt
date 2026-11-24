package ai.droidcommand.app.ui.settings

import ai.droidcommand.app.fleetworker.PairingInput
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * "Fleet worker (optional)" in Settings. Both switches are off until the owner turns them on. Compiles; never run on a device.
 * The wording says what the feature does today, including that its one job type never asks for a confirmation, so the batch
 * switch has nothing to act on yet.
 */
@Composable
fun FleetWorkerSection(viewModel: FleetWorkerViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsState()
    val s = ui.status
    SectionLabel("Fleet worker (optional)")

    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Take jobs from my fleet coordinator", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = s.workerEnabled,
                    onCheckedChange = viewModel::setWorkerEnabled,
                    enabled = s.paired || s.workerEnabled,
                    modifier = Modifier.testTag("fleet_worker_switch"),
                )
            }
            Note(
                "Off by default. When on, this phone stays connected to your own VictorLinux fleet coordinator and runs the jobs it sends. " +
                    "A notification with a Switch off button shows while it is on. Today the only job type is hashing files in a private " +
                    "folder: no root, no screen control, nothing outside this app's own storage.",
            )
            Text(s.summary(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("fleet_worker_status"))
            ui.note?.let { Note(it) }
        }
    }

    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Pairing", style = MaterialTheme.typography.titleMedium)
            if (s.paired) {
                Note("Paired as ${s.deviceId} with ${s.coordinatorUrl}.")
                DroidSecondaryButton(onClick = viewModel::unpair, modifier = Modifier.testTag("fleet_worker_unpair")) { Text("Unpair") }
                Note("Unpairing forgets the coordinator on this phone, stops any job, ends every batch approval and switches the worker off. Revoke the phone on the coordinator too.")
            } else {
                when (val p = ui.pairing) {
                    is PairingUi.Confirm -> {
                        Text("Does this match what the coordinator shows?", style = MaterialTheme.typography.bodyMedium)
                        Note("Address: ${p.payload.coordinatorUrl}")
                        Text(
                            PairingInput.display(p.payload.certFingerprintSha256),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("fleet_worker_fingerprint"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DroidPrimaryButton(onClick = viewModel::confirmPairing, modifier = Modifier.testTag("fleet_worker_confirm_pair")) { Text("Pair with this coordinator") }
                            DroidSecondaryButton(onClick = viewModel::cancelPairing) { Text("Cancel") }
                        }
                    }
                    PairingUi.Working -> Note("Pairing...")
                    else -> {
                        OutlinedTextField(
                            value = ui.pasted,
                            onValueChange = viewModel::onPasted,
                            label = { Text("Pairing text from the coordinator") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth().testTag("fleet_worker_pairing_text"),
                        )
                        DroidPrimaryButton(onClick = viewModel::check, enabled = ui.pasted.isNotBlank(), modifier = Modifier.testTag("fleet_worker_check")) { Text("Check") }
                        (p as? PairingUi.Failed)?.let { Text(it.why, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Note("Run `victor-fleet pair` on the coordinator and paste what it prints. You will compare its fingerprint before anything is sent. QR scanning is not built.")
                    }
                }
            }
        }
    }

    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Batch approval", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = s.batchApprovalEnabled,
                    onCheckedChange = viewModel::setBatchApprovalEnabled,
                    modifier = Modifier.testTag("fleet_batch_switch"),
                )
            }
            Note(
                "Off by default. When a plan has several jobs that each ask you to confirm, you can approve the reviewed list once instead of " +
                    "tapping for each. It covers only the jobs and exact arguments you were shown, for at most an hour, once each, never anything " +
                    "that needs root or a shell, and you can switch it off at any moment (that ends every approval at once). Today's only job type never " +
                    "asks for a confirmation, so this has no effect yet.",
            )
            if (s.liveBatches > 0) Note("${s.liveBatches} batch approval(s) active right now.")
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
