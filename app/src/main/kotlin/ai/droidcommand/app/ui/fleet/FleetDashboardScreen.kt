package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.theme.AccentBlueBright
import ai.droidcommand.app.ui.theme.AccentRed
import ai.droidcommand.app.ui.theme.AccentSuccess
import ai.droidcommand.app.ui.theme.BorderDark
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.remotecontrol.FleetDashboardRow
import ai.droidcommand.remotecontrol.FleetDashboardTone
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** Amber for a snapshot that says the node is struggling. Not a theme-wide token. */
private val DashboardAmber = Color(0xFFFFB020)

/** Bound companions: tap opens the orchestrator bind path, not a dead dialog. */
private val COMPANION_ORCHESTRATOR_IDS = setOf("hackerai", "pentestswarm")

/**
 * Fleet home. One row per catalogued member. Refresh pulls live status and
 * capabilities for TRUSTED paired EXTERNAL_NODEs (off the main thread) and
 * publishes them into the status tray.
 */
@Composable
fun FleetDashboardScreen(
    onOpenPairing: () -> Unit,
    onOpenOrchestrator: () -> Unit,
    viewModel: FleetDashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var sheetFor by remember { mutableStateOf<FleetDashboardRow?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("fleet_dashboard"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Fleet")
        Text(
            state.liveBindNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.emptyStateCta?.let { cta ->
            DroidCard(
                modifier = Modifier
                    .clickable(onClick = onOpenPairing)
                    .testTag("fleet_dashboard_empty_cta"),
                borderColor = AccentBlueBright.copy(alpha = 0.55f),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Pair a node to get live status", style = MaterialTheme.typography.titleMedium)
                    Text(cta, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("Tap to open pairing →", style = MaterialTheme.typography.labelMedium, color = AccentBlueBright)
                }
            }
        }
        OutlinedButton(
            onClick = viewModel::refresh,
            modifier = Modifier.fillMaxWidth().testTag("fleet_dashboard_refresh"),
        ) { Text("Refresh list") }
        OutlinedButton(
            onClick = onOpenPairing,
            modifier = Modifier.fillMaxWidth().testTag("fleet_dashboard_pairing"),
        ) { Text("Pair and manage nodes") }
        OutlinedButton(
            onClick = onOpenOrchestrator,
            modifier = Modifier.fillMaxWidth().testTag("fleet_dashboard_orchestrator"),
        ) { Text("Open fleet commands") }

        state.rows.forEach { row ->
            FleetDashboardRowCard(
                row = row,
                onClick = {
                    when {
                        row.member.opensPairing -> onOpenPairing()
                        row.member.id in COMPANION_ORCHESTRATOR_IDS -> onOpenOrchestrator()
                        else -> sheetFor = row
                    }
                },
            )
        }
    }

    sheetFor?.let { row ->
        AlertDialog(
            onDismissRequest = { sheetFor = null },
            title = { Text(row.member.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.member.role)
                    Text("${row.statusLabel} · ${row.seenLabel}")
                    if (row.reason.isNotBlank()) {
                        Text(row.reason, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    Text(
                        when {
                            row.member.opensPairing ->
                                "Open pairing to connect or re-check this node."
                            row.member.id in COMPANION_ORCHESTRATOR_IDS ->
                                "Open fleet commands to bind this companion. Debug builds need the same signing key."
                            else ->
                                "This node has no pairing screen of its own. Use Refresh list after installing and launching it."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { sheetFor = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun FleetDashboardRowCard(row: FleetDashboardRow, onClick: () -> Unit) {
    DroidCard(
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag("fleet_dashboard_row_${row.member.id}"),
        borderColor = if (row.member.highlighted) AccentBlueBright.copy(alpha = 0.55f) else BorderDark,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(toneColor(row.tone))
                        .testTag("fleet_dashboard_dot_${row.member.id}"),
                )
                Spacer(Modifier.width(8.dp))
                Text(row.member.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (row.member.highlighted) {
                    Text("First target", style = MaterialTheme.typography.labelSmall, color = AccentBlueBright)
                }
            }
            Text(row.member.role, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(row.statusLabel, style = MaterialTheme.typography.bodySmall, color = toneColor(row.tone))
            if (row.reason.isNotBlank()) {
                Text(
                    row.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.testTag("fleet_dashboard_reason_${row.member.id}"),
                )
            }
            Text(row.seenLabel, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

@Composable
private fun toneColor(tone: FleetDashboardTone): Color = when (tone) {
    FleetDashboardTone.OK -> AccentSuccess
    FleetDashboardTone.DEGRADED -> DashboardAmber
    FleetDashboardTone.DOWN -> AccentRed
    FleetDashboardTone.UNKNOWN -> TextSecondary
}
