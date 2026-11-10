package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.fleet.FleetInstallState
import ai.droidcommand.app.fleet.FleetRow
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.AccentRed
import ai.droidcommand.app.ui.theme.AccentSuccess
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import ai.droidcommand.app.ux.FleetAppHelp
import ai.droidcommand.app.ux.FriendlyErrors
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.fleet.messenger.ExternalNodeRow
import ai.droidcommand.fleet.messenger.LiveCallResult
import ai.droidcommand.fleet.messenger.NodePairingState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Fleet orchestrator: HackerAI plus the nine KnownFleet members. Companions stay
 * install-probe only; EXTERNAL_NODE members (RootForge, File Manager, Keyboard, Mesh,
 * Browser) can be paired (package + observed signing-cert SHA-256, explicit confirm)
 * and then run read-only Messenger status/capabilities. `*.up` is fire-and-forget
 * after a second confirm, then this screen polls `*.status`. Nothing connects until
 * the owner pairs a node. Compiles; never run on a device.
 */
@Composable
fun FleetScreen(viewModel: FleetViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var pending by remember { mutableStateOf<PendingAction?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitleWithHelp("Pair an app", ScreenHelpCatalog.PAIRING)
        Text(
            "Pairing means you trust the install that is on this phone. If that app is later replaced, the connection stops until you pair again. " +
                "This does not turn on remote control. Security tools still ask before they run.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Start with RootForge if you have it: Pair, then Test connection on the Fleet tab.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        state.pairingMessage?.let { Text(FriendlyErrors.forUser(it), style = MaterialTheme.typography.bodySmall) }
        if (state.loading && state.externalNodes.isEmpty()) {
            CircularProgressIndicator()
        }

        SectionLabel("Apps you can pair")
        state.externalNodes.forEach { row ->
            ExternalNodeCard(
                row = row,
                live = state.liveById[row.spec.id],
                busy = row.spec.id in state.busyIds,
                onPair = { pending = PendingAction.Pair(row) },
                onPairByPassword = { pending = PendingAction.PairByPassword(row) },
                onUnpair = { pending = PendingAction.Unpair(row) },
                onCapabilities = { viewModel.refreshCapabilities(row.spec.id) },
                onStatus = { viewModel.refreshStatus(row.spec.id) },
                onBringUp = { pending = PendingAction.BringUp(row) },
            )
        }

        SectionLabel("Other apps")
        state.otherRows.forEach { row -> CompanionCard(row) }
    }

    when (val action = pending) {
        is PendingAction.Pair -> AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Pair ${action.row.spec.displayName}?") },
            text = {
                Text(
                    "This stores that you trust the ${action.row.spec.displayName} install on this phone " +
                        "(${action.row.installedPackage}). If that install is replaced later, the connection " +
                        "stops until you pair again. Pairing does not turn on remote control and does not run a command.",
                )
            },
            confirmButton = {
                DroidTextButton(onClick = {
                    pending = null
                    viewModel.pair(action.row.spec.id)
                }) { Text("Pair") }
            },
            dismissButton = { DroidTextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
        is PendingAction.PairByPassword -> {
            var password by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text("Link ${action.row.spec.displayName} by password?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "This stores package ${action.row.installedPackage} and the observed " +
                                "signing-cert SHA-256 ${action.row.observedCertDisplay} after the " +
                                "pairing password matches. Later reconnects refuse if that cert " +
                                "changes. External control stays OFF. Excluded apps cannot be linked.",
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            singleLine = true,
                            label = { Text("Pairing password") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false,
                            ),
                        )
                    }
                },
                confirmButton = {
                    DroidTextButton(
                        enabled = password.isNotBlank(),
                        onClick = {
                            val typed = password
                            pending = null
                            viewModel.pairByPassword(action.row.spec.id, typed)
                        },
                    ) { Text("Link") }
                },
                dismissButton = { DroidTextButton(onClick = { pending = null }) { Text("Cancel") } },
            )
        }
        is PendingAction.Unpair -> AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Unpair ${action.row.spec.displayName}?") },
            text = { Text("DroidCommand will stop talking to this app until you pair it again.") },
            confirmButton = {
                DroidTextButton(onClick = {
                    pending = null
                    viewModel.unpair(action.row.spec.id)
                }) { Text("Unpair") }
            },
            dismissButton = { DroidTextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
        is PendingAction.BringUp -> AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Send ${action.row.spec.upOperation}?") },
            text = {
                Text(
                    "This asks the other app to come online, then checks that it answered. " +
                        "It is not a security-tool run. Those still ask you separately.",
                )
            },
            confirmButton = {
                DroidTextButton(onClick = {
                    pending = null
                    viewModel.bringUpAndPoll(action.row.spec.id)
                }) { Text("Send") }
            },
            dismissButton = { DroidTextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

private sealed class PendingAction {
    data class Pair(val row: ExternalNodeRow) : PendingAction()
    data class PairByPassword(val row: ExternalNodeRow) : PendingAction()
    data class Unpair(val row: ExternalNodeRow) : PendingAction()
    data class BringUp(val row: ExternalNodeRow) : PendingAction()
}

@Composable
private fun ExternalNodeCard(
    row: ExternalNodeRow,
    live: LiveCallResult?,
    busy: Boolean,
    onPair: () -> Unit,
    onPairByPassword: () -> Unit,
    onUnpair: () -> Unit,
    onCapabilities: () -> Unit,
    onStatus: () -> Unit,
    onBringUp: () -> Unit,
) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.spec.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                row.installedPackage ?: row.spec.primaryPackage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stateLabel(row), style = MaterialTheme.typography.bodySmall, color = stateColor(row.state))
            if (row.pairedCertDisplay != null && row.observedCertDisplay != null && row.pairedCertDisplay != row.observedCertDisplay) {
                Text(
                    "This install does not match the one you already trusted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentRed,
                )
            }
            row.trustDetail?.let { Text(FriendlyErrors.forUser(it), style = MaterialTheme.typography.bodySmall, color = AccentRed) }
            Text(FleetAppHelp.forId(row.spec.id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (row.canPair) {
                    DroidPrimaryButton(onClick = onPair, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(if (row.canUnpair) "Re-pair" else "Pair")
                    }
                    DroidSecondaryButton(onClick = onPairByPassword, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Password")
                    }
                }
                if (row.canUnpair) {
                    DroidSecondaryButton(onClick = onUnpair, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Unpair") }
                }
            }
            if (row.liveEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    DroidSecondaryButton(onClick = onCapabilities, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("What can it do")
                    }
                    DroidSecondaryButton(onClick = onStatus, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Test connection")
                    }
                }
                if (row.spec.upOperation != null) {
                    DroidSecondaryButton(onClick = onBringUp, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Ask it to come online")
                    }
                }
            }
            if (busy) CircularProgressIndicator()
            live?.let {
                Text(FriendlyErrors.forUser(it.summary()), style = MaterialTheme.typography.bodySmall, color = liveColor(it))
            }
        }
    }
}

