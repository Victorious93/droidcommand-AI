package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.fleet.FleetInstallState
import ai.droidcommand.app.fleet.FleetRow
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.AccentRed
import ai.droidcommand.app.ui.theme.AccentSuccess
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
        ScreenTitle("Fleet")
        Text(
            "Companions are separate APKs (AIDL + DependencyGuard). RootForge, File Manager, Keyboard, Mesh, and Browser " +
                "are external nodes (Messenger + shared JSON) — never DependencyGuard. Pairing is an explicit " +
                "trust decision: eyeball the observed cert, then confirm. Nothing is LIVE; Kali/pentest " +
                "approval gates are unchanged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "P1 target: RootForge — Pair → Capabilities",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        state.pairingMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.loading && state.externalNodes.isEmpty()) {
            CircularProgressIndicator()
        }

        SectionLabel("External nodes")
        state.externalNodes.forEach { row ->
            ExternalNodeCard(
                row = row,
                live = state.liveById[row.spec.id],
                busy = row.spec.id in state.busyIds,
                onPair = { pending = PendingAction.Pair(row) },
                onUnpair = { pending = PendingAction.Unpair(row) },
                onCapabilities = { viewModel.refreshCapabilities(row.spec.id) },
                onStatus = { viewModel.refreshStatus(row.spec.id) },
                onBringUp = { pending = PendingAction.BringUp(row) },
            )
        }

        SectionLabel("Companions and other")
        state.otherRows.forEach { row -> CompanionCard(row) }
    }

    when (val action = pending) {
        is PendingAction.Pair -> AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Pair ${action.row.spec.displayName}?") },
            text = {
                Text(
                    "This stores package ${action.row.installedPackage} and the observed signing-cert SHA-256 " +
                        "${action.row.observedCertDisplay}. Later calls refuse if that cert changes — " +
                        "re-signing is not auto-trusted. Pairing does not grant Kali, pentest, or companion AIDL.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    viewModel.pair(action.row.spec.id)
                }) { Text("Pair") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
        is PendingAction.Unpair -> AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Unpair ${action.row.spec.displayName}?") },
            text = { Text("Messenger will stop talking to this node until you pair it again.") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    viewModel.unpair(action.row.spec.id)
                }) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
        is PendingAction.BringUp -> AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Send ${action.row.spec.upOperation}?") },
            text = {
                Text(
                    "This is fire-and-forget (${action.row.spec.upOperation}), then this screen polls " +
                        "${action.row.spec.statusOperation}. Empty parameters {}. It is not a Kali/pentest " +
                        "or DependencyGuard path — those gates stay in place for companions and security tools.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    viewModel.bringUpAndPoll(action.row.spec.id)
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

private sealed class PendingAction {
    data class Pair(val row: ExternalNodeRow) : PendingAction()
    data class Unpair(val row: ExternalNodeRow) : PendingAction()
    data class BringUp(val row: ExternalNodeRow) : PendingAction()
}

@Composable
private fun ExternalNodeCard(
    row: ExternalNodeRow,
    live: LiveCallResult?,
    busy: Boolean,
    onPair: () -> Unit,
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
            row.observedCertDisplay?.let { cert ->
                Text("Observed cert SHA-256", style = MaterialTheme.typography.labelSmall)
                Text(cert, style = MaterialTheme.typography.bodySmall)
            }
            row.pairedCertDisplay?.let { cert ->
                if (cert != row.observedCertDisplay) {
                    Text("Paired cert SHA-256", style = MaterialTheme.typography.labelSmall)
                    Text(cert, style = MaterialTheme.typography.bodySmall, color = AccentRed)
                }
            }
            row.trustDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AccentRed) }
            Text(row.spec.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (row.canPair) {
                    Button(onClick = onPair, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(if (row.canUnpair) "Re-pair" else "Pair")
                    }
                }
                if (row.canUnpair) {
                    OutlinedButton(onClick = onUnpair, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Unpair") }
                }
            }
            if (row.liveEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onCapabilities, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Capabilities")
                    }
                    OutlinedButton(onClick = onStatus, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Status")
                    }
                }
                if (row.spec.upOperation != null) {
                    OutlinedButton(onClick = onBringUp, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Bring up (then poll status)")
                    }
                }
            }
            if (busy) CircularProgressIndicator()
            live?.let {
                Text(it.summary(), style = MaterialTheme.typography.bodySmall, color = liveColor(it))
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
            Text(row.member.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(row.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
    NodePairingState.NOT_INSTALLED -> "Package: not installed. Not paired."
    NodePairingState.NOT_PAIRED -> "Package: installed. Not paired — confirm the cert to pair."
    NodePairingState.TRUSTED -> "Paired and trusted. Read-only Messenger ops are available."
    NodePairingState.CERT_MISMATCH -> "Cert mismatch — installed signer is not the paired certificate."
    NodePairingState.UNTRUSTED -> "Untrusted — pairing no longer matches the installed package."
}

private fun installLabel(state: FleetInstallState): String = when (state) {
    FleetInstallState.INSTALLED -> "Package: installed"
    FleetInstallState.NOT_INSTALLED -> "Package: not installed"
    FleetInstallState.NOT_APPLICABLE -> "Package: not an APK"
}
