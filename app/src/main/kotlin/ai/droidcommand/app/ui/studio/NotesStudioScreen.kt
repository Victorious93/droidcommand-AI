package ai.droidcommand.app.ui.studio

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.notes.studio.AiContextKind
import ai.droidcommand.notes.studio.ContextSection
import ai.droidcommand.notes.studio.FormatAction
import ai.droidcommand.notes.studio.LanVaultServer
import ai.droidcommand.notes.studio.LintSeverity
import ai.droidcommand.notes.studio.MarkdownFormatting
import ai.droidcommand.notes.studio.TextEdit
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private val STUDIO_TABS = listOf("AI context", "Prompts", "Editor", "Share on Wi-Fi")

@Composable
fun NotesStudioScreen(viewModel: NotesStudioViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp).testTag("notes_studio_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenTitleWithHelp("Notes Studio", ScreenHelpCatalog.NOTES_STUDIO)
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            STUDIO_TABS.forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (tab) {
                0 -> ContextTab(viewModel) { toast = it }
                1 -> PromptsTab(viewModel) { toast = it }
                2 -> EditorTab(viewModel) { toast = it }
                else -> LanTab(viewModel) { toast = it }
            }
        }
    }
    toast?.let { m ->
        AlertDialog(onDismissRequest = { toast = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { toast = null }) { Text("OK") } })
    }
}

@Composable
private fun ContextTab(viewModel: NotesStudioViewModel, show: (String) -> Unit) {
    var structured by rememberSaveable { mutableStateOf(true) }
    var project by rememberSaveable { mutableStateOf("") }
    var newSection by rememberSaveable { mutableStateOf("") }
    SectionLabel("AI context file")
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AiContextKind.entries.forEach { k ->
            FilterChip(selected = viewModel.kind == k, onClick = { viewModel.selectKind(k) }, label = { Text(k.displayName) }, modifier = Modifier.testTag("studio_kind_${k.name}"))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value = project, onValueChange = { project = it }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { viewModel.applyTemplate(project) }) { Text("Template") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = structured, onClick = { structured = true }, label = { Text("Sections") })
        FilterChip(selected = !structured, onClick = { structured = false }, label = { Text("Raw") })
    }
    if (structured) {
        val doc = viewModel.document
        OutlinedTextField(value = doc.title, onValueChange = { viewModel.editDocument(doc.copy(title = it)) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        doc.sections.forEachIndexed { i, section ->
            DroidCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = section.title,
                            onValueChange = { viewModel.editDocument(doc.withSection(i, section.copy(title = it))) },
                            label = { Text("Heading") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.editDocument(doc.removeSection(i)) }) { Text("Remove") }
                    }
                    OutlinedTextField(
                        value = section.body,
                        onValueChange = { viewModel.editDocument(doc.withSection(i, ContextSection(section.title, it))) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value = newSection, onValueChange = { newSection = it }, label = { Text("New section") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                if (newSection.isNotBlank()) viewModel.editDocument(doc.addSection(newSection))
                newSection = ""
            }) { Text("Add") }
        }
    } else {
        OutlinedTextField(
            value = viewModel.contextText,
            onValueChange = viewModel::editContext,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).testTag("studio_context_raw"),
        )
    }
    SectionLabel("Checks")
    val issues = viewModel.issues
    DroidCard(modifier = Modifier.testTag("studio_lint")) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (issues.isEmpty()) Text("No problems found.")
            issues.forEach { issue ->
                val color = when (issue.severity) {
                    LintSeverity.ERROR -> MaterialTheme.colorScheme.error
                    LintSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                    LintSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text((if (issue.line > 0) "Line ${issue.line}: " else "") + issue.message, color = color, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { show(viewModel.saveContext()) }, modifier = Modifier.testTag("studio_context_save")) { Text("Save to vault") }
        OutlinedButton(onClick = { show(viewModel.exportContext()) }) { Text("Export file") }
    }
}

