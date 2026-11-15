package ai.droidcommand.app.ui.systemsettings

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.systemsettings.BackendState
import ai.droidcommand.systemsettings.OperationStatus
import ai.droidcommand.systemsettings.RestoreState
import ai.droidcommand.systemsettings.SettingsTable
import ai.droidcommand.systemsettings.describe
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private val TABS = listOf("Curated", "Browse", "Advanced", "Modified", "History", "Profiles", "Access")

/**
 * Victorious Framework · System Settings. A native editor for the System, Secure and Global settings tables. Nothing changes until the owner
 * confirms a preview that shows the old and new value, and every change is recorded so it can be restored.
 */
@Composable
fun SystemSettingsScreen(viewModel: SystemSettingsViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editTable by rememberSaveable { mutableStateOf<String?>(null) }
    var editName by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp).testTag("system_settings_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenTitleWithHelp("System Settings", ScreenHelpCatalog.SYSTEM_SETTINGS)
        Text("Victorious Framework", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AccessSummary(viewModel)
        viewModel.banner?.let { b ->
            DroidCard(modifier = Modifier.testTag("ss_banner")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    DroidTextButton(onClick = { viewModel.banner = null }) { Text("Dismiss") }
                }
            }
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            TABS.forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }, modifier = Modifier.testTag("ss_tab_$label"))
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (viewModel.loading && viewModel.statuses.isEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp).semantics { contentDescription = "Loading settings" }, strokeWidth = 2.dp)
                    Text("Reading this phone's settings…")
                }
            }
            when (tab) {
                0 -> CuratedTab(viewModel)
                1 -> BrowseTab(viewModel) { table, name -> editTable = table.wire; editName = name; tab = 2 }
                2 -> AdvancedTab(viewModel, editTable?.let { SettingsTable.fromWire(it) }, editName) { editTable = null; editName = "" }
                3 -> ModifiedTab(viewModel)
                4 -> HistoryTab(viewModel)
                5 -> ProfilesTab(viewModel)
                else -> AccessTab(viewModel)
            }
        }
    }
    PlanDialog(viewModel)
    RestoreDialog(viewModel)
    OutcomeDialog(viewModel)
    ImportDialog(viewModel)
}

