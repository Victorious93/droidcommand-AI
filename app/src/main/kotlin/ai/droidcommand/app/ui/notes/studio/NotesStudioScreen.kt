package ai.droidcommand.app.ui.notes.studio

import ai.droidcommand.notes.studio.ContextFileKind
import ai.droidcommand.notes.studio.ContextTemplates
import ai.droidcommand.notes.studio.FormatAction
import ai.droidcommand.notes.studio.LintSeverity
import ai.droidcommand.notes.studio.MarkdownFormatter
import ai.droidcommand.notes.studio.TextEdit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

private enum class StudioTab(val label: String) { WRITE("Write"), CONTEXT("AI context files"), PROMPTS("Prompt Vault"), LAN("LAN viewer") }

private enum class EditMode { EDIT, SPLIT, PREVIEW }

@Composable
fun NotesStudioScreen(viewModel: NotesStudioViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableStateOf(StudioTab.WRITE) }
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("notes_studio"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Notes Studio", style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioTab.entries.forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) }, modifier = Modifier.testTag("studio_tab_${t.name.lowercase()}")) }
        }
        viewModel.message?.let { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = viewModel::clearMessage) { Text("OK") }
            }
        }
        when (tab) {
            StudioTab.WRITE -> WriteTab(viewModel)
            StudioTab.CONTEXT -> ContextTab(viewModel)
            StudioTab.PROMPTS -> PromptsTab(viewModel)
            StudioTab.LAN -> LanTab(viewModel)
        }
    }
}

private val TOOLBAR = listOf(
    FormatAction.BOLD to "B", FormatAction.ITALIC to "I", FormatAction.HEADING to "H", FormatAction.BULLET to "•",
    FormatAction.NUMBERED to "1.", FormatAction.CHECKBOX to "☐", FormatAction.QUOTE to "❝", FormatAction.CODE to "`",
    FormatAction.CODE_BLOCK to "{ }", FormatAction.LINK to "Link", FormatAction.MERMAID to "Diagram",
)

@Composable
private fun WriteTab(vm: NotesStudioViewModel) {
    var value by remember { mutableStateOf(TextFieldValue("# New note\n\n```mermaid\nflowchart LR\n  Idea --> Draft --> Done\n```\n")) }
    var path by rememberSaveable { mutableStateOf("Untitled.md") }
    var mode by rememberSaveable { mutableStateOf(EditMode.SPLIT) }
    LaunchedEffect(Unit) { vm.loadNotes() }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (vm.notes.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                vm.notes.take(100).forEach { n ->
                    TextButton(onClick = {
                        vm.open(n) { t ->
                            value = TextFieldValue(t)
                            path = n
                        }
                    }) { Text(n, fontSize = 12.sp) }
                }
            }
        }
        OutlinedTextField(path, { path = it }, label = { Text("Note path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EditMode.entries.forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
        }
        if (mode != EditMode.PREVIEW) {
            Row(Modifier.horizontalScroll(rememberScrollState()).testTag("format_toolbar"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TOOLBAR.forEach { (action, label) ->
                    OutlinedButton(onClick = {
                        val r = MarkdownFormatter.apply(TextEdit(value.text, value.selection.min, value.selection.max), action)
                        value = TextFieldValue(r.text, TextRange(r.start, r.end))
                    }) { Text(label) }
                }
            }
            OutlinedTextField(
                value,
                { value = it },
                modifier = Modifier.fillMaxWidth().height(if (mode == EditMode.SPLIT) 220.dp else 420.dp).testTag("studio_editor"),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
        if (mode != EditMode.EDIT) {
            Card(Modifier.fillMaxWidth()) { MermaidWebView(value.text, Modifier.fillMaxWidth().height(if (mode == EditMode.SPLIT) 300.dp else 520.dp)) }
        }
        Button(onClick = { vm.save(path, value.text) }) { Text("Save to vault") }
    }
}

@Composable
private fun ContextTab(vm: NotesStudioViewModel) {
    val clipboard = LocalClipboardManager.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> uri?.let(vm::exportTo) }
    var showRaw by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Build and check the files AI coding tools read.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ContextFileKind.entries.forEach { k -> FilterChip(selected = vm.kind == k, onClick = { vm.chooseKind(k) }, label = { Text(k.label) }) }
        }
        Text("Templates", fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ContextTemplates.all.forEach { t -> OutlinedButton(onClick = { vm.applyTemplate(t) }) { Text(t.name) } }
        }
        OutlinedTextField(vm.document.title, vm::setTitle, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        vm.document.sections.forEachIndexed { i, s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(s.title, { vm.updateSection(i, s.copy(title = it)) }, label = { Text("Section") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(s.body, { vm.updateSection(i, s.copy(body = it)) }, label = { Text("Content") }, modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(onClick = { vm.moveSection(i, i - 1) }, enabled = i > 0) { Text("Up") }
                        TextButton(onClick = { vm.moveSection(i, i + 1) }, enabled = i < vm.document.sections.lastIndex) { Text("Down") }
                        TextButton(onClick = { vm.removeSection(i) }) { Text("Remove") }
                    }
                }
            }
        }
        OutlinedButton(onClick = vm::addSection) { Text("Add section") }
        val issues = vm.issues
        Text(if (issues.isEmpty()) "Lint: no problems" else "Lint: ${issues.size} finding(s)", fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("context_lint"))
        issues.forEach { issue ->
            val color = when (issue.severity) {
                LintSeverity.ERROR -> MaterialTheme.colorScheme.error
                LintSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                LintSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text("${issue.severity} line ${issue.line}: ${issue.message}", color = color, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { export.launch(vm.kind.exportPath.substringAfterLast('/')) }) { Text("Export file…") }
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.rendered)) }) { Text("Copy") }
            OutlinedButton(onClick = vm::saveContextToVault) { Text("Save to vault") }
            OutlinedButton(onClick = { clipboard.getText()?.text?.let(vm::importText) }) { Text("Import from clipboard") }
        }
        Text("Goes in your repo at ${vm.kind.exportPath}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Hide file" else "Show file") }
        if (showRaw) Card { SelectionContainer { Text(vm.rendered, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(8.dp)) } }
    }
}

