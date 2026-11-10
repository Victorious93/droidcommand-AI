package ai.droidcommand.app.ui.status

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.settings.RemoteControlViewModel
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Plain-English status tray and local backup. Same data as Advanced → Remote Control,
 * brought forward so it is easier to find.
 */
@Composable
fun StatusBackupScreen(viewModel: RemoteControlViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsState()
    val now = System.currentTimeMillis()
    val rows = StatusTraySummary.rows(ui.statusTray, now)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("status_backup_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Status and backup")
        Text(
            "Shows the latest health reports from your fleet apps on this phone. " +
                "Rows are kept across app restarts. Remote control stays off until you turn it on under Advanced.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionLabel("Fleet status")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(StatusTraySummary.overall(ui.statusTray, now), style = MaterialTheme.typography.titleMedium)
                DroidSecondaryButton(onClick = { viewModel.refresh() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Refresh status")
                }
                if (rows.isEmpty()) {
                    Text(
                        "No status reports yet. Pair a fleet app and open its status, or refresh after one checks in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    rows.forEach { row ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(row.node, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${row.headline} · ${row.freshness}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            row.details.forEach { detail ->
                                Text(detail, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        SectionLabel("Local backup")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Export or restore this phone's saved macros, pairings, and status rows. Nothing is uploaded. API keys are not included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DroidPrimaryButton(onClick = { viewModel.exportBackup() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Export local backup")
                }
                DroidSecondaryButton(onClick = { viewModel.importBackup() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Restore local backup")
                }
                ui.lastPreview?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
