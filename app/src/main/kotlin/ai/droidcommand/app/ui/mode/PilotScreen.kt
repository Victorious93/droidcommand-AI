package ai.droidcommand.app.ui.mode

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.app.mode.ToolBackendNotes
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.rememberDraft
import ai.droidcommand.app.ux.ScreenHelpCatalog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Pilot engine: one tool, invoked through [ai.droidcommand.agent.DroidCommandSession.runPilotInstruction].
 * The screen title is the user's label for this mode (default "Mode 1").
 */
@Composable
fun PilotScreen(viewModel: PilotViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val mode by viewModel.modeState.collectAsState()
    var toolName by rememberSaveable { mutableStateOf(state.tools.firstOrNull()?.name.orEmpty()) }
    var args by rememberDraft()
    var menuOpen by remember { mutableStateOf(false) }
    val selected = state.tools.firstOrNull { it.name == toolName }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitleWithHelp(mode.label(AgentMode.PILOT), ScreenHelpCatalog.PILOT, uppercase = false)
        Text(
            "Runs one approved action, then stops. The title is the name from Settings. Anything that can change the phone still asks first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.tools.isEmpty()) {
            DroidCard {
                Text("No tools are registered for Pilot.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            DroidCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(onClick = { menuOpen = true }, enabled = !state.running, modifier = Modifier.fillMaxWidth()) {
                            Text(selected?.name ?: "Choose a tool")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            state.tools.forEach { tool ->
                                DropdownMenuItem(
                                    text = { Text(tool.name) },
                                    onClick = {
                                        toolName = tool.name
                                        menuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    selected?.let {
                        Text(it.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ToolBackendNotes.forName(it.name)?.let { note ->
                            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    OutlinedTextField(
                        value = args,
                        onValueChange = { args = it },
                        enabled = !state.running,
                        label = { Text("Arguments (key=value, one per line)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8,
                    )
                    Button(
                        onClick = { viewModel.run(toolName, args) },
                        enabled = !state.running && toolName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.running) "Running…" else "Run tool") }
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    state.result?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
