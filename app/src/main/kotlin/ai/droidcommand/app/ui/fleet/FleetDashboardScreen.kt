package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.theme.AccentBlueBright
import ai.droidcommand.app.ui.theme.AccentRed
import ai.droidcommand.app.ui.theme.AccentSuccess
import ai.droidcommand.app.ui.theme.BorderDark
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.app.ux.FleetAppHelp
import ai.droidcommand.app.ux.FleetCardUi
import ai.droidcommand.app.ux.FleetUserCopy
import ai.droidcommand.app.ux.FleetUserKind
import ai.droidcommand.app.ux.FleetUserStatus
import ai.droidcommand.app.ux.ScreenHelpCatalog
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** Amber for a struggling but still connected app. Not a theme-wide token. */
private val DashboardAmber = Color(0xFFFFB020)

private sealed class FleetDialog {
    data class Pair(val card: FleetCardUi) : FleetDialog()
    data class Unpair(val card: FleetCardUi) : FleetDialog()
    data class Help(val card: FleetCardUi) : FleetDialog()
}

@Composable
fun FleetDashboardScreen(
    onOpenPairing: () -> Unit,
    onOpenOrchestrator: () -> Unit,
    viewModel: FleetDashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    FleetDashboardContent(
        state = state,
        onRefresh = viewModel::refresh,
        onPair = viewModel::pair,
        onUnpair = viewModel::unpair,
        onTest = viewModel::testConnection,
        onOpenPairing = onOpenPairing,
        onOpenOrchestrator = onOpenOrchestrator,
    )
}

@Composable
fun FleetDashboardContent(
    state: FleetHomeUi,
    onRefresh: () -> Unit,
    onPair: (String) -> Unit,
    onUnpair: (String) -> Unit,
    onTest: (String) -> Unit,
    onOpenPairing: () -> Unit,
    onOpenOrchestrator: () -> Unit,
) {
    var dialog by remember { mutableStateOf<FleetDialog?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("fleet_dashboard"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitleWithHelp("Fleet", ScreenHelpCatalog.FLEET)
        Text(
            state.banner,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.emptyHint?.let { hint ->
            DroidCard(
                modifier = Modifier.testTag("fleet_dashboard_empty_cta"),
                borderColor = AccentBlueBright.copy(alpha = 0.55f),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Nothing is connected yet", style = MaterialTheme.typography.titleMedium)
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }
        state.notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("fleet_dashboard_notice"))
        }
        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth().testTag("fleet_dashboard_refresh"),
        ) { Text("Refresh") }

        state.cards.forEach { card ->
            FleetAppCard(
                card = card,
                busy = card.id in state.busyIds,
                onPair = { dialog = FleetDialog.Pair(card) },
                onUnpair = { dialog = FleetDialog.Unpair(card) },
                onTest = { onTest(card.id) },
                onHelp = { dialog = FleetDialog.Help(card) },
                onApprovedCommands = onOpenOrchestrator,
            )
        }
    }

    when (val shown = dialog) {
        is FleetDialog.Pair -> PairStepsDialog(
            card = shown.card,
            onConfirm = {
                dialog = null
                if (shown.card.canPair) onPair(shown.card.id) else onOpenPairing()
            },
            onDismiss = { dialog = null },
        )
        is FleetDialog.Unpair -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(FleetUserCopy.unpairConfirmTitle(shown.card.name)) },
            text = { Text(FleetUserCopy.unpairConfirmBody(shown.card.name)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    onUnpair(shown.card.id)
                }) { Text("Stop pairing") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        is FleetDialog.Help -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(shown.card.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(shown.card.help)
                    shown.card.status.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    shown.card.status.fix?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
        )
        null -> Unit
    }
}

