package ai.droidcommand.app.ui.notes

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.theme.AccentCyan
import ai.droidcommand.app.ui.theme.TextPrimary
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.notes.InstalledPlugin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private const val SAMPLE = """id: my-plugin
name: My plugin
description: A custom text transform. This is not an Obsidian plugin.
op: prepend|> 
op: replace|TODO|DONE
"""

/**
 * Install, enable and remove user plugins. The box takes a `.dcplugin` descriptor (prepend, append,
 * literal replace only). No plugin ships with the app, and Obsidian's catalog is not consulted.
 */
@Composable
fun NotePluginsScreen(onBack: () -> Unit, viewModel: NotesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var source by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        ScreenTitle("Plugins")
        Text(
            "Custom plugins are text descriptors you install yourself. They can prepend, append, or replace literal text. They cannot run code, read files, or use the network. None are bundled.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        state.status?.let { Text(it, color = AccentCyan, style = MaterialTheme.typography.bodySmall) }
        if (state.plugins.isEmpty()) {
            Text("No plugins installed.", color = TextPrimary)
        }
        state.plugins.forEach { plugin -> PluginRow(plugin, viewModel) }
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Install a plugin", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    label = { Text(".dcplugin text") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.installPlugin(source)
                            source = ""
                        },
                        enabled = source.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Install") }
                    TextButton(onClick = { source = SAMPLE }, modifier = Modifier.weight(1f)) { Text("Insert example") }
                }
            }
        }
    }
}

@Composable
private fun PluginRow(plugin: InstalledPlugin, viewModel: NotesViewModel) {
    DroidCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(plugin.displayName, color = TextPrimary)
                Text("${plugin.id} · ${plugin.opCount} operations", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                if (plugin.description.isNotBlank()) {
                    Text(plugin.description, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Switch(checked = plugin.enabled, onCheckedChange = { viewModel.setPluginEnabled(plugin.id, it) })
        }
        TextButton(onClick = { viewModel.uninstallPlugin(plugin.id) }) { Text("Remove") }
    }
}
