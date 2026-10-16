package ai.droidcommand.app.ui.coding

import ai.droidcommand.app.coding.CodingController
import ai.droidcommand.app.coding.CodingUiState
import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.app.prefs.CodingTunnelChoice
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.rememberDraft
import ai.droidcommand.coding.CodingPhase
import ai.droidcommand.coding.CodingSession
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class CodingViewModel @Inject constructor(
    private val controller: CodingController,
    private val prefs: AdvancedPrefs,
) : ViewModel() {
    val state: StateFlow<CodingUiState> = controller.state

    fun usingPairedTunnel(): Boolean = prefs.codingTunnel() == CodingTunnelChoice.PAIRED

    fun start(goal: String) {
        if (usingPairedTunnel()) return
        controller.start(goal)
    }

    fun advance(id: String) {
        if (!usingPairedTunnel()) controller.advance(id)
    }

    fun cancel(id: String) {
        if (!usingPairedTunnel()) controller.cancel(id)
    }
}

/**
 * Plan, then edit, then report, for one session. The running backend is the on-device stand-in.
 * Choosing "Paired" in Settings does not open a socket; that host is not connected.
 */
@Composable
fun CodingScreen(viewModel: CodingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val paired = viewModel.usingPairedTunnel()
    var goal by rememberDraft()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
    ) {
        item {
            Text(
                "Cloud coding",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Text(
                if (paired) {
                    "Paired tunnel is selected and not connected. No session will start. A live Cursor or Codex backend is not built."
                } else {
                    "On-device stand-in. One session goes plan, then edit, then report. No repository is edited and no cloud service is called."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            DroidCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it },
                        enabled = !paired,
                        label = { Text("Goal") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                    )
                    Button(
                        onClick = {
                            viewModel.start(goal)
                            goal = ""
                        },
                        enabled = !paired && goal.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start session") }
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        items(state.sessions, key = { it.id }) { session ->
            SessionCard(
                session = session,
                paired = paired,
                onAdvance = { viewModel.advance(session.id) },
                onCancel = { viewModel.cancel(session.id) },
            )
        }
    }
}

@Composable
private fun SessionCard(session: CodingSession, paired: Boolean, onAdvance: () -> Unit, onCancel: () -> Unit) {
    val open = session.phase == CodingPhase.PLAN || session.phase == CodingPhase.EDIT
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(session.phase.name, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(session.goal, fontWeight = FontWeight.Bold)
            PhaseBlock("Plan", session.plan)
            if (session.edit.isNotEmpty()) PhaseBlock("Edit", session.edit)
            if (session.report.isNotEmpty()) PhaseBlock("Report", session.report)
            if (open && !paired) {
                Button(onClick = onAdvance, modifier = Modifier.fillMaxWidth()) {
                    Text(if (session.phase == CodingPhase.PLAN) "Continue to edit" else "Continue to report")
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun PhaseBlock(title: String, body: String) {
    Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Text(body, style = MaterialTheme.typography.bodySmall)
}
