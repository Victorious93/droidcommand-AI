package ai.droidcommand.app.ui.cloud

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.theme.AccentSky
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.cloudtunnel.CodingSession
import ai.droidcommand.cloudtunnel.TunnelParticipant
import ai.droidcommand.cloudtunnel.TunnelPhase
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 * One shared session: plan, then edit, then report. The banner states whether the turn stays on
 * the local stub or is posted to a configured HTTPS endpoint. Never run on a device.
 */
@Composable
fun CloudSessionScreen(viewModel: CloudSessionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var title by remember { mutableStateOf("") }
    var endpoint by remember(state.endpoint) { mutableStateOf(state.endpoint) }
    var instruction by remember { mutableStateOf("") }
    val selected = state.sessions.firstOrNull { it.id == state.selectedId }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScreenTitle("Cloud session")
                Text(state.statusNote, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text("Backend: ${state.backend.name}", color = AccentSky, style = MaterialTheme.typography.labelMedium)
            }
        }
        item {
            DroidCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text("HTTPS tunnel endpoint (empty uses the local stub)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Button(onClick = { viewModel.setEndpoint(endpoint) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Save endpoint")
                    }
                }
            }
        }
        item {
            DroidCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("New session title") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            viewModel.open(title)
                            title = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open shared session") }
                }
            }
        }
        items(state.sessions, key = { it.id }) { session ->
            FilterChip(
                selected = session.id == state.selectedId,
                onClick = { viewModel.select(session.id) },
                label = { Text("${session.title} · ${session.phase.name}") },
            )
        }
        if (selected != null) {
            item {
                SessionEditor(selected, instruction, state.busy, onInstruction = { instruction = it }, onSubmit = {
                    viewModel.submit(instruction)
                    instruction = ""
                }, onClose = viewModel::closeSelected)
            }
            items(selected.events, key = { it.id }) { event ->
                DroidCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(participantLabel(event.participant) + " · " + event.phase.name, color = AccentSky, style = MaterialTheme.typography.labelMedium)
                        Text(event.body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        state.error?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        item { Spacer(Modifier.padding(bottom = 12.dp)) }
    }
}

@Composable
private fun SessionEditor(
    session: CodingSession,
    instruction: String,
    busy: Boolean,
    onInstruction: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Phase ${session.phase.name}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Cursor cloud-agent and ChatGPT Codex share this session. Plan, then edit, then report.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = instruction,
                onValueChange = onInstruction,
                enabled = session.phase != TunnelPhase.CLOSED && !busy,
                label = { Text(promptFor(session.phase)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Button(
                onClick = onSubmit,
                enabled = instruction.isNotBlank() && session.phase != TunnelPhase.CLOSED && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Sending…" else "Send turn") }
            if (session.phase != TunnelPhase.CLOSED) {
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close session") }
            }
        }
    }
}

private fun promptFor(phase: TunnelPhase): String = when (phase) {
    TunnelPhase.PLAN -> "What should the plan cover?"
    TunnelPhase.EDIT -> "What should the edit pass do?"
    TunnelPhase.REPORT -> "What should the report add?"
    TunnelPhase.CLOSED -> "Session closed"
}

private fun participantLabel(participant: TunnelParticipant): String = when (participant) {
    TunnelParticipant.USER -> "You"
    TunnelParticipant.CURSOR_CLOUD_AGENT -> "Cursor cloud agent"
    TunnelParticipant.CHATGPT_CODEX -> "ChatGPT Codex"
    TunnelParticipant.REMOTE_UNVERIFIED -> "Remote"
}
