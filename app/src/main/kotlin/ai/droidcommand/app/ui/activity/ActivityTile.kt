package ai.droidcommand.app.ui.activity

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.app.ui.components.StatusPill
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** One line on the Fleet home screen: what is running, and a pill when something is waiting for the owner. */
@Composable
fun ActivityTile(onOpen: () -> Unit, viewModel: ActivityViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val waiting = state.needsYou.size
    val summary = when {
        state.rows.isEmpty() -> "Nothing has run since the app started."
        state.working > 0 -> "${state.working} working" + if (waiting > 0) " · $waiting need you" else ""
        waiting > 0 -> "$waiting need you"
        else -> "${state.rows.size} finished or idle"
    }
    DroidCard(onClick = onOpen, modifier = Modifier.testTag("fleet_activity_tile")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Activity", style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (waiting > 0) StatusPill("Needs you", PillTone.Warning)
        }
    }
}