@Composable
private fun CompanionCard(row: FleetRow) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.member.name, style = MaterialTheme.typography.titleMedium)
            row.member.packageName?.let { pkg ->
                Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(installLabel(row.installed), style = MaterialTheme.typography.bodySmall)
            Text(FleetAppHelp.forId(row.member.id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(FriendlyErrors.forUser(row.status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun stateColor(state: NodePairingState) = when (state) {
    NodePairingState.TRUSTED -> AccentSuccess
    NodePairingState.CERT_MISMATCH, NodePairingState.UNTRUSTED -> AccentRed
    NodePairingState.NOT_PAIRED, NodePairingState.NOT_INSTALLED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun liveColor(result: LiveCallResult) = when (result) {
    is LiveCallResult.Success -> AccentSuccess
    is LiveCallResult.UpThenStatus ->
        if (result.up is LiveCallResult.Success && result.status is LiveCallResult.Success) {
            AccentSuccess
        } else {
            AccentRed
        }
    is LiveCallResult.Gated -> MaterialTheme.colorScheme.onSurfaceVariant
    is LiveCallResult.RemoteError,
    is LiveCallResult.Timeout,
    is LiveCallResult.Untrusted,
    is LiveCallResult.Failed,
    -> AccentRed
}

private fun stateLabel(row: ExternalNodeRow): String = when (row.state) {
    NodePairingState.NOT_INSTALLED -> "Not installed"
    NodePairingState.NOT_PAIRED -> "Installed, tap to pair"
    NodePairingState.TRUSTED -> "Connected"
    NodePairingState.CERT_MISMATCH -> "Blocked: this install does not match the trusted one"
    NodePairingState.UNTRUSTED -> "Blocked: pairing no longer matches this install"
}

private fun installLabel(state: FleetInstallState): String = when (state) {
    FleetInstallState.INSTALLED -> "Installed"
    FleetInstallState.NOT_INSTALLED -> "Not installed"
    FleetInstallState.NOT_APPLICABLE -> "Not an app on this phone"
}
