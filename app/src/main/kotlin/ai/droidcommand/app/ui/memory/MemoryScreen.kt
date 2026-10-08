package ai.droidcommand.app.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
 * Knowledge-graph K4: the "what the app remembers" screen — view every stored entity and delete one
 * or all of them (the owner-approved view/delete control). Layout idioms (a scaffold with a top bar,
 * a scrolling list of cards, a per-row delete button, a confirm dialog for the destructive clear,
 * and an explicit empty state) are adapted from opendroid's MemoryScreen (Apache-2.0); this is
 * original code against this repo's own types, not copied source. Compiles; never run on a device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(viewModel: MemoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory") },
                actions = {
                    if (state.entities.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Facts the app has saved from your conversations. They are added only when you tap " +
                    "\"Remember this chat\", and are sent to the AI as reference notes. Delete anything you " +
                    "do not want kept.",
                style = MaterialTheme.typography.bodySmall,
            )
            when {
                state.loading -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                state.entities.isEmpty() -> Text("Nothing saved yet.", style = MaterialTheme.typography.bodyMedium)
                else -> LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.entities, key = { it.id }) { entity ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(entity.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(entity.type.name, style = MaterialTheme.typography.labelSmall)
                                    if (entity.properties.isNotEmpty()) {
                                        Text(
                                            entity.properties.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}: ${it.value}" },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                IconButton(onClick = { viewModel.delete(entity.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${entity.label}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete everything?") },
            text = { Text("This permanently deletes every saved fact. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    confirmClear = false
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
