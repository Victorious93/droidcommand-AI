package ai.droidcommand.app.ui.settings

import ai.droidcommand.app.ui.components.DroidGroup
import ai.droidcommand.app.ui.components.DroidListRow
import ai.droidcommand.app.ui.components.DroidRowDivider
import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.components.StatusPillWithChevron
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.fleet.messenger.ConnectedAppStatus
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * One row per fleet app, each in one honest state (Not installed / Installed, not paired / Paired / Active /
 * Needs attention). Tapping a row opens the Fleet tab, where pairing happens. Nothing here turns anything on.
 */
@Composable
fun ConnectedAppsSection(onOpenFleet: () -> Unit, viewModel: ConnectedAppsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsState()
    SectionLabel("Connected apps")
    DroidGroup(modifier = Modifier.testTag("advanced_connected_apps")) {
        ui.rows.forEachIndexed { index, row ->
            if (index > 0) DroidRowDivider()
            DroidListRow(
                title = row.purpose.title,
                subtitle = row.purpose.whatItDoes + " " + row.detail,
                onClick = onOpenFleet,
                modifier = Modifier.testTag("connected_app_${row.purpose.catalogId}"),
                trailing = { StatusPillWithChevron(row.status.label, toneOf(row.status)) },
            )
        }
    }
    DroidSecondaryButton(
        onClick = viewModel::checkPaired,
        enabled = !ui.checking,
        modifier = Modifier.testTag("connected_apps_check"),
    ) { Text(if (ui.checking) "Checking…" else "Check paired apps") }
}

private fun toneOf(status: ConnectedAppStatus): PillTone = when (status) {
    ConnectedAppStatus.ACTIVE -> PillTone.Success
    ConnectedAppStatus.NEEDS_ATTENTION -> PillTone.Warning
    ConnectedAppStatus.PAIRED, ConnectedAppStatus.INSTALLED, ConnectedAppStatus.NOT_PAIRED, ConnectedAppStatus.NOT_INSTALLED -> PillTone.Neutral
}