@Composable
private fun PairStepsDialog(
    card: FleetCardUi,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(FleetUserCopy.pairConfirmTitle(card.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(FleetUserCopy.pairConfirmBody(card.name))
                card.pairSteps.forEachIndexed { index, step ->
                    Text("${index + 1}. $step", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("fleet_pair_confirm"),
            ) { Text(if (card.canPair) "Pair" else "Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FleetAppCard(
    card: FleetCardUi,
    busy: Boolean,
    onPair: () -> Unit,
    onUnpair: () -> Unit,
    onTest: () -> Unit,
    onHelp: () -> Unit,
    onApprovedCommands: () -> Unit,
) {
    DroidCard(
        modifier = Modifier.testTag("fleet_dashboard_row_${card.id}"),
        borderColor = if (card.highlighted) AccentBlueBright.copy(alpha = 0.55f) else BorderDark,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(kindColor(card.status.kind))
                        .testTag("fleet_dashboard_dot_${card.id}"),
                )
                Spacer(Modifier.width(8.dp))
                Text(card.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (card.highlighted) {
                    Text("Start here", style = MaterialTheme.typography.labelSmall, color = AccentBlueBright)
                }
            }
            Text(card.role, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(
                card.status.label,
                style = MaterialTheme.typography.bodySmall,
                color = kindColor(card.status.kind),
                modifier = Modifier.testTag("fleet_dashboard_status_${card.id}"),
            )
            card.status.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            card.status.fix?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.testTag("fleet_dashboard_reason_${card.id}"),
                )
            }
            Text(card.help, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(card.seenLabel, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (card.canPair || card.status.kind == FleetUserKind.INSTALLED_TAP_TO_PAIR || card.status.kind == FleetUserKind.NOT_INSTALLED) {
                    Button(
                        onClick = onPair,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).testTag("fleet_pair_${card.id}"),
                    ) { Text(if (card.canUnpair) "Re-pair" else "Pair") }
                }
                if (card.canUnpair) {
                    OutlinedButton(
                        onClick = onUnpair,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop pairing") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).testTag("fleet_test_${card.id}"),
                ) { Text("Test connection") }
                TextButton(onClick = onHelp, modifier = Modifier.testTag("fleet_help_${card.id}")) {
                    Text("What is this?")
                }
            }
            if (card.opensApprovedCommands) {
                TextButton(onClick = onApprovedCommands) { Text("Approved commands") }
            }
        }
    }
}

@Composable
private fun kindColor(kind: FleetUserKind): Color = when (kind) {
    FleetUserKind.CONNECTED -> AccentSuccess
    FleetUserKind.INSTALLED_TAP_TO_PAIR -> DashboardAmber
    FleetUserKind.NOT_INSTALLED -> TextSecondary
    FleetUserKind.BLOCKED -> AccentRed
}

@Preview(showBackground = true, backgroundColor = 0xFF0B1220)
@Composable
private fun FleetEmptyPreview() {
    FleetDashboardContent(
        state = FleetHomeUi(
            cards = emptyList(),
            banner = "None of your other apps are connected yet. Install one, then tap Pair.",
            emptyHint = "None of your other apps are connected yet. Install one, then tap Pair.",
        ),
        onRefresh = {},
        onPair = {},
        onUnpair = {},
        onTest = {},
        onOpenPairing = {},
        onOpenOrchestrator = {},
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B1220)
@Composable
private fun FleetCardPreview() {
    FleetDashboardContent(
        state = FleetHomeUi(
            cards = listOf(
                FleetCardUi(
                    id = "rootforge",
                    name = "RootForge OS",
                    role = "Talks to a rooted phone and its gadgets",
                    help = FleetAppHelp.forId("rootforge"),
                    status = FleetUserStatus(
                        kind = FleetUserKind.INSTALLED_TAP_TO_PAIR,
                        label = "Installed, tap to pair",
                        detail = "Installed on this phone.",
                        fix = "Tap Pair and follow the steps.",
                    ),
                    seenLabel = "Not checked yet",
                    canPair = true,
                    canUnpair = false,
                    canTest = true,
                    highlighted = true,
                    pairSteps = FleetUserCopy.PAIR_STEPS,
                    opensApprovedCommands = false,
                ),
            ),
            banner = "These are your other Victorious apps. Pair one to use it from here.",
        ),
        onRefresh = {},
        onPair = {},
        onUnpair = {},
        onTest = {},
        onOpenPairing = {},
        onOpenOrchestrator = {},
    )
}
