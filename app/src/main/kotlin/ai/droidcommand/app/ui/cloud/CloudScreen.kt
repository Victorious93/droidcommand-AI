package ai.droidcommand.app.ui.cloud

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.theme.AccentCyan
import ai.droidcommand.app.ui.theme.BorderDark
import ai.droidcommand.app.ui.theme.TextPrimary
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.cloudsession.CloudCodingSession
import ai.droidcommand.cloudsession.CloudSessionPhase
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Cloud coding tunnel. One session walks plan → edit → report. The status card is the transport's
 * own status; with the stub transport it stays PLANNED and says no remote worker ran.
 */
@Composable
fun CloudScreen(viewModel: CloudViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var goal by remember { mutableStateOf("") }
    val selected = state.sessions.firstOrNull { it.id == state.selectedId } ?: state.sessions.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("Cloud") }
        item { StatusCard(state.backendStatus) }
        item {
            DroidCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        label = { Text("Goal for this session") },
                    )
                    Button(
                        onClick = {
                            viewModel.open(goal)
                            goal = ""
                        },
                        enabled = goal.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open session") }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (selected != null) {
            item {
                SessionDetail(
                    selected,
                    onAdvance = { viewModel.advance(selected.id) },
                    onCancel = { viewModel.cancel(selected.id) },
                )
            }
        }
        if (state.sessions.isNotEmpty()) {
            item { Text("SESSIONS", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
            items(state.sessions, key = { it.id }) { session ->
                SessionRow(session, selected = session.id == selected?.id, onClick = { viewModel.select(session.id) })
            }
        }
    }
}

@Composable
private fun StatusCard(status: String) {
    DroidCard(borderColor = AccentCyan.copy(alpha = 0.5f)) {
        Text("BACKEND", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(status, color = TextPrimary)
    }
}

@Composable
private fun SessionDetail(session: CloudCodingSession, onAdvance: () -> Unit, onCancel: () -> Unit) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(session.goal, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(session.phase.name, color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            session.events.forEach { event ->
                Text(event.title.uppercase(), color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(event.body, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
            }
            if (!session.terminal) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAdvance, modifier = Modifier.weight(1f)) { Text(advanceLabel(session.phase)) }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: CloudCodingSession, selected: Boolean, onClick: () -> Unit) {
    DroidCard(
        modifier = Modifier.clickable(onClick = onClick),
        borderColor = if (selected) AccentCyan.copy(alpha = 0.6f) else BorderDark,
    ) {
        Text(session.goal, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(session.phase.name, color = TextSecondary, fontSize = 12.sp)
    }
}

private fun advanceLabel(phase: CloudSessionPhase): String = when (phase) {
    CloudSessionPhase.PLAN -> "Run edit step"
    CloudSessionPhase.EDIT -> "Write report"
    CloudSessionPhase.REPORT -> "Close session"
    else -> "Advance"
}