@Composable
private fun PromptsTab(viewModel: NotesStudioViewModel, show: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var category by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { viewModel.refreshPrompts() }
    SectionLabel("Prompt Vault")
    OutlinedTextField(
        value = viewModel.promptQuery,
        onValueChange = {
            viewModel.promptQuery = it
            viewModel.refreshPrompts()
        },
        label = { Text("Search prompts") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("studio_prompt_search"),
    )
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = viewModel.promptCategory == null, onClick = {
            viewModel.promptCategory = null
            viewModel.refreshPrompts()
        }, label = { Text("All") })
        viewModel.categories.forEach { c ->
            FilterChip(selected = viewModel.promptCategory == c, onClick = {
                viewModel.promptCategory = c
                viewModel.refreshPrompts()
            }, label = { Text(c) })
        }
    }
    viewModel.promptList.forEach { p ->
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.title, fontWeight = FontWeight.Medium)
                Text(p.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(p.body.take(300), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(p.body))
                        show("Copied")
                    }) { Text("Copy") }
                    TextButton(onClick = {
                        category = p.category
                        title = p.title
                        body = p.body
                    }) { Text("Edit") }
                    TextButton(onClick = { viewModel.deletePrompt(p) }) { Text("Delete") }
                }
            }
        }
    }
    SectionLabel("Save a prompt")
    OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Prompt") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
    OutlinedButton(onClick = { show(viewModel.savePrompt(category, title, body)) }) { Text("Save prompt") }
}

private val TOOLBAR = listOf(
    FormatAction.BOLD to "B", FormatAction.ITALIC to "I", FormatAction.STRIKE to "S", FormatAction.CODE to "`",
    FormatAction.H1 to "H1", FormatAction.H2 to "H2", FormatAction.H3 to "H3", FormatAction.BULLET to "•",
    FormatAction.NUMBERED to "1.", FormatAction.TASK to "☐", FormatAction.QUOTE to "❝", FormatAction.LINK to "Link",
    FormatAction.CODE_BLOCK to "{ }", FormatAction.MERMAID to "Diagram", FormatAction.TABLE to "Table", FormatAction.RULE to "—",
)

@Composable
private fun EditorTab(viewModel: NotesStudioViewModel, show: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(viewModel.editorText)) }
    if (field.text != viewModel.editorText) field = TextFieldValue(viewModel.editorText, TextRange(viewModel.editorText.length))
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var path by rememberSaveable { mutableStateOf("Untitled.md") }
    SectionLabel("Editor")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Edit", "Split", "Preview").forEachIndexed { i, label ->
            FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(label) }, modifier = Modifier.testTag("studio_editor_mode_$i"))
        }
    }
    if (mode != 2) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).testTag("studio_toolbar"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TOOLBAR.forEach { (action, label) ->
                TextButton(onClick = {
                    val out = MarkdownFormatting.apply(action, TextEdit(field.text, field.selection.start, field.selection.end))
                    viewModel.editorText = out.text
                    field = TextFieldValue(out.text, TextRange(out.start, out.end))
                }) { Text(label) }
            }
        }
        OutlinedTextField(
            value = field,
            onValueChange = {
                field = it
                viewModel.editorText = it.text
            },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().heightIn(min = if (mode == 0) 320.dp else 180.dp).testTag("studio_editor"),
        )
    }
    if (mode != 0) {
        DroidCard(modifier = Modifier.testTag("studio_preview")) {
            MermaidPreview(viewModel.editorText, isSystemInDarkTheme(), Modifier.fillMaxWidth().height(if (mode == 2) 480.dp else 260.dp))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("Vault path") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { show(viewModel.openEditor(path)) }) { Text("Open") }
        OutlinedButton(onClick = { show(viewModel.saveEditor(path)) }) { Text("Save") }
    }
}

@Composable
private fun LanTab(viewModel: NotesStudioViewModel, show: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    SectionLabel("Share on Wi-Fi (read-only)")
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Off until you start it. Other devices on the same Wi-Fi can read your notes in a browser after entering the password. " +
                    "Nothing can be changed from there, devices outside your local network are refused, and sharing stops when you leave this screen.",
                style = MaterialTheme.typography.bodySmall,
            )
            val url = viewModel.lanUrl
            if (url == null) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (at least ${LanVaultServer.MIN_PASSWORD} characters)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("studio_lan_password"),
                )
                OutlinedButton(
                    onClick = { show(viewModel.startLan(password)) },
                    enabled = password.length >= LanVaultServer.MIN_PASSWORD,
                    modifier = Modifier.testTag("studio_lan_start"),
                ) { Text("Start sharing") }
            } else {
                Text("Sharing at $url", fontWeight = FontWeight.Medium, modifier = Modifier.testTag("studio_lan_url"))
                OutlinedButton(onClick = viewModel::stopLan) { Text("Stop sharing") }
            }
        }
    }
}
