package ai.droidcommand.app.ui.systemsettings

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import ai.droidcommand.systemsettings.AccessKind
import ai.droidcommand.systemsettings.BackendState
import ai.droidcommand.systemsettings.Change
import ai.droidcommand.systemsettings.OperationStatus
import ai.droidcommand.systemsettings.Proposed
import ai.droidcommand.systemsettings.ReadResult
import ai.droidcommand.systemsettings.ReplayStatus
import ai.droidcommand.systemsettings.RequestSource
import ai.droidcommand.systemsettings.RestoreRequest
import ai.droidcommand.systemsettings.RestoreStatus
import ai.droidcommand.systemsettings.RiskClass
import ai.droidcommand.systemsettings.SettingKey
import ai.droidcommand.systemsettings.SettingsCatalog
import ai.droidcommand.systemsettings.SettingsTable
import ai.droidcommand.systemsettings.Snapshot
import ai.droidcommand.systemsettings.describe
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun failureWords(r: ReadResult.Failed) = "Cannot read it (${r.failure.kind.name.lowercase().replace('_', ' ')}): ${r.failure.message}"

// ---------------------------------------------------------------------------------------------------------------------------------
// Curated
// ---------------------------------------------------------------------------------------------------------------------------------

@Composable
internal fun CuratedTab(vm: SystemSettingsViewModel) {
    Text(
        "Settings this app understands. Each one is checked against Android's own documentation before it is offered, and a stored value is " +
            "only a request: Android or your phone maker may ignore it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (vm.curated.isEmpty() && !vm.loading) Text("No settings could be read. Check the Access tab.")
    val dnsRows = vm.curated.filter { it.spec.group == "Private DNS" }
    vm.curated.filter { it.spec.group != "Private DNS" }.forEach { row -> CuratedCard(vm, row) }
    if (dnsRows.isNotEmpty()) PrivateDnsCard(vm, dnsRows)
}

@Composable
private fun CuratedCard(vm: SystemSettingsViewModel, row: ai.droidcommand.systemsettings.SystemSettingsEngine.CuratedRow) {
    val spec = row.spec
    var custom by rememberSaveable(spec.id) { mutableStateOf("") }
    var details by rememberSaveable(spec.id) { mutableStateOf(false) }
    DroidCard(modifier = Modifier.testTag("ss_curated_${spec.name}")) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(spec.title, style = MaterialTheme.typography.titleMedium)
            Text("${spec.table.wire}/${spec.name}  ·  ${if (spec.table.deviceWide) "whole device" else "user ${vm.device.userId}"}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(spec.description, style = MaterialTheme.typography.bodyMedium)
            when {
                row.unsupportedReason != null -> Text("Not available here: ${row.unsupportedReason}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("ss_unsupported_${spec.name}"))
                row.current is ReadResult.Failed -> Text(failureWords(row.current as ReadResult.Failed), color = MaterialTheme.colorScheme.error)
                row.current is ReadResult.Value -> Text("Now: " + (row.current as ReadResult.Value).snapshot.describe(), fontWeight = FontWeight.Medium, modifier = Modifier.testTag("ss_value_${spec.name}"))
            }
            if (spec.group == "Refresh rate") {
                Text(
                    "Display is running at " + (vm.observedRate?.let { "%.0f Hz".format(it) } ?: "an unknown rate") + " right now. That is what the display is doing, not the stored preference.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (row.unsupportedReason == null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.presets.forEach { p ->
                        FilterChip(
                            selected = false,
                            onClick = { vm.previewCurated(spec, p.value) },
                            label = { Text(p.label) },
                            modifier = Modifier.testTag("ss_preset_${spec.name}_${p.value}"),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = custom, onValueChange = { custom = it.take(64) }, singleLine = true, label = { Text("Custom value") },
                        modifier = Modifier.weight(1f).testTag("ss_custom_${spec.name}"),
                    )
                    DroidSecondaryButton(onClick = { vm.previewCurated(spec, custom.trim()) }, enabled = custom.isNotBlank(), modifier = Modifier.testTag("ss_custom_preview_${spec.name}")) { Text("Preview") }
                }
            }
            DroidTextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Details and checks") }
            if (details) {
                Text("Accepted: ${spec.type.rangeText(vm.device)}", style = MaterialTheme.typography.bodySmall)
                Text("What a stored value proves: ${spec.effectNote}", style = MaterialTheme.typography.bodySmall)
                spec.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text("Checked against: ${spec.sources}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Needs Android API ${spec.minSdk} or newer. Other phone makers may differ; only this phone has been asked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PrivateDnsCard(vm: SystemSettingsViewModel, rows: List<ai.droidcommand.systemsettings.SystemSettingsEngine.CuratedRow>) {
    val mode = rows.first { it.spec.name == SettingsCatalog.PRIVATE_DNS_MODE }
    val host = rows.first { it.spec.name == SettingsCatalog.PRIVATE_DNS_SPECIFIER }
    val currentMode = ((mode.current as? ReadResult.Value)?.snapshot as? Snapshot.Present)?.value
    val currentHost = ((host.current as? ReadResult.Value)?.snapshot as? Snapshot.Present)?.value.orEmpty()
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var hostText by rememberSaveable { mutableStateOf("") }
    DroidCard(modifier = Modifier.testTag("ss_private_dns")) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Private DNS", style = MaterialTheme.typography.titleMedium)
            Text("global/private_dns_mode + global/private_dns_specifier  ·  whole device", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("Encrypts DNS lookups (DNS-over-TLS). The mode and the provider hostname are changed together and both previous values are recorded.", style = MaterialTheme.typography.bodyMedium)
            if (mode.unsupportedReason != null) {
                Text("Not available here: ${mode.unsupportedReason}")
                return@Column
            }
            Text("Configured: mode ${currentMode ?: "(not set)"}, hostname ${currentHost.ifEmpty { "(none)" }}", fontWeight = FontWeight.Medium, modifier = Modifier.testTag("ss_dns_configured"))
            Text("Active: ${vm.dnsLine ?: "checking…"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ss_dns_active"))
            Text("Configured and active can differ: the setting is a request; the network and provider decide.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("off" to "Off", "opportunistic" to "Automatic", "hostname" to "Provider hostname").forEach { (v, label) ->
                    FilterChip(selected = chosen == v, onClick = { chosen = v }, label = { Text(label) }, modifier = Modifier.testTag("ss_dns_mode_$v"))
                }
            }
            if (chosen == "hostname") {
                OutlinedTextField(
                    value = hostText, onValueChange = { hostText = it.trim().take(253) }, singleLine = true,
                    label = { Text("Provider hostname") }, placeholder = { Text(currentHost.ifEmpty { "dns.example.net" }) },
                    modifier = Modifier.fillMaxWidth().testTag("ss_dns_host"),
                )
            }
            DroidSecondaryButton(
                onClick = {
                    val m = chosen ?: return@DroidSecondaryButton
                    val changes = buildList {
                        add(Change(mode.key, Proposed.Put(m)))
                        if (m == "hostname") add(Change(host.key, Proposed.Put(hostText.ifBlank { currentHost })))
                    }
                    vm.previewChange("Private DNS: $m", changes)
                },
                enabled = chosen != null,
                modifier = Modifier.testTag("ss_dns_preview"),
            ) { Text("Preview Private DNS change") }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------------
// Browse
// ---------------------------------------------------------------------------------------------------------------------------------

@Composable
internal fun BrowseTab(vm: SystemSettingsViewModel, onEdit: (SettingsTable, String) -> Unit) {
    var table by rememberSaveable { mutableStateOf(SettingsTable.GLOBAL.wire) }
    var via by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val t = SettingsTable.fromWire(table) ?: SettingsTable.GLOBAL
    Text(
        "Every row in a table, as Android reports it. Unknown settings are labelled unknown: this app does not guess what they do.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsTable.entries.forEach { st ->
            FilterChip(selected = t == st, onClick = { table = st.wire }, label = { Text(st.wire.replaceFirstChar { it.uppercase() }) }, modifier = Modifier.testTag("ss_table_${st.wire}"))
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Read via", style = MaterialTheme.typography.bodySmall)
        FilterChip(selected = via == null, onClick = { via = null }, label = { Text("Automatic") })
        AccessKind.entries.forEach { k -> FilterChip(selected = via == k.name, onClick = { via = k.name }, label = { Text(k.label) }) }
    }
    OutlinedTextField(
        value = query, onValueChange = { query = it.take(64) }, singleLine = true, label = { Text("Search names or values") },
        modifier = Modifier.fillMaxWidth().testTag("ss_browse_search"),
    )
    DroidPrimaryButton(onClick = { vm.loadTable(t, via?.let { AccessKind.valueOf(it) }) }, modifier = Modifier.testTag("ss_browse_load")) { Text("Load ${t.wire} table") }
    val b = vm.browse
    when {
        vm.busy && !b.loaded -> Text("Loading…")
        b.error != null -> Text(b.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ss_browse_error"))
        b.loaded && b.rows.isEmpty() -> Text("This table is empty.")
        !b.loaded -> Text("Tap Load to read the ${t.wire} table.")
        else -> {
            val shown = b.rows.filter { query.isBlank() || it.key.name.contains(query, true) || it.value.contains(query, true) || it.spec?.title?.contains(query, true) == true }
            Text("${shown.size} of ${b.rows.size} entries" + (b.servedBy?.let { " · read via ${it.label}" } ?: "") + if (b.partial) " · some multi-line values could not be listed" else "", style = MaterialTheme.typography.bodySmall)
            shown.take(MAX_ROWS).forEach { r ->
                var reveal by remember(r.key.id) { mutableStateOf(false) }
                DroidCard(modifier = Modifier.testTag("ss_row_${r.key.name}")) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(r.key.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                        val masked = r.risk == RiskClass.HIGH_IMPACT && !reveal
                        Text(if (masked) "•••••• (high-impact value hidden)" else r.value.ifEmpty { "(empty string)" }.take(300), style = MaterialTheme.typography.bodySmall)
                        Text(
                            when (r.risk) {
                                RiskClass.CURATED -> r.spec?.title ?: "Known setting"
                                RiskClass.UNREVIEWED -> "Unknown setting: nothing is known about what it does."
                                RiskClass.HIGH_IMPACT -> "High-impact: identity, credentials, provisioning, accessibility or input."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r.risk == RiskClass.HIGH_IMPACT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            if (r.risk == RiskClass.HIGH_IMPACT) DroidTextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
                            DroidTextButton(onClick = { onEdit(r.key.table, r.key.name) }) { Text("Open in editor") }
                        }
                    }
                }
            }
            if (shown.size > MAX_ROWS) Text("Showing the first $MAX_ROWS. Narrow the search to see others.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val MAX_ROWS = 200

// ---------------------------------------------------------------------------------------------------------------------------------
// Advanced
// ---------------------------------------------------------------------------------------------------------------------------------

@Composable
internal fun AdvancedTab(vm: SystemSettingsViewModel, prefillTable: SettingsTable?, prefillName: String, onConsumed: () -> Unit) {
    var table by rememberSaveable { mutableStateOf((prefillTable ?: SettingsTable.GLOBAL).wire) }
    var name by rememberSaveable { mutableStateOf(prefillName) }
    var value by rememberSaveable { mutableStateOf("") }
    var remove by rememberSaveable { mutableStateOf(false) }
    var reviewed by rememberSaveable { mutableStateOf(false) }
    var highImpact by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(prefillTable, prefillName) {
        if (prefillTable != null && prefillName.isNotEmpty()) {
            table = prefillTable.wire
            name = prefillName
            value = ""
            remove = false
            reviewed = false
            highImpact = false
            onConsumed()
        }
    }
    val t = SettingsTable.fromWire(table) ?: SettingsTable.GLOBAL
    val risk = vm.riskOf(t, name)
    val spec = SettingKey.parse(t, name.trim(), vm.device.userId)?.let { SettingsCatalog.find(it) }
    Text(
        "Add, edit or remove any entry by hand. Entries this app does not know are labelled unknown and need your explicit review. " +
            "High-impact entries (identity, credentials, accessibility, input methods) need a second confirmation.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsTable.entries.forEach { st -> FilterChip(selected = t == st, onClick = { table = st.wire }, label = { Text(st.wire.replaceFirstChar { it.uppercase() }) }, modifier = Modifier.testTag("ss_adv_table_${st.wire}")) }
    }
    Text("Scope: ${if (t.deviceWide) "whole device (Global is not per user)" else "user ${vm.device.userId}"}", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(value = name, onValueChange = { name = it.take(128); vm.clearReadout() }, singleLine = true, label = { Text("Setting name") }, modifier = Modifier.fillMaxWidth().testTag("ss_adv_name"))
    if (name.isNotBlank()) {
        Text(
            when {
                spec != null -> "Known: ${spec.title}. ${spec.description} Accepted: ${spec.type.rangeText(vm.device)}."
                risk == RiskClass.HIGH_IMPACT -> "High-impact entry."
                else -> "Unknown setting. This app does not know what it does and will not describe it."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (risk == RiskClass.HIGH_IMPACT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("ss_adv_known"),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DroidSecondaryButton(onClick = { vm.readKey(t, name) }, enabled = name.isNotBlank(), modifier = Modifier.testTag("ss_adv_read")) { Text("Read current value") }
    }
    vm.readout?.let { Text(it.text, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("ss_adv_readout")) }
    OutlinedTextField(value = value, onValueChange = { value = it.take(512) }, enabled = !remove, label = { Text("New value") }, modifier = Modifier.fillMaxWidth().testTag("ss_adv_value"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = remove, onCheckedChange = { remove = it }, modifier = Modifier.testTag("ss_adv_remove"))
        Text("Remove the entry instead (restoring will put it back)")
    }
    if (risk == RiskClass.UNREVIEWED && name.isNotBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = reviewed, onCheckedChange = { reviewed = it }, modifier = Modifier.testTag("ss_adv_reviewed"))
            Text("I reviewed this entry myself. I understand the app cannot say what it does.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (risk == RiskClass.HIGH_IMPACT) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = highImpact, onCheckedChange = { highImpact = it }, modifier = Modifier.testTag("ss_adv_highimpact"))
            Text("I understand this can lock me out, break accessibility or input, or change who this phone trusts.", style = MaterialTheme.typography.bodySmall)
        }
    }
    DroidPrimaryButton(
        onClick = { vm.previewAdvanced(t, name, value, remove, reviewed || risk == RiskClass.CURATED, highImpact) },
        enabled = name.isNotBlank(),
        modifier = Modifier.testTag("ss_adv_preview"),
    ) { Text("Preview change") }
}

// ---------------------------------------------------------------------------------------------------------------------------------
// Modified and history
// ---------------------------------------------------------------------------------------------------------------------------------

@Composable
internal fun ModifiedTab(vm: SystemSettingsViewModel) {
    Text(
        "Entries this app changed, with the original value recorded before the first change. Restoring puts back the values recorded here. " +
            "It is not a factory reset and does not touch anything this app never changed.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val rows = vm.modified
    if (rows.isEmpty()) {
        Text("Nothing is modified. Entries appear here after you change them in this app.", modifier = Modifier.testTag("ss_modified_empty"))
        return
    }
    if (rows.any { it.differsFromBaseline }) {
        DroidSecondaryButton(onClick = { vm.previewRestore(RestoreRequest(RequestSource.UI, baseline = true)) }, modifier = Modifier.testTag("ss_restore_all")) { Text("Preview: restore all recorded originals") }
    }
    rows.forEach { m ->
        DroidCard(modifier = Modifier.testTag("ss_modified_${m.key.name}")) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${m.key.table.wire}/${m.key.name}  ·  ${m.key.scopeLabel}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                Text("Original: ${m.baseline.describe()}")
                Text("Now: " + (m.current?.describe() ?: "unreadable (${m.unreadableReason ?: "unknown"})"))
                if (m.changedElsewhere) Text("Something else changed this since this app last set it.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (!m.differsFromBaseline) Text("Back at its original value.", style = MaterialTheme.typography.bodySmall)
                DroidSecondaryButton(
                    onClick = { vm.previewRestore(RestoreRequest(RequestSource.UI, baseline = true, keyIds = setOf(m.key.id))) },
                    enabled = m.differsFromBaseline,
                    modifier = Modifier.testTag("ss_restore_${m.key.name}"),
                ) { Text("Restore original") }
            }
        }
    }
}

@Composable
internal fun HistoryTab(vm: SystemSettingsViewModel) {
    Text("Every change and restore, newest first. Records are kept on this phone only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (vm.history.isEmpty()) {
        Text("No changes recorded yet.", modifier = Modifier.testTag("ss_history_empty"))
        return
    }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    vm.history.take(50).forEach { op ->
        DroidCard(modifier = Modifier.testTag("ss_history_${op.id.take(8)}")) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(op.label, fontWeight = FontWeight.Medium)
                Text(
                    "${fmt.format(Date(op.createdAt))} · ${op.kind.name.lowercase()} · from ${op.source.name.lowercase().replace('_', ' ')} · via ${op.backend.label} · ${statusWords(op.status)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                op.entries.forEach { e ->
                    Text(
                        "${e.key.table.wire}/${e.key.name}: ${e.previous.describe()} → ${e.proposed.describe()}",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        SystemSettingsViewModel.outcomeText(e.outcome) + when (e.restoreStatus) {
                            RestoreStatus.NOT_RESTORED -> ""
                            RestoreStatus.RESTORED -> " · restored"
                            RestoreStatus.AUTO_ROLLED_BACK -> " · put back automatically"
                            RestoreStatus.CONFLICT -> " · restore skipped: changed since"
                            RestoreStatus.RESTORE_FAILED -> " · restore failed"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (e.outcome.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                val restorable = op.entries.any { it.outcome.stored && it.restoreStatus in setOf(RestoreStatus.NOT_RESTORED, RestoreStatus.CONFLICT, RestoreStatus.RESTORE_FAILED) }
                if (restorable) {
                    DroidSecondaryButton(onClick = { vm.previewRestore(RestoreRequest(RequestSource.UI, operationId = op.id)) }, modifier = Modifier.testTag("ss_history_restore_${op.id.take(8)}")) { Text("Restore this change") }
                }
            }
        }
    }
}

private fun statusWords(s: OperationStatus) = when (s) {
    OperationStatus.COMPLETED -> "completed"
    OperationStatus.FAILED_ROLLED_BACK -> "failed, put back"
    OperationStatus.FAILED_PARTIAL -> "partly failed"
    OperationStatus.INTERRUPTED -> "interrupted"
    OperationStatus.APPLYING -> "running"
}

// ---------------------------------------------------------------------------------------------------------------------------------
// Profiles
// ---------------------------------------------------------------------------------------------------------------------------------

@Composable
internal fun ProfilesTab(vm: SystemSettingsViewModel) {
    var saveDialog by rememberSaveable { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<ai.droidcommand.systemsettings.SettingsProfile?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importProfile(uri) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val p = exporting
        exporting = null
        if (uri != null && p != null) vm.exportProfile(p, uri)
    }
    Text(
        "Saved sets of reviewed settings. Saving or importing a profile never applies it: you always see the exact changes first. " +
            "Profiles hold no accounts, credentials, accessibility or keyboard settings.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DroidSecondaryButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.testTag("ss_profile_import")) { Text("Import JSON") }
        DroidSecondaryButton(onClick = { saveDialog = true }, modifier = Modifier.testTag("ss_profile_save_current")) { Text("Save current values") }
    }
    vm.profiles.forEach { p ->
        val rec = vm.replay[p.id]
        DroidCard(modifier = Modifier.testTag("ss_profile_${p.id}")) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.name + if (p.builtIn) "  (starter)" else "", style = MaterialTheme.typography.titleMedium)
                if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodyMedium)
                p.entries.forEach { e -> Text("${e.table.wire}/${e.name} = ${e.value}" + if (e.optional) "  (optional)" else "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                if (p.tradeoffs.isNotBlank()) Text("Tradeoffs: ${p.tradeoffs}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DroidPrimaryButton(onClick = { vm.previewProfile(p, includeOptional = false) }, modifier = Modifier.testTag("ss_profile_apply_${p.id}")) { Text("Preview and apply") }
                    DroidSecondaryButton(onClick = { exporting = p; exporter.launch("${p.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.profile.json") }) { Text("Export") }
                    if (!p.builtIn) DroidTextButton(onClick = { vm.deleteProfile(p) }) { Text("Delete") }
                }
                SwitchRow(
                    "Reapply after reboot", "Off by default. " + (vm.requirements[p.id] ?: ""),
                    checked = rec?.enabled == true, tag = "ss_replay_${p.id}",
                ) { vm.setReplay(p, it) }
                if (rec != null) Text(replayWords(rec.status) + (if (rec.lastOutcome.isNotBlank()) " ${rec.lastOutcome}" else ""), style = MaterialTheme.typography.bodySmall, color = if (rec.status.suspended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (saveDialog) SaveProfileDialog(vm) { saveDialog = false }
}

private fun replayWords(s: ReplayStatus) = when (s) {
    ReplayStatus.ARMED -> "Armed. It will run after the next reboot."
    ReplayStatus.UP_TO_DATE -> "Up to date at the last boot."
    ReplayStatus.APPLIED -> "Applied at the last boot."
    ReplayStatus.PENDING_BACKEND_UNAVAILABLE -> "Pending: no allowed access method was ready at boot."
    ReplayStatus.FAILED -> "Failed at the last boot."
    ReplayStatus.SUSPENDED_REPEATED_FAILURES -> "Suspended after repeated failures. Turn it off and on to review."
    ReplayStatus.SUSPENDED_CONTEXT_CHANGED -> "Suspended: the firmware or Android version changed. Turn it off and on to review."
    ReplayStatus.SUSPENDED_PROFILE_CHANGED -> "Suspended: the profile changed. Turn it off and on to review."
    ReplayStatus.SUSPENDED_PROFILE_MISSING -> "Suspended: the profile is gone."
}

@Composable
private fun SaveProfileDialog(vm: SystemSettingsViewModel, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(setOf<String>()) }
    val candidates = vm.curated.filter { it.unsupportedReason == null && vm.hasCuratedValue(it.spec.id) && it.spec.group != "Private DNS" }
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("ss_save_profile_dialog"),
        title = { Text("Save current values") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(64) }, singleLine = true, label = { Text("Profile name") }, modifier = Modifier.fillMaxWidth().testTag("ss_save_profile_name"))
                Text("Choose settings to include. Their current values are saved. Nothing is applied.", style = MaterialTheme.typography.bodySmall)
                candidates.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = c.spec.id in picked, onCheckedChange = { on -> picked = if (on) picked + c.spec.id else picked - c.spec.id })
                        Text(c.spec.title)
                    }
                }
            }
        },
        confirmButton = { DroidPrimaryButton(onClick = { vm.saveCurrentAsProfile(name, picked); onClose() }, enabled = name.isNotBlank() && picked.isNotEmpty(), modifier = Modifier.testTag("ss_save_profile_ok")) { Text("Save") } },
        dismissButton = { DroidTextButton(onClick = onClose) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------------------------------------------------------------
// Access
// ---------------------------------------------------------------------------------------------------------------------------------

@Composable
internal fun AccessTab(vm: SystemSettingsViewModel) {
    val context = LocalContext.current
    var confirmAssistant by rememberSaveable { mutableStateOf(false) }
    var confirmRoot by rememberSaveable { mutableStateOf(false) }
    SectionLabel("Access methods")
    Text(
        "The app uses the least privileged method that can do the job and never moves up by itself. If a method fails, the change stops and says why.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    vm.statuses.forEach { s ->
        DroidCard(modifier = Modifier.testTag("ss_status_${s.kind.name.lowercase()}")) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.kind.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        s.state == BackendState.READY && s.readOnly -> "Ready, read-only"
                        s.state == BackendState.READY -> "Ready"
                        s.state == BackendState.PERMISSION_MISSING -> "Permission missing"
                        else -> "Unavailable"
                    } + if (s.kind !in vm.config.allowed) " · turned off below" else "",
                    fontWeight = FontWeight.Medium,
                )
                Text(s.detail, style = MaterialTheme.typography.bodySmall)
                Text("Can write: " + s.writableTables.joinToString { it.wire }.ifEmpty { "nothing" } + " · other users: " + if (s.crossUser) "reachable if you allow it" else "no", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    SectionLabel("What may be used")
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SwitchRow("Shizuku", "On by default. Used only when standard permissions cannot do the job.", AccessKind.SHIZUKU in vm.config.allowed, "ss_allow_shizuku") { vm.setShizukuAllowed(it) }
            SwitchRow("Root", "Off by default. A rooted phone is not enough on its own. Turning this on lets this screen use su when nothing lighter can.", AccessKind.ROOT in vm.config.allowed, "ss_allow_root") { if (it) confirmRoot = true else vm.setRootAllowed(false) }
            SwitchRow("Other users", "Off by default. Lets Shizuku or root change another user's System and Secure settings.", vm.config.allowOtherUsers, "ss_allow_other_users") { vm.setOtherUsers(it) }
            SwitchRow("Let the assistant use System Settings", "Off by default. The assistant sees only reviewed settings, never raw tables, and every change still asks you with the exact values.", vm.config.assistantEnabled, "ss_allow_assistant") { if (it) confirmAssistant = true else vm.setAssistant(false) }
        }
    }
    Text("Remote: no remote settings command exists in this app, so remote requests are refused.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("ss_remote_note"))
    SectionLabel("Set up access for this phone")
    vm.setupSteps().forEach { step ->
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(step.title, fontWeight = FontWeight.Medium)
                Text(step.body, style = MaterialTheme.typography.bodySmall)
                step.command?.let { cmd ->
                    MonoBlock(cmd, "ss_cmd_${cmd.hashCode()}")
                    DroidTextButton(onClick = { copy(context, cmd) }) { Text("Copy command") }
                }
            }
        }
    }
    DroidSecondaryButton(
        onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${vm.applicationId}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
        modifier = Modifier.testTag("ss_open_write_settings"),
    ) { Text("Open 'Modify system settings'") }
    SectionLabel("This phone")
    DroidCard {
        val d = vm.device
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(d.summary, modifier = Modifier.testTag("ss_device_summary"))
            Text("Running as user ${d.userId}. Build: ${d.fingerprint}", style = MaterialTheme.typography.bodySmall)
            Text("Display refresh rates: " + d.supportedRefreshRatesHz.joinToString { "%.0f".format(it) }.ifEmpty { "not reported" } + " Hz", style = MaterialTheme.typography.bodySmall)
            Text("This has not been tested on every phone or OEM build. A stored value does not prove the phone changed behaviour.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmAssistant) {
        AlertDialog(
            onDismissRequest = { confirmAssistant = false },
            title = { Text("Let the assistant use System Settings?") },
            text = { Text("It could ask to change reviewed settings. Each request shows the old and new values and waits for you. It cannot touch accounts, credentials, accessibility, keyboards, or settings this app does not know.") },
            confirmButton = { DroidPrimaryButton(onClick = { vm.setAssistant(true); confirmAssistant = false }, modifier = Modifier.testTag("ss_assistant_confirm")) { Text("Allow") } },
            dismissButton = { DroidTextButton(onClick = { confirmAssistant = false }) { Text("Cancel") } },
        )
    }
    if (confirmRoot) {
        AlertDialog(
            onDismissRequest = { confirmRoot = false },
            title = { Text("Allow root for System Settings?") },
            text = { Text("When Standard permissions and Shizuku cannot do the job, this screen can run the settings command as root. Your root manager still decides each request.") },
            confirmButton = { DroidPrimaryButton(onClick = { vm.setRootAllowed(true); confirmRoot = false }, modifier = Modifier.testTag("ss_root_confirm")) { Text("Allow root") } },
            dismissButton = { DroidTextButton(onClick = { confirmRoot = false }) { Text("Cancel") } },
        )
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("System Settings command", text))
}
