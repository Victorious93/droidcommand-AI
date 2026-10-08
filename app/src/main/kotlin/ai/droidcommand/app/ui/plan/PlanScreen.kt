package ai.droidcommand.app.ui.plan

import ai.droidcommand.agent.PlanRun
import ai.droidcommand.agent.PlanStatus
import ai.droidcommand.agent.PlanStep
import ai.droidcommand.agent.PlanStepStatus
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.AccentCyan
import ai.droidcommand.app.ui.theme.AccentNeonGreen
import ai.droidcommand.app.ui.theme.AccentPurple
import ai.droidcommand.app.ui.theme.AccentRed
import ai.droidcommand.app.ui.theme.AccentGreenContainer
import ai.droidcommand.app.ui.theme.BorderDark
import ai.droidcommand.app.ui.theme.TextPrimary
import ai.droidcommand.app.ui.theme.TextSecondary
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AMBER = Color(0xFFFFB300)
private val timeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

/**
 * Plan screen in OpenDroid's "Plan Engine" layout: a current-run card, the steps of that run, and a
 * history list. Here a plan is one agent objective run through the Forge loop; steps are the tool
 * calls the model chose, shown as they happen — there is no up-front step list to show because the
 * model decides one step at a time. Never built into an APK or run on a device.
 */
@Composable
fun PlanScreen(viewModel: PlanViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var objective by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }

    val latest = state.runs.firstOrNull()
    val shown = state.runs.firstOrNull { it.id == selectedId } ?: latest

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
    ) {
        item { ScreenTitle("Plan Engine") }

        item {
            DroidCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = objective,
                        onValueChange = { objective = it },
                        enabled = !state.running,
                        label = { Text("Objective") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                    )
                    Text(
                        "Runs with ${viewModel.targetLabel()}. Tools still ask for approval, and any tool with no backend configured is denied.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    if (state.running) {
                        Button(
                            onClick = viewModel::stop,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentRed, contentColor = TextPrimary),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Stop after current step") }
                    } else {
                        Button(
                            onClick = {
                                selectedId = null
                                viewModel.run(objective)
                                objective = ""
                            },
                            enabled = objective.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Run objective") }
                    }
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (shown == null) {
            item { EmptyPlanPlaceholder() }
        } else {
            item { RunHeaderCard(shown, isLatest = shown.id == latest?.id, onShowLatest = { selectedId = null }) }
            item { SectionLabel("Plan sequence stage", modifier = Modifier.padding(top = 4.dp)) }
            if (shown.steps.isEmpty()) {
                item {
                    Text(
                        if (shown.status == PlanStatus.RUNNING) "Waiting for the model's first step…" else "No tools were invoked.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(shown.steps, key = { "${shown.id}-${it.index}" }) { StepCard(it) }
            shown.summary?.let { summary ->
                item { SummaryCard(shown.status, summary) }
            }
        }

        if (state.runs.isNotEmpty()) {
            item { SectionLabel("Autonomous execution history", modifier = Modifier.padding(top = 8.dp)) }
            items(state.runs, key = { it.id }) { run ->
                HistoryRow(
                    run = run,
                    selected = run.id == shown?.id,
                    onSelect = { selectedId = run.id },
                    onDelete = { viewModel.delete(run.id) },
                )
            }
        }
    }
}

private fun statusColor(status: PlanStatus): Color = when (status) {
    PlanStatus.COMPLETED -> AccentNeonGreen
    PlanStatus.RUNNING -> AccentCyan
    PlanStatus.FAILED -> AccentRed
    PlanStatus.CANCELLED -> AMBER
}

@Composable
private fun EmptyPlanPlaceholder() {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("No plan yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Describe an objective above. The agent chooses tools one step at a time and each step is recorded here.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun RunHeaderCard(run: PlanRun, isLatest: Boolean, onShowLatest: () -> Unit) {
    val color = statusColor(run.status)
    DroidCard(borderColor = if (isLatest) color.copy(alpha = 0.5f) else BorderDark) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(run.status.name, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (isLatest) {
                Badge(if (run.status == PlanStatus.RUNNING) "ACTIVE RUN" else "LATEST RUN", AccentNeonGreen, null)
            } else {
                Badge("VIEWING PAST RUN", AccentPurple, onShowLatest)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(run.objective, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = BorderDark)
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Steps", fontSize = 10.sp, color = TextSecondary)
                Text("${run.steps.size} so far", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Started", fontSize = 10.sp, color = TextSecondary)
                Text(timeFormat.format(run.startedAt), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color, onClick: (() -> Unit)?) {
    Text(
        text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.2f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun StepCard(step: PlanStep) {
    val color = when (step.status) {
        PlanStepStatus.RUNNING -> AccentCyan
        PlanStepStatus.SUCCEEDED -> AccentNeonGreen
        PlanStepStatus.PARTIAL -> AMBER
        PlanStepStatus.FAILED -> AccentRed
    }
    DroidCard(borderColor = color.copy(alpha = 0.5f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape).background(AccentGreenContainer),
                contentAlignment = Alignment.Center,
            ) { Text("${step.index}", color = AccentNeonGreen, fontWeight = FontWeight.Bold) }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Execute ${step.toolName}", fontWeight = FontWeight.Bold, color = TextPrimary)
                if (step.input.isNotEmpty()) {
                    Text(
                        step.input.entries.joinToString { "${it.key}=${it.value}" },
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                step.output?.let {
                    Text(it, color = TextPrimary, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            when (step.status) {
                PlanStepStatus.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = color)
                PlanStepStatus.SUCCEEDED -> Icon(Icons.Filled.CheckCircle, contentDescription = "Succeeded", tint = color)
                PlanStepStatus.PARTIAL -> Icon(Icons.Filled.Warning, contentDescription = "Partly done", tint = color)
                PlanStepStatus.FAILED -> Icon(Icons.Filled.Close, contentDescription = "Failed", tint = color)
            }
        }
    }
}

@Composable
private fun SummaryCard(status: PlanStatus, summary: String) {
    DroidCard(borderColor = statusColor(status).copy(alpha = 0.5f)) {
        Text(
            when (status) {
                PlanStatus.COMPLETED -> "RESULT"
                PlanStatus.CANCELLED -> "CANCELLED"
                else -> "FAILED"
            },
            color = statusColor(status),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(summary, color = TextPrimary)
    }
}

@Composable
private fun HistoryRow(run: PlanRun, selected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    DroidCard(modifier = Modifier.clickable(onClick = onSelect), borderColor = if (selected) AccentNeonGreen.copy(alpha = 0.5f) else BorderDark) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(run.objective, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(timeFormat.format(run.startedAt), color = TextSecondary, fontSize = 12.sp)
                    Text("${run.steps.size} steps", color = AccentCyan, fontSize = 12.sp)
                    Text(run.status.name, color = statusColor(run.status), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (run.status != PlanStatus.RUNNING) {
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete run", tint = TextSecondary) }
            }
        }
    }
}
