package ai.droidcommand.app.ui.vault

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.theme.AccentSky
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.vault.MarkdownBlock
import ai.droidcommand.vault.PluginEnablement
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

/** Folders, a markdown editor, a preview, and declarative plugins. No Obsidian plugins are bundled. */
@Composable
fun VaultScreen(viewModel: VaultViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var folderName by remember { mutableStateOf("") }
    var pluginId by remember { mutableStateOf("") }
    var pluginName by remember { mutableStateOf("") }
    var pluginVersion by remember { mutableStateOf("1") }
    var pluginPrefix by remember { mutableStateOf("") }
    var pluginTemplate by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Vault")
        Text(state.sandbox, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        state.status?.let { Text(it, color = AccentSky, style = MaterialTheme.typography.bodySmall) }

        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Folders", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.folderId == null, onClick = { viewModel.selectFolder(null) }, label = { Text("Root") })
                }
                state.folders.forEach { folder ->
                    FilterChip(
                        selected = state.folderId == folder.id,
                        onClick = { viewModel.selectFolder(folder.id) },
                        label = { Text(folder.id) },
                    )
                }
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("New folder in the current folder") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(onClick = {
                    viewModel.createFolder(folderName)
                    folderName = ""
                }, enabled = folderName.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Create folder") }
                if (state.folderId != null) {
                    OutlinedButton(onClick = viewModel::deleteFolder, modifier = Modifier.fillMaxWidth()) { Text("Delete empty folder") }
                }
            }
        }

        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Notes", style = MaterialTheme.typography.titleMedium)
                if (state.notes.isEmpty()) {
                    Text("No notes in this folder.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                state.notes.forEach { note ->
                    FilterChip(
                        selected = state.selectedNoteId == note.id,
                        onClick = { viewModel.selectNote(note.id) },
                        label = { Text(note.title) },
                    )
                }
                OutlinedTextField(
                    value = state.title,
                    onValueChange = { viewModel.edit(it, state.markdown) },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (state.preview) {
                    PreviewBlocks(viewModel.previewBlocks())
                } else {
                    OutlinedTextField(
                        value = state.markdown,
                        onValueChange = { viewModel.edit(state.title, it) },
                        label = { Text("Markdown") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !state.preview, onClick = { viewModel.setPreview(false) }, label = { Text("Edit") })
                    FilterChip(selected = state.preview, onClick = { viewModel.setPreview(true) }, label = { Text("Preview") })
                }
                Button(onClick = viewModel::createNote, modifier = Modifier.fillMaxWidth()) { Text("New note from title") }
                Button(onClick = viewModel::save, enabled = state.selectedNoteId != null, modifier = Modifier.fillMaxWidth()) { Text("Save") }
                OutlinedButton(onClick = viewModel::deleteSelected, enabled = state.selectedNoteId != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete note")
                }
            }
        }

        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Plugins", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Install a manifest you write. Enabling it can prefix the preview, fill a new note, and replace literal text. It cannot run code.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                state.plugins.forEach { plugin ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(plugin.manifest?.name ?: plugin.id)
                            Text(plugin.problem ?: plugin.switchLabel(), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        if (plugin.manifest != null) {
                            Switch(
                                checked = plugin.enablement == PluginEnablement.ENABLED,
                                onCheckedChange = { viewModel.setPluginEnabled(plugin.id, it) },
                            )
                        }
                        OutlinedButton(onClick = { viewModel.uninstallPlugin(plugin.id) }) { Text("Remove") }
                    }
                }
                OutlinedTextField(value = pluginId, onValueChange = { pluginId = it }, label = { Text("Id") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = pluginName, onValueChange = { pluginName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = pluginVersion, onValueChange = { pluginVersion = it }, label = { Text("Version") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = pluginPrefix, onValueChange = { pluginPrefix = it }, label = { Text("Preview prefix") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = pluginTemplate, onValueChange = { pluginTemplate = it }, label = { Text("Note template ({{title}})") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        viewModel.installPlugin(pluginId, pluginName, pluginVersion, pluginPrefix, pluginTemplate)
                        pluginId = ""
                        pluginName = ""
                        pluginPrefix = ""
                        pluginTemplate = ""
                    },
                    enabled = pluginId.isNotBlank() && pluginName.isNotBlank() && pluginVersion.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Install plugin") }
            }
        }
    }
}

@Composable
private fun PreviewBlocks(blocks: List<MarkdownBlock>) {
    if (blocks.isEmpty()) {
        Text("Nothing to preview.", color = TextSecondary)
        return
    }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> Text(block.text, fontWeight = FontWeight.Bold, fontSize = (22 - block.level).sp, color = AccentSky)
                    is MarkdownBlock.Paragraph -> Text(block.text)
                    is MarkdownBlock.Bullet -> Text("• ${block.text}")
                    is MarkdownBlock.Code -> Text(block.text, fontFamily = FontFamily.Monospace, color = TextSecondary)
                }
            }
        }
    }
}
