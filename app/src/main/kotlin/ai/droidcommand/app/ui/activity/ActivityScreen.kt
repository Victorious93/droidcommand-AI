package ai.droidcommand.app.ui.activity

import ai.droidcommand.activity.ActivityEvent
import ai.droidcommand.activity.ActivityKind
import ai.droidcommand.activity.ActivityStatus
import ai.droidcommand.activity.ActorActivity
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.DroidEmptyState
import ai.droidcommand.app.ui.components.DroidNote
import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.components.StatusPill
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the helper bots and fleet apps are doing right now. Rows that need the owner come first; tapping a row
 * opens what it did, step by step. Read-only: approvals still happen in the approval dialog or the app's own
 * Allow screen, and this screen never answers one.
 */
@Composable
fun ActivityScreen(viewModel: ActivityViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("activity_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Activity")
        Text(
            "What DroidCommand, your helper bots and your fleet apps are doing on this phone. It only shows what ran through this app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DroidNote("This list is kept in memory. It is empty after the app restarts.")

        if (state.rows.isEmpty()) {
            DroidEmptyState(
                icon = Icons.Filled.Timeline,
                title = "Nothing has run yet",
                body = "Run a Pilot instruction, a Plan or a helper bot, or ask this phone to do something from a fleet app. It shows up here while it works.",
            )
        }

        if (state.needsYou.isNotEmpty()) {
            SectionLabel("Needs you")
            state.needsYou.forEach { row -> ActivityRow(row, state, viewModel::select) }
        }
        ActivityGroup.entries.forEach { group ->
            val rows = state.inGroup(group)
            if (rows.isNotEmpty()) {
                SectionLabel(group.title)
                rows.forEach { row -> ActivityRow(row, state, viewModel::select) }
            }
        }
    }
}

@Composable
private fun ActivityRow(row: ActorActivity, state: ActivityUiState, onSelect: (String) -> Unit) {
    val open = state.selectedActorId == row.actor.id
    DroidCard(
        onClick = { onSelect(row.actor.id) },
        modifier = Modifier.testTag("activity_row_${row.actor.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.actor.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StatusPill(row.status.label, toneOf(row.status))
        }
        val line = when {
            row.pendingApproval != null -> "Asking: ${row.pendingApproval}"
            row.currentStep != null -> "Now: ${row.currentStep}"
            else -> row.lastSummary
        }
        Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Text(
            metaLine(row, state.nowMillis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (open) {
            Column(modifier = Modifier.padding(top = 12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Steps, newest first")
                state.timeline.forEach { TimelineLine(it) }
            }
        }
    }
}

@Composable
private fun TimelineLine(event: ActivityEvent) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(clock(event.atMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${kindWord(event.kind)}  ${event.summary}", style = MaterialTheme.typography.bodySmall)
        }
        event.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 56.dp),
            )
        }
    }
}

private fun toneOf(status: ActivityStatus): PillTone = when (status) {
    ActivityStatus.WAITING_FOR_YOU, ActivityStatus.STALLED -> PillTone.Warning
    ActivityStatus.FAILED -> PillTone.Error
    ActivityStatus.DONE -> PillTone.Success
    ActivityStatus.WORKING, ActivityStatus.STOPPED -> PillTone.Neutral
}

private fun kindWord(kind: ActivityKind): String = when (kind) {
    ActivityKind.RUN_STARTED -> "Started"
    ActivityKind.TOOL_STARTED -> "Step"
    ActivityKind.TOOL_FINISHED -> "Step done"
    ActivityKind.TOOL_FAILED -> "Step failed"
    ActivityKind.NEEDS_APPROVAL -> "Asked you"
    ActivityKind.APPROVAL_GRANTED -> "You allowed"
    ActivityKind.APPROVAL_DENIED -> "You declined"
    ActivityKind.NOTE -> "Note"
    ActivityKind.RUN_FINISHED -> "Done"
    ActivityKind.RUN_FAILED -> "Failed"
    ActivityKind.RUN_CANCELLED -> "Stopped"
}

private fun metaLine(row: ActorActivity, now: Long): String {
    val steps = when (row.steps) {
        0 -> "no steps yet"
        1 -> "1 step"
        else -> "${row.steps} steps"
    }
    val failed = if (row.failedSteps > 0) " · ${row.failedSteps} failed" else ""
    return "$steps$failed · ${ago(now - row.lastEventAtMillis)}"
}

internal fun ago(millis: Long): String {
    val s = millis.coerceAtLeast(0) / 1000
    return when {
        s < 10 -> "just now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        else -> "${s / 86_400}d ago"
    }
}

private fun clock(millis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