@Composable
private fun AccessSummary(vm: SystemSettingsViewModel) {
    DroidCard(modifier = Modifier.testTag("ss_access_summary")) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (vm.statuses.isEmpty()) {
                Text("Checking access…", style = MaterialTheme.typography.bodySmall)
            }
            vm.statuses.forEach { s ->
                val label = when {
                    s.state == BackendState.READY && s.readOnly -> "read-only"
                    s.state == BackendState.READY -> "ready"
                    s.state == BackendState.PERMISSION_MISSING -> "permission missing"
                    else -> "unavailable"
                }
                val allowed = vm.config.allowed.contains(s.kind)
                Text(
                    "${s.kind.label}: $label" + if (!allowed) " (turned off in Access)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (s.state == BackendState.READY && allowed) FontWeight.Medium else FontWeight.Normal,
                    color = if (s.state == BackendState.READY && allowed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlanDialog(vm: SystemSettingsViewModel) {
    val p = vm.pending ?: return
    AlertDialog(
        onDismissRequest = vm::cancelPending,
        modifier = Modifier.testTag("ss_plan_dialog"),
        title = { Text("Review before changing") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.title, fontWeight = FontWeight.Medium)
                Text(
                    "Access: " + (p.plan.backend?.label ?: "none available") + ". Nothing changes until you tap Apply. Every write is read back and recorded so you can restore it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                p.plan.entries.forEach { e ->
                    val k = e.change.key
                    Column(Modifier.fillMaxWidth().testTag("ss_plan_entry_${k.name}")) {
                        Text("${k.table.wire}/${k.name}  ·  ${k.scopeLabel}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text(
                            (e.current?.describe() ?: "(unreadable)") + "  →  " + e.change.proposed.describe() +
                                if (e.noop) "  (already set)" else "",
                            fontWeight = FontWeight.Medium,
                        )
                        Text(e.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                p.unsupported.forEach { u ->
                    Text("Not supported on this phone: ${u.entry.name} — ${u.reason}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                p.profile?.let { prof ->
                    if (prof.tradeoffs.isNotBlank()) Text("Tradeoffs: ${prof.tradeoffs}", style = MaterialTheme.typography.bodySmall)
                    if (prof.entries.any { it.optional }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = p.includeOptional, onCheckedChange = vm::setIncludeOptional, modifier = Modifier.testTag("ss_include_optional"))
                            Text("Include optional entries")
                        }
                    }
                }
                p.plan.blockers.forEach { Text("Blocked: ${it.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                p.plan.warnings.forEach { Text("Note: ${it.message}", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            DroidPrimaryButton(onClick = vm::confirmPending, enabled = p.plan.executable, modifier = Modifier.testTag("ss_plan_apply")) { Text("Apply") }
        },
        dismissButton = { DroidTextButton(onClick = vm::cancelPending, modifier = Modifier.testTag("ss_plan_cancel")) { Text("Cancel") } },
    )
}

@Composable
private fun RestoreDialog(vm: SystemSettingsViewModel) {
    val r = vm.pendingRestore ?: return
    AlertDialog(
        onDismissRequest = vm::cancelRestore,
        modifier = Modifier.testTag("ss_restore_dialog"),
        title = { Text("Restore recorded values") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.preview.title, fontWeight = FontWeight.Medium)
                Text(
                    "This puts back the values this app recorded. It is not a factory reset, and it leaves alone anything that changed since. " +
                        "Access: ${r.preview.backend?.label ?: "none available"}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                r.preview.items.forEach { it ->
                    Column(Modifier.fillMaxWidth().testTag("ss_restore_item_${it.key.name}")) {
                        Text("${it.key.table.wire}/${it.key.name}  ·  ${it.key.scopeLabel}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text(
                            (it.current?.describe() ?: "(unreadable)") + "  →  " + it.target.describe(),
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            when (it.state) {
                                RestoreState.READY -> "Ready. " + it.note
                                RestoreState.ALREADY_AT_TARGET -> it.note
                                RestoreState.CONFLICT -> "Conflict. " + it.note
                                RestoreState.UNREADABLE -> it.note
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.state == RestoreState.CONFLICT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (it.state == RestoreState.CONFLICT) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = it.key.id in vm.forced, onCheckedChange = { _ -> vm.toggleForce(it.key.id) }, modifier = Modifier.testTag("ss_force_${it.key.name}"))
                                Text("Overwrite the newer value anyway", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                r.preview.blockers.forEach { Text("Blocked: ${it.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            DroidPrimaryButton(onClick = vm::confirmRestore, enabled = r.preview.actionable, modifier = Modifier.testTag("ss_restore_apply")) { Text("Restore") }
        },
        dismissButton = { DroidTextButton(onClick = vm::cancelRestore) { Text("Cancel") } },
    )
}

@Composable
private fun OutcomeDialog(vm: SystemSettingsViewModel) {
    val o = vm.outcome ?: return
    AlertDialog(
        onDismissRequest = { vm.outcome = null },
        modifier = Modifier.testTag("ss_outcome_dialog"),
        title = { Text(o.operation?.status?.let { statusTitle(it) } ?: "Nothing changed") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(o.title, fontWeight = FontWeight.Medium)
                o.note?.let { Text(it) }
                o.operation?.entries?.forEach { e ->
                    Column(Modifier.testTag("ss_outcome_entry_${e.key.name}")) {
                        Text("${e.key.table.wire}/${e.key.name}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text(SystemSettingsViewModel.outcomeText(e.outcome), fontWeight = FontWeight.Medium)
                        val extra = listOfNotNull(e.note.ifBlank { null }, e.effectNote, e.restoreNote.ifBlank { null }).joinToString(" ")
                        if (extra.isNotBlank()) Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (o.skipped.isNotEmpty()) {
                    Text("Left alone (changed since): " + o.skipped.joinToString { it.key.name }, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { DroidTextButton(onClick = { vm.outcome = null }, modifier = Modifier.testTag("ss_outcome_ok")) { Text("OK") } },
    )
}

private fun statusTitle(s: OperationStatus) = when (s) {
    OperationStatus.COMPLETED -> "Done"
    OperationStatus.FAILED_ROLLED_BACK -> "Failed, then put back"
    OperationStatus.FAILED_PARTIAL -> "Partly failed. Check each entry"
    OperationStatus.INTERRUPTED -> "Interrupted"
    OperationStatus.APPLYING -> "Still running"
}

@Composable
private fun ImportDialog(vm: SystemSettingsViewModel) {
    val r = vm.importReview ?: return
    AlertDialog(
        onDismissRequest = { vm.importReview = null },
        modifier = Modifier.testTag("ss_import_dialog"),
        title = { Text(if (r.profile != null) "Import profile" else "This file cannot be imported") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                r.profile?.let { p ->
                    Text(p.name, fontWeight = FontWeight.Medium)
                    if (p.description.isNotBlank()) Text(p.description)
                    p.entries.forEach { e -> Text("${e.table.wire}/${e.name} = ${e.value}" + if (e.optional) " (optional)" else "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("Saving it does not apply it. You will see the exact changes before anything is applied.", style = MaterialTheme.typography.bodySmall)
                }
                r.errors.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (r.profile != null) DroidPrimaryButton(onClick = vm::keepImportedProfile, modifier = Modifier.testTag("ss_import_keep")) { Text("Save (do not apply)") }
        },
        dismissButton = { DroidTextButton(onClick = { vm.importReview = null }) { Text(if (r.profile != null) "Discard" else "Close") } },
    )
}

/** A labelled switch row used by the Access and Profiles tabs. */
@Composable
internal fun SwitchRow(title: String, body: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag).semantics { contentDescription = title })
    }
}

@Composable
internal fun MonoBlock(text: String, tag: String? = null) {
    SelectionContainer {
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}
