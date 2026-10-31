package ai.droidcommand.app.ui.toolbox

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.shizuku.toolbox.LogLevel
import ai.droidcommand.shizuku.toolbox.PackageFilter
import ai.droidcommand.shizuku.toolbox.RebootTarget
import ai.droidcommand.shizuku.toolbox.SettingsNamespace
import ai.droidcommand.shizuku.toolbox.ToolboxCommands
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private val TABS = listOf("Shell", "Apps", "Logcat", "Settings", "Files", "Device", "Reboot")

/**
 * Shizuku / root power tools. Nothing here runs on its own: read-only Shizuku commands run when their button is tapped,
 * and anything that changes the phone (or anything sent through root) opens a dialog that shows the exact command first.
 */
@Composable
fun SystemToolboxScreen(viewModel: SystemToolboxViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp).testTag("system_toolbox_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenTitleWithHelp("System Toolbox", ScreenHelpCatalog.SYSTEM_TOOLBOX)
        BackendCard(viewModel)
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            TABS.forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (tab) {
                0 -> ShellTab(viewModel)
                1 -> AppsTab(viewModel)
                2 -> LogcatTab(viewModel)
                3 -> SettingsTab(viewModel)
                4 -> FilesTab(viewModel)
                5 -> DeviceTab(viewModel)
                else -> RebootTab(viewModel)
            }
            if (tab != 2 && viewModel.output.isNotEmpty()) OutputBox(viewModel.output)
        }
    }
    viewModel.pending?.let { p ->
        AlertDialog(
            onDismissRequest = viewModel::cancel,
            modifier = Modifier.testTag("toolbox_confirm"),
            title = { Text(if (p.command.destructive) "Run this command?" else "Run through root?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${p.command.label} via ${viewModel.backend.name}. This exact command will run:")
                    SelectionContainer {
                        Text(p.shown, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("toolbox_confirm_command"))
                    }
                    if (p.command.destructive) Text("It can change this phone. Cancel if you are not sure.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirm, modifier = Modifier.testTag("toolbox_confirm_run")) { Text("Run") } },
            dismissButton = { TextButton(onClick = viewModel::cancel) { Text("Cancel") } },
        )
    }
    viewModel.message?.let { m ->
        AlertDialog(
            onDismissRequest = { viewModel.message = null },
            text = { Text(m) },
            confirmButton = { TextButton(onClick = { viewModel.message = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun BackendCard(viewModel: SystemToolboxViewModel) {
    var confirmAi by remember { mutableStateOf(false) }
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !viewModel.useRoot, onClick = { viewModel.selectRoot(false) }, label = { Text("Shizuku") }, modifier = Modifier.testTag("toolbox_backend_shizuku"))
                FilterChip(selected = viewModel.useRoot, onClick = { viewModel.selectRoot(true) }, label = { Text("Root") }, modifier = Modifier.testTag("toolbox_backend_root"))
            }
            val available = if (viewModel.useRoot) viewModel.rootAvailable() else viewModel.shizukuAvailable()
            Text(
                if (available) "${viewModel.backend.name} is ready." else "${viewModel.backend.name} is not available. Start built-in Shizuku on the Advanced tab, or use a rooted phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Let the assistant use the toolbox", fontWeight = FontWeight.Medium)
                    Text("Off by default. Even when on, it asks you before every command.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = viewModel.aiEnabled,
                    onCheckedChange = { on -> if (on) confirmAi = true else viewModel.setAi(false) },
                    modifier = Modifier.testTag("toolbox_ai_gate"),
                )
            }
        }
    }
    if (confirmAi) {
        AlertDialog(
            onDismissRequest = { confirmAi = false },
            title = { Text("Let the assistant use the toolbox?") },
            text = {
                Text(
                    "The assistant could list apps, read permissions, settings, battery and storage, and ask to force-stop, " +
                        "disable or enable an app. It cannot open a shell or reboot. Every request shows you the exact command and waits for you.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setAi(true)
                    confirmAi = false
                }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { confirmAi = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OutputBox(text: String) {
    DroidCard(modifier = Modifier.testTag("toolbox_output")) {
        SelectionContainer {
            Text(text.take(20_000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.horizontalScroll(rememberScrollState()))
        }
    }
}

@Composable
private fun ShellTab(viewModel: SystemToolboxViewModel) {
    var command by rememberSaveable { mutableStateOf("") }
    SectionLabel("Shell")
    Text("Every command is shown to you before it runs.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Command") }, modifier = Modifier.fillMaxWidth().testTag("toolbox_shell_input"), singleLine = false)
    OutlinedButton(onClick = { viewModel.request(ToolboxCommands.shell(command)) }, enabled = !viewModel.busy, modifier = Modifier.testTag("toolbox_shell_run")) { Text("Run…") }
}

@Composable
private fun AppsTab(viewModel: SystemToolboxViewModel) {
    var filter by rememberSaveable { mutableStateOf(PackageFilter.USER) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    SectionLabel("App manager")
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PackageFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
    }
    OutlinedButton(onClick = { viewModel.listPackages(filter) }, enabled = !viewModel.busy, modifier = Modifier.testTag("toolbox_list_packages")) { Text("List packages") }
    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Filter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    viewModel.packages.filter { query.isBlank() || query.lowercase() in it.lowercase() }.take(200).forEach { pkg ->
        TextButton(onClick = { selected = pkg }, modifier = Modifier.fillMaxWidth()) { Text(pkg, modifier = Modifier.fillMaxWidth()) }
    }
    selected?.let { pkg ->
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pkg, fontWeight = FontWeight.Medium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { viewModel.loadPermissions(pkg) }) { Text("Permissions") }
                    OutlinedButton(onClick = { viewModel.backupApk(pkg) }) { Text("Back up APK") }
                    OutlinedButton(onClick = { viewModel.request(ToolboxCommands.forceStop(pkg)) }) { Text("Force stop") }
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { viewModel.request(ToolboxCommands.disable(pkg)) }) { Text("Disable") }
                    OutlinedButton(onClick = { viewModel.request(ToolboxCommands.enable(pkg)) }) { Text("Enable") }
                    OutlinedButton(onClick = { viewModel.request(ToolboxCommands.clearData(pkg)) }) { Text("Clear data") }
                    OutlinedButton(onClick = { viewModel.request(ToolboxCommands.uninstall(pkg)) }) { Text("Uninstall") }
                }
                viewModel.permissions.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (p.granted) "✓ " else "✗ ") + p.name.removePrefix("android.permission.") + if (p.runtime) "" else " (install)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (p.runtime) {
                            TextButton(onClick = {
                                viewModel.request(if (p.granted) ToolboxCommands.revoke(pkg, p.name) else ToolboxCommands.grant(pkg, p.name))
                            }) { Text(if (p.granted) "Revoke" else "Grant") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogcatTab(viewModel: SystemToolboxViewModel) {
    var level by rememberSaveable { mutableStateOf(LogLevel.WARN) }
    var filter by rememberSaveable { mutableStateOf("") }
    SectionLabel("Logcat")
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LogLevel.entries.forEach { l -> FilterChip(selected = level == l, onClick = { level = l }, label = { Text(l.name.lowercase()) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { viewModel.loadLogcat(level, 500) }, enabled = !viewModel.busy, modifier = Modifier.testTag("toolbox_logcat_load")) { Text("Load last 500") }
        OutlinedButton(onClick = { viewModel.saveLogcat(filter) }) { Text("Save") }
    }
    OutlinedTextField(value = filter, onValueChange = { filter = it }, label = { Text("Filter text") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val shown = viewModel.filteredLogcat(filter)
    if (shown.isNotEmpty()) OutputBox(shown)
}

@Composable
private fun SettingsTab(viewModel: SystemToolboxViewModel) {
    var ns by rememberSaveable { mutableStateOf(SettingsNamespace.GLOBAL) }
    var key by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    SectionLabel("Settings get / put")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsNamespace.entries.forEach { n -> FilterChip(selected = ns == n, onClick = { ns = n }, label = { Text(n.wire) }) }
    }
    OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("New value (for put)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { viewModel.request(ToolboxCommands.getSetting(ns, key)) }) { Text("Get") }
        OutlinedButton(onClick = { viewModel.request(ToolboxCommands.putSetting(ns, key, value)) }) { Text("Put…") }
    }
}

@Composable
private fun FilesTab(viewModel: SystemToolboxViewModel) {
    var path by rememberSaveable { mutableStateOf("/data/local/tmp") }
    SectionLabel("Root file browser (read-only)")
    Text("Needs root. Lists folders and shows the first 64 KB of a file; it never writes.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("Path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { viewModel.request(ToolboxCommands.listDir(path)) }) { Text("List") }
        OutlinedButton(onClick = { viewModel.request(ToolboxCommands.readFile(path)) }) { Text("Read") }
        OutlinedButton(onClick = { path = path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" } }) { Text("Up") }
    }
}

@Composable
private fun DeviceTab(viewModel: SystemToolboxViewModel) {
    SectionLabel("Device info")
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { viewModel.request(ToolboxCommands.deviceProps()) }, modifier = Modifier.testTag("toolbox_device_props")) { Text("Properties") }
        OutlinedButton(onClick = { viewModel.request(ToolboxCommands.battery()) }) { Text("Battery") }
        OutlinedButton(onClick = { viewModel.request(ToolboxCommands.storage()) }) { Text("Storage") }
    }
    Text(
        "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun RebootTab(viewModel: SystemToolboxViewModel) {
    SectionLabel("Reboot")
    Text("Each option asks first and shows the exact command.", style = MaterialTheme.typography.bodySmall)
    RebootTarget.entries.forEach { t ->
        OutlinedButton(onClick = { viewModel.request(ToolboxCommands.reboot(t)) }, modifier = Modifier.fillMaxWidth()) { Text(t.label) }
    }
}
