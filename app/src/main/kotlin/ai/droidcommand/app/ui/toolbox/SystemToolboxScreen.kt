package ai.droidcommand.app.ui.toolbox

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

private enum class Section(val label: String) {
    TERMINAL("Terminal"),
    APPS("Apps"),
    PERMISSIONS("Permissions"),
    LOGCAT("Logcat"),
    SETTINGS("Settings"),
    REBOOT("Reboot"),
    FILES("Files"),
    DEVICE("Device"),
}

/**
 * Advanced → System Toolbox. Uses the existing Shizuku connection, or `su` for root-only parts.
 * Nothing runs until a button is tapped, and anything that changes the system first shows the
 * exact command in a dialog.
 */
@Composable
fun SystemToolboxScreen(viewModel: SystemToolboxViewModel = hiltViewModel()) {
    var section by rememberSaveable { mutableStateOf(Section.APPS) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("system_toolbox"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("System Toolbox", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Runs through Shizuku when it is connected, otherwise through root (su). Nothing runs until you tap. " +
                "Anything that changes your phone shows the exact command first.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Let the AI use the toolbox", fontWeight = FontWeight.SemiBold)
                Text("Off by default. Even when on, it asks you every time. No terminal or reboot.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = viewModel.aiEnabled, onCheckedChange = viewModel::requestAiGate, modifier = Modifier.testTag("toolbox_ai_gate"))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Section.entries.forEach { s -> FilterChip(selected = section == s, onClick = { section = s }, label = { Text(s.label) }) }
        }
        if (viewModel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when (section) {
            Section.TERMINAL -> TerminalSection(viewModel)
            Section.APPS -> AppsSection(viewModel)
            Section.PERMISSIONS -> PermissionsSection(viewModel)
            Section.LOGCAT -> LogcatSection(viewModel)
            Section.SETTINGS -> SettingsSection(viewModel)
            Section.REBOOT -> RebootSection(viewModel)
            Section.FILES -> FilesSection(viewModel)
            Section.DEVICE -> DeviceSection(viewModel)
        }
        if (viewModel.output.isNotBlank()) OutputBox(viewModel.output)
    }

    viewModel.pending?.let { p ->
        AlertDialog(
            onDismissRequest = viewModel::cancel,
            title = { Text("Run ${p.command.title}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This exact command will run:")
                    Card { SelectionContainer { Text(p.shownText, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp).testTag("toolbox_confirm_command")) } }
                }
            },
            confirmButton = { Button(onClick = viewModel::confirm, modifier = Modifier.testTag("toolbox_confirm_run")) { Text("Run") } },
            dismissButton = { TextButton(onClick = viewModel::cancel) { Text("Cancel") } },
        )
    }
    if (viewModel.confirmAiGate) {
        AlertDialog(
            onDismissRequest = { viewModel.confirmAi(false) },
            title = { Text("Let the AI use the toolbox?") },
            text = { Text("The AI could ask to force stop, freeze, uninstall or clear apps, change permissions and settings. You will still approve every single call.") },
            confirmButton = { Button(onClick = { viewModel.confirmAi(true) }) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = { viewModel.confirmAi(false) }) { Text("Keep off") } },
        )
    }
}

