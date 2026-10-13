package ai.droidcommand.app.ui.notes

import ai.droidcommand.app.navigation.Routes
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.theme.AccentCyan
import ai.droidcommand.app.ui.theme.AccentRed
import ai.droidcommand.app.ui.theme.TextPrimary
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.notes.NoteSummary
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController

/**
 * Markdown vault browser. Folders and notes live in app-private storage. No Obsidian plugin is
 * installed with the app; custom plugins are a separate screen.
 */
@Composable
fun NotesScreen(navController: NavHostController, viewModel: NotesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var dialog by remember { mutableStateOf<NoteDialog?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Notes")
        Text(
            "Markdown notes on this device. The default install includes no Obsidian plugins.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        state.status?.let { Text(it, color = AccentCyan, style = MaterialTheme.typography.bodySmall) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.folders) { folder ->
                val label = if (folder.isEmpty()) "Vault" else folder
                FilterChip(selected = folder == state.folder, onClick = { viewModel.selectFolder(folder) }, label = { Text(label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { dialog = NoteDialog.NOTE }, modifier = Modifier.weight(1f)) { Text("New note") }
            OutlinedButton(onClick = { dialog = NoteDialog.FOLDER }, modifier = Modifier.weight(1f)) { Text("New folder") }
        }
        OutlinedButton(onClick = { navController.navigate(Routes.NOTE_PLUGINS) }, modifier = Modifier.fillMaxWidth()) {
            Text("Plugins (${state.plugins.count { it.enabled }} enabled)")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            if (state.notes.isEmpty()) {
                item { Text("No notes in this folder yet.", color = TextSecondary) }
            }
            items(state.notes, key = { it.path }) { note ->
                NoteRow(
                    note,
                    onOpen = { navController.navigate(Routes.noteEditor(note.path)) },
                    onDelete = { viewModel.deleteNote(note.path) },
                )
            }
        }
    }

    when (dialog) {
        NoteDialog.NOTE -> NameDialog(
            title = "New note",
            label = "File name",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                viewModel.createNote(state.folder, name) { path -> navController.navigate(Routes.noteEditor(path)) }
            },
        )
        NoteDialog.FOLDER -> NameDialog(
            title = "New folder",
            label = "Folder path",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                viewModel.createFolder(name)
            },
        )
        null -> Unit
    }
}

private enum class NoteDialog { NOTE, FOLDER }

@Composable
private fun NoteRow(note: NoteSummary, onOpen: () -> Unit, onDelete: () -> Unit) {
    DroidCard(modifier = Modifier.clickable(onClick = onOpen)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(note.title, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(note.path, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onDelete) { Text("Delete", color = AccentRed) }
        }
    }
}

@Composable
private fun NameDialog(title: String, label: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
