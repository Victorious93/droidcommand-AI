package ai.droidcommand.app.ui.logs

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.theme.AccentCyan
import ai.droidcommand.app.ui.theme.AccentNeonGreen
import ai.droidcommand.app.ui.theme.AccentRed
import ai.droidcommand.app.ui.theme.BackgroundDark
import ai.droidcommand.app.ui.theme.BorderDark
import ai.droidcommand.app.ui.theme.TextPrimary
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.AuditEventType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val logTime = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

private val problemTypes = setOf(
    AuditEventType.ACCESS_DENIED,
    AuditEventType.GRANT_DENIED,
    AuditEventType.INITIATOR_DENIED,
    AuditEventType.MODE_DENIED,
    AuditEventType.APPROVAL_DENIED,
    AuditEventType.APPROVAL_TIMED_OUT,
    AuditEventType.APPROVAL_UNAVAILABLE,
)

private val okTypes = setOf(
    AuditEventType.ACCESS_GRANTED,
    AuditEventType.APPROVAL_APPROVED,
    AuditEventType.GRANT_ISSUED,
    AuditEventType.GRANT_CONSUMED,
)

private fun typeColor(type: AuditEventType): Color = when (type) {
    in problemTypes -> AccentRed
    in okTypes -> AccentNeonGreen
    else -> AccentCyan
}

/**
 * System logs in OpenDroid's layout (title, two tabs, cards). The data is this app's audit trail of
 * security decisions on tool runs (every denial; approvals; authorizations of sensitive/root tools —
 * routine tool runs that are not security-sensitive are not logged), not an execution transcript. Read-only: the audit log is append-only
 * by design, so OpenDroid's clear-logs button is deliberately absent. Never built into an APK or run on
 * a device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: LogsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    // The audit file only changes when a tool runs elsewhere, so re-read it each time this tab opens.
    LaunchedEffect(Unit) { viewModel.refresh() }

    val shown = if (tab == 0) state.events else state.events.filter { it.type in problemTypes }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenTitle("System Logs")
            IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TextSecondary) }
        }

        SecondaryTabRow(
            selectedTabIndex = tab,
            containerColor = BackgroundDark,
            contentColor = AccentNeonGreen,
            divider = { HorizontalDivider(color = BorderDark) },
        ) {
            listOf("All events", "Denied & errors").forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = {
                        Text(
                            title,
                            fontSize = 13.sp,
                            fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (tab == index) AccentNeonGreen else TextSecondary,
                        )
                    },
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            when {
                state.error != null -> Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                state.loading && state.events.isEmpty() -> Text("Loading…", color = TextSecondary)
                shown.isEmpty() -> EmptyLogs(denied = tab == 1)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(shown) { LogCard(it) }
                }
            }
        }
    }
}

@Composable
private fun EmptyLogs(denied: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (denied) "Nothing denied" else "No events recorded yet", color = TextPrimary, fontWeight = FontWeight.Bold)
        Text(
            if (denied) "No tool run has been refused, timed out or left unapproved." else "Tool denials, approval prompts and authorizations for sensitive tools are recorded here.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LogCard(event: AuditEvent) {
    val color = typeColor(event.type)
    DroidCard(borderColor = color.copy(alpha = 0.35f)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(event.type.name.replace('_', ' '), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(logTime.format(event.timestamp), color = TextSecondary, fontSize = 11.sp)
        }
        Text(event.subject, color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        if (event.detail.isNotBlank()) {
            Text(event.detail, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
