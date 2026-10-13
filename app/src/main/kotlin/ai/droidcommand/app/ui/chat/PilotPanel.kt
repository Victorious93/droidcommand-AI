package ai.droidcommand.app.ui.chat

import ai.droidcommand.app.pilot.PilotViewModel
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.theme.TextSecondary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Mode 1's Pilot control: pick one registered tool, give it `key=value` lines, and run it once.
 * The chat transcript above this panel is separate; this call is [ai.droidcommand.app.pilot.PilotController].
 */
@Composable
fun PilotPanel(viewModel: PilotViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var tool by remember(state.tools) { mutableStateOf(state.tools.firstOrNull().orEmpty()) }
    var input by remember { mutableStateOf("") }
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pilot — run one tool", style = MaterialTheme.typography.titleMedium)
            Text(
                "One direct tool call. Sensitive tools still ask for approval. Most backends on this device are not configured, so a denied result is expected until one is.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            if (state.tools.isEmpty()) {
                Text("No Pilot tools are registered.", color = TextSecondary)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.tools) { name ->
                        FilterChip(selected = name == tool, onClick = { tool = name }, label = { Text(name) })
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Input (key=value per line)") },
                    enabled = !state.running,
                )
                Button(
                    onClick = { viewModel.run(tool, input) },
                    enabled = !state.running && tool.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.running) "Running…" else "Run tool") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            state.lastResult?.let {
                Text("Last result (${state.lastTool})", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