@Composable
private fun PromptsTab(vm: NotesStudioViewModel) {
    val clipboard = LocalClipboardManager.current
    var title by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadPrompts() }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(vm.promptQuery, vm::setQuery, label = { Text("Search prompts") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("prompt_search"))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = vm.promptCategory == null, onClick = { vm.setCategory(null) }, label = { Text("All") })
            vm.categories.forEach { c -> FilterChip(selected = vm.promptCategory == c, onClick = { vm.setCategory(c) }, label = { Text(c) }) }
        }
        if (vm.promptList.isEmpty()) Text("No saved prompts yet.", style = MaterialTheme.typography.bodySmall)
        vm.promptList.forEach { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Text(p.title, fontWeight = FontWeight.SemiBold)
                    Text(p.category, style = MaterialTheme.typography.labelSmall)
                    Text(p.body.take(300), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(p.body)) }) { Text("Copy") }
                        TextButton(onClick = { vm.deletePrompt(p.id) }) { Text("Delete") }
                    }
                }
            }
        }
        Text("Add a prompt", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(category, { category = it }, label = { Text("Category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it }, label = { Text("Prompt text") }, modifier = Modifier.fillMaxWidth().height(140.dp))
        Button(onClick = {
            vm.savePrompt(title, category, body)
            title = ""
            body = ""
        }) { Text("Save prompt") }
    }
}

@Composable
private fun LanTab(vm: NotesStudioViewModel) {
    var password by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Lets a computer on the same Wi-Fi read your vault in a browser. Read-only, password-protected, LAN addresses only. " +
                "Off by default and stops when you leave this screen.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("Password (8+ characters)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !vm.lanRunning,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LAN viewer", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Switch(checked = vm.lanRunning, onCheckedChange = { on -> if (on) vm.startLan(password) else vm.stopLan() }, modifier = Modifier.testTag("lan_toggle"))
        }
        vm.lanUrl?.let { Text("Open $it — user name: vault", fontFamily = FontFamily.Monospace) }
    }
}