@Composable
private fun OutputBox(text: String) {
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(text.take(40_000), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun TerminalSection(vm: SystemToolboxViewModel) {
    var line by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(line, { line = it }, label = { Text("Command") }, modifier = Modifier.fillMaxWidth().testTag("toolbox_terminal_input"), singleLine = true)
    Text("Every terminal command is shown to you before it runs.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { vm.request({ ToolboxCommands.terminal(line) }) }) { Text("Run…") }
}

@Composable
private fun PackageField(pkg: String, onChange: (String) -> Unit) {
    OutlinedTextField(pkg, onChange, label = { Text("Package (e.g. com.example.app)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}

@Composable
private fun AppsSection(vm: SystemToolboxViewModel) {
    var pkg by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(PackageFilter.USER) }
    var apk by rememberSaveable { mutableStateOf("/sdcard/Download/") }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PackageFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.name.lowercase()) }) }
    }
    OutlinedButton(onClick = { vm.loadPackages(filter) }) { Text("List apps") }
    vm.packages.take(300).forEach { p -> TextButton(onClick = { pkg = p }) { Text(p, fontSize = 12.sp) } }
    PackageField(pkg) { pkg = it }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.request({ ToolboxCommands.forceStop(pkg) }) }) { Text("Force stop") }
        OutlinedButton(onClick = { vm.request({ ToolboxCommands.disable(pkg) }) }) { Text("Freeze") }
        OutlinedButton(onClick = { vm.request({ ToolboxCommands.enable(pkg) }) }) { Text("Enable") }
        OutlinedButton(onClick = { vm.request({ ToolboxCommands.clearData(pkg) }) }) { Text("Clear data") }
        OutlinedButton(onClick = { vm.request({ ToolboxCommands.uninstall(pkg) }) }) { Text("Uninstall") }
        OutlinedButton(onClick = { vm.backupApk(pkg) }) { Text("Back up APK") }
    }
    OutlinedTextField(apk, { apk = it }, label = { Text("APK file to install (absolute path)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedButton(onClick = { vm.request({ ToolboxCommands.install(apk) }) }) { Text("Install APK…") }
}

@Composable
private fun PermissionsSection(vm: SystemToolboxViewModel) {
    var pkg by rememberSaveable { mutableStateOf("") }
    PackageField(pkg) { pkg = it }
    OutlinedButton(onClick = { vm.loadPermissions(pkg) }) { Text("Show permissions") }
    vm.permissions.forEach { (perm, granted) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(perm.removePrefix("android.permission."), Modifier.weight(1f), fontSize = 12.sp)
            Switch(
                checked = granted,
                onCheckedChange = { on ->
                    vm.request({ if (on) ToolboxCommands.grant(pkg, perm) else ToolboxCommands.revoke(pkg, perm) }) { vm.loadPermissions(pkg) }
                },
            )
        }
    }
}

@Composable
private fun LogcatSection(vm: SystemToolboxViewModel) {
    var tag by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableStateOf("I") }
    var query by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(tag, { tag = it }, label = { Text("Tag (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("V", "D", "I", "W", "E").forEach { l -> FilterChip(selected = level == l, onClick = { level = l }, label = { Text(l) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.loadLog(tag, level) }) { Text("Capture") }
        val filtered = remember(vm.log, query) { ToolboxCommands.filterLog(vm.log, query) }
        OutlinedButton(onClick = { vm.saveLog(filtered) }, enabled = vm.log.isNotBlank()) { Text("Save") }
    }
    OutlinedTextField(query, { query = it }, label = { Text("Filter") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    if (vm.log.isNotBlank()) OutputBox(ToolboxCommands.filterLog(vm.log, query).takeLast(40_000))
}

@Composable
private fun SettingsSection(vm: SystemToolboxViewModel) {
    var ns by rememberSaveable { mutableStateOf(SettingsNamespace.GLOBAL) }
    var key by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsNamespace.entries.forEach { n -> FilterChip(selected = ns == n, onClick = { ns = n }, label = { Text(n.wire) }) }
    }
    OutlinedTextField(key, { key = it }, label = { Text("Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(value, { value = it }, label = { Text("New value") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.request({ ToolboxCommands.settingsGet(ns, key) }) }) { Text("Get") }
        OutlinedButton(onClick = { vm.request({ ToolboxCommands.settingsPut(ns, key, value) }) }) { Text("Put…") }
        OutlinedButton(onClick = { vm.request({ ToolboxCommands.settingsList(ns) }) }) { Text("List all") }
    }
}

@Composable
private fun RebootSection(vm: SystemToolboxViewModel) {
    Text("Each option asks first and shows the command.", style = MaterialTheme.typography.bodySmall)
    RebootTarget.entries.forEach { t -> OutlinedButton(onClick = { vm.request({ ToolboxCommands.reboot(t) }) }, modifier = Modifier.fillMaxWidth()) { Text(t.label + "…") } }
}

@Composable
private fun FilesSection(vm: SystemToolboxViewModel) {
    var path by rememberSaveable { mutableStateOf("/") }
    Text("Read-only root file browser (needs root).", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(path, { path = it }, label = { Text("Path") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.request({ ToolboxCommands.listDir(path) }) }) { Text("List") }
        OutlinedButton(onClick = { vm.request({ ToolboxCommands.readFile(path) }) }) { Text("Open file") }
        OutlinedButton(onClick = { path = path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" } }) { Text("Up") }
    }
}

@Composable
private fun DeviceSection(vm: SystemToolboxViewModel) {
    Text("Read-only device details.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.request({ ToolboxCommands.getprop() }) }) { Text("Props") }
        OutlinedButton(onClick = { vm.request({ ToolboxCommands.battery() }) }) { Text("Battery") }
        OutlinedButton(onClick = { vm.request({ ToolboxCommands.storage() }) }) { Text("Storage") }
    }
}
