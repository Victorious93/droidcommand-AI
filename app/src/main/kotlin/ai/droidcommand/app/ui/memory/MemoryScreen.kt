package ai.droidcommand.app.ui.memory

import ai.droidcommand.agent.Entity
import ai.droidcommand.agent.EntityType
import ai.droidcommand.app.memory.MemoryController
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.theme.AccentCyan
import ai.droidcommand.app.ui.theme.AccentRed
import ai.droidcommand.app.ux.ScreenHelpCatalog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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

/**
 * Knowledge-graph phase K4's view/delete screen (`docs/KNOWLEDGE_GRAPH_PHASE_SCOPE.md`). Compiles; never
 * run on a device or emulator — layout, scrolling and the dialog's real behavior are unverified beyond
 * compilation, the same caveat every other screen in this module already carries.
 *
 * UI idioms (a scaffold-free card list, per-row delete, a confirm dialog gating the one destructive
 * action, an explicit empty state) follow `Victorious93/opendroid`'s `MemoryScreen` at the owner's
 * request — adapted against this app's own types and this module's existing plain-Column convention
 * (see [ai.droidcommand.app.ui.tools.ToolsScreen]/[ai.droidcommand.app.ui.settings.SettingsScreen], which
 * use no `Scaffold`/`TopAppBar`); no source was copied.
 */
@Composable
fun MemoryScreen(viewModel: MemoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var confirmingClear by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Entity?>(null) }
    var draft by remember { mutableStateOf("") }
    var draftType by remember { mutableStateOf(EntityType.CONCEPT) }
    var draftProps by remember { mutableStateOf("") }
    var typeMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitleWithHelp("Memory", ScreenHelpCatalog.MEMORY)
        Text(
            "What DroidCommand AI has remembered from your conversations. Edit or delete anything " +
                "that is wrong or you don't want kept, or clear everything.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            state.loading -> CircularProgressIndicator()
            state.entities.isEmpty() -> EmptyMemoryState()
            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.entities, key = { it.id }) { entity ->
                        MemoryRow(
                            entity,
                            onEdit = {
                                draft = entity.label
                                draftType = entity.type
                                draftProps = MemoryController.formatProperties(entity.properties)
                                editing = entity
                            },
                            onDelete = { viewModel.delete(entity.id) },
                        )
                    }
                }
                OutlinedButton(
                    onClick = { confirmingClear = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                ) {
                    Text("Clear all")
                }
            }
        }
    }

    editing?.let { target ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit memory") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Label") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box {
                        OutlinedButton(onClick = { typeMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Type: ${draftType.name}")
                        }
                        DropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                            EntityType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.name) },
                                    onClick = {
                                        draftType = type
                                        typeMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = draftProps,
                        onValueChange = { draftProps = it },
                        label = { Text("Properties (key=value, one per line)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        viewModel.update(target.id, draft, draftType, MemoryController.parseProperties(draftProps))
                        editing = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Clear all memory?") },
            text = { Text("This permanently deletes everything remembered from every conversation. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    viewModel.clearAll()
                }) { Text("Clear all") }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyMemoryState() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Nothing remembered yet.", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Use \"Remember this chat\" in a conversation to save facts here.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MemoryRow(entity: Entity, onEdit: () -> Unit, onDelete: () -> Unit) {
    DroidCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entity.type.name, style = MaterialTheme.typography.labelSmall, color = AccentCyan)
                Text(entity.label, style = MaterialTheme.typography.bodyLarge)
            }
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDelete) { Text("Delete", color = AccentRed) }
        }
    }
}
