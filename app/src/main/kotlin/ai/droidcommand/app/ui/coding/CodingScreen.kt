package ai.droidcommand.app.ui.coding

import ai.droidcommand.app.coding.CodingController
import ai.droidcommand.app.coding.CodingUiState
import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.app.prefs.CodingTunnelChoice
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.DroidEmptyState
import ai.droidcommand.app.ui.components.DroidNote
import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.components.StatusPill
import ai.droidcommand.app.ui.components.pillColors
import ai.droidcommand.app.ui.components.rememberDraft
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import ai.droidcommand.coding.CodingPhase
import ai.droidcommand.coding.CodingSession
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
 * Plan, then edit, then report, for one session. The running backend is the on-device stand-in: it records canned text
 * for each step and does nothing else. Choosing "Paired" in Settings does not open a socket; that host is not connected.
 * The screen therefore says "preview" first and shows the three steps, so what is real and what is not is never in doubt.
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScreenTitle("Coding session", modifier = Modifier.weight(1f))
                StatusPill("Preview", PillTone.Warning)
            }
        }
        item {
            DroidNote(
                if (paired) {
                    "Paired tunnel is selected and not connected, so no session will start. A live Cursor or Codex backend is not built."
                } else {
                    "This is a preview of the flow. It does not write code: it records your goal and walks a fixed plan, edit and report. " +
                        "No cloud model is called and no repository is touched."
                },
                tone = PillTone.Warning,
            )
        }
        item {
            DroidCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it },
                        enabled = !paired,
                        label = { Text("What should the session work on?") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                    )
                    DroidPrimaryButton(
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
        if (state.sessions.isEmpty()) {
            item {
                DroidEmptyState(
                    icon = Icons.Filled.Code,
                    title = "No sessions yet",
                    body = "Write a goal above and start one to see how plan, edit and report follow each other.",
                )
            }
        } else {
            item { SectionLabel("Sessions") }
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
    var showEarlier by rememberSaveable(session.id) { mutableStateOf(false) }
    val (phaseText, phaseTone) = CodingPresentation.phaseLabel(session.phase)
    val (latestTitle, latestBody) = CodingPresentation.latestTitleAndBody(session)
    val earlier = CodingPresentation.earlier(session)
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(session.goal, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusPill(phaseText, phaseTone)
            }
            PhaseStepper(CodingPresentation.steps(session))
            PhaseBlock(latestTitle, latestBody)
            if (earlier.isNotEmpty()) {
                DroidTextButton(onClick = { showEarlier = !showEarlier }) { Text(if (showEarlier) "Hide earlier steps" else "Show earlier steps") }
                if (showEarlier) earlier.forEach { (title, body) -> PhaseBlock(title, body) }
            }
            val continueLabel = CodingPresentation.continueLabel(session.phase)
            if (open && !paired && continueLabel != null) {
                DroidPrimaryButton(onClick = onAdvance, modifier = Modifier.fillMaxWidth()) { Text(continueLabel) }
                DroidTextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel this session") }
            }
        }
    }
}

/** Plan, Edit, Report as three dots joined by a line: a tick when done, filled when current, an x when stopped. */
@Composable
private fun PhaseStepper(steps: List<CodingStep>) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { index, step ->
            val tone = when (step.mark) {
                StepMark.DONE -> PillTone.Success
                StepMark.STOPPED -> PillTone.Warning
                StepMark.CURRENT -> PillTone.Neutral
                StepMark.TODO -> PillTone.Neutral
            }
            val accent = if (step.mark == StepMark.CURRENT) MaterialTheme.colorScheme.primary else pillColors(tone).second
            val filled = step.mark == StepMark.DONE || step.mark == StepMark.STOPPED || step.mark == StepMark.CURRENT
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(if (filled) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    when (step.mark) {
                        StepMark.DONE -> Icon(Icons.Filled.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(14.dp))
                        StepMark.STOPPED -> Icon(Icons.Filled.Close, contentDescription = "Stopped", tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(14.dp))
                        StepMark.CURRENT, StepMark.TODO -> Unit
                    }
                }
                Text(
                    step.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (step.mark == StepMark.CURRENT) FontWeight.Bold else FontWeight.Normal,
                    color = if (step.mark == StepMark.TODO) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (index < steps.lastIndex) {
                Box(modifier = Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)))
            }
        }
    }
}

@Composable
private fun PhaseBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel(title)
        Text(body, style = MaterialTheme.typography.bodySmall)
    }
}
