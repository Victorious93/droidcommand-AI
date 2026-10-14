package ai.droidcommand.app.ui.notes

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.notes.MarkdownBlock
import ai.droidcommand.notes.NotePlugin
import ai.droidcommand.notes.NoteVault
import ai.droidcommand.notes.PluginStore
import ai.droidcommand.notes.VaultEntry
import ai.droidcommand.notes.renderMarkdown
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@HiltViewModel
class NotesViewModel @Inject constructor(@ApplicationContext context: Context) : ViewModel() {
    private val root = context.filesDir.toPath().resolve("notes-vault")
    private val vault = NoteVault(root)
    private val plugins = PluginStore(root)

    var folder by mutableStateOf("")
        private set
    var entries by mutableStateOf(vault.list())
        private set
    var openPath by mutableStateOf<String?>(null)
        private set
    var body by mutableStateOf("")
        private set
    var preview by mutableStateOf(false)
        private set
    var pluginText by mutableStateOf("")
    var installed by mutableStateOf(plugins.list())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun open(entry: VaultEntry) {
        error = null
        if (entry.folder) {
            folder = entry.relativePath
            openPath = null
            entries = vault.list(folder)
        } else {
            openPath = entry.relativePath
            body = vault.read(entry.relativePath)
            preview = false
        }
    }

    fun up() {
        error = null
        openPath = null
        folder = folder.substringBeforeLast('/', "")
        entries = vault.list(folder)
    }

    fun newFolder(name: String) {
        val relative = child(name)
        try {
            vault.createFolder(relative)
            entries = vault.list(folder)
            error = null
        } catch (e: IllegalArgumentException) {
            error = e.message
        }
    }

    fun newNote(name: String) {
        val file = if (name.endsWith(".md")) name else "$name.md"
        val relative = child(file)
        try {
            vault.write(relative, "")
            entries = vault.list(folder)
            open(VaultEntry(file, relative, folder = false))
        } catch (e: IllegalArgumentException) {
            error = e.message
        }
    }

    fun edit(text: String) {
        body = text
    }

    fun save() {
        val path = openPath ?: return
        vault.write(path, body)
        error = null
    }

    fun togglePreview() {
        preview = !preview
    }

    fun previewBlocks(): List<MarkdownBlock> = renderMarkdown(plugins.apply(body))

    fun installPlugin() {
        try {
            plugins.install(pluginText)
            pluginText = ""
            installed = plugins.list()
            error = null
        } catch (e: IllegalArgumentException) {
            error = e.message
        }
    }

    fun setPluginEnabled(id: String, enabled: Boolean) {
        plugins.setEnabled(id, enabled)
        installed = plugins.list()
    }

    fun removePlugin(id: String) {
        plugins.remove(id)
        installed = plugins.list()
    }

    private fun child(name: String): String = if (folder.isEmpty()) name else "$folder/$name"
}

/**
 * Folders and markdown notes on this device. Preview is headings, bullets, code fences, and paragraphs.
 * Plugins are user-written text manifests. None are installed until the user adds one, and none run as code.
 */
@Composable
fun NotesScreen(viewModel: NotesViewModel = hiltViewModel()) {
    var showPlugins by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
    ) {
        item {
            Text("Notes", color = MaterialTheme.colorScheme.primary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                if (viewModel.folder.isEmpty()) "Vault" else viewModel.folder,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (viewModel.folder.isNotEmpty() || viewModel.openPath != null) {
                    OutlinedButton(onClick = viewModel::up) { Text("Up") }
                }
                OutlinedButton(onClick = { showPlugins = !showPlugins }) { Text(if (showPlugins) "Notes" else "Plugins") }
            }
        }
        viewModel.error?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (showPlugins) {
            item { PluginPanel(viewModel) }
        } else if (viewModel.openPath != null) {
            item { Editor(viewModel) }
        } else {
            item {
                DroidCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draftName,
                            onValueChange = { draftName = it },
                            label = { Text("New name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.newNote(draftName); draftName = "" }, enabled = draftName.isNotBlank()) { Text("Note") }
                            OutlinedButton(onClick = { viewModel.newFolder(draftName); draftName = "" }, enabled = draftName.isNotBlank()) { Text("Folder") }
                        }
                    }
                }
            }
            items(viewModel.entries, key = { it.relativePath }) { entry ->
                DroidCard {
                    TextButton(onClick = { viewModel.open(entry) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (entry.folder) "Folder  ${entry.name}" else entry.name,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Editor(viewModel: NotesViewModel) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(viewModel.openPath.orEmpty(), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::save) { Text("Save") }
                OutlinedButton(onClick = viewModel::togglePreview) { Text(if (viewModel.preview) "Edit" else "Preview") }
            }
            if (viewModel.preview) {
                viewModel.previewBlocks().forEach { block ->
                    when (block) {
                        is MarkdownBlock.Heading -> Text(block.text, fontWeight = FontWeight.Bold, fontSize = (22 - block.level).sp, color = MaterialTheme.colorScheme.primary)
                        is MarkdownBlock.Bullet -> Text("• ${block.text}")
                        is MarkdownBlock.Code -> Text(block.text, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        is MarkdownBlock.Paragraph -> Text(block.text)
                    }
                }
            } else {
                OutlinedTextField(
                    value = viewModel.body,
                    onValueChange = viewModel::edit,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    label = { Text("Markdown") },
                )
            }
        }
    }
}

@Composable
private fun PluginPanel(viewModel: NotesViewModel) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your plugins", style = MaterialTheme.typography.titleMedium)
            Text(
                "None come with the app. A plugin is a text manifest: literal replacements and an optional prefix or suffix. It is not code, and it is saved disabled until you enable it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = viewModel.pluginText,
                onValueChange = { viewModel.pluginText = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                label = { Text("id, name, replace") },
            )
            Button(onClick = viewModel::installPlugin, enabled = viewModel.pluginText.isNotBlank()) { Text("Install") }
            if (viewModel.installed.isEmpty()) {
                Text("No plugins installed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            viewModel.installed.forEach { plugin ->
                PluginRow(
                    plugin,
                    onEnabled = { enabled -> viewModel.setPluginEnabled(plugin.id, enabled) },
                    onRemove = { viewModel.removePlugin(plugin.id) },
                )
            }
        }
    }
}

@Composable
private fun PluginRow(plugin: NotePlugin, onEnabled: (Boolean) -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(plugin.name, fontWeight = FontWeight.Bold)
            Text(plugin.id, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onRemove) { Text("Remove") }
        Switch(checked = plugin.enabled, onCheckedChange = onEnabled)
    }
}
