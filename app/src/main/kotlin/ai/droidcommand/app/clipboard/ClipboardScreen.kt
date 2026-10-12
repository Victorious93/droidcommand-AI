package ai.droidcommand.app.clipboard

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** Public and private clipboards. Only the explicit paste and copy buttons touch the Android system clipboard. */
@Composable
fun ClipboardScreen(viewModel: ClipboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Clipboards")
        Text(
            "Public clips stay in this app's memory until it closes. Private clips are encrypted on this device. " +
                "Copying a clip to the system clipboard lets other apps read it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        SectionLabel("Public")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = viewModel::pasteFromSystem, modifier = Modifier.fillMaxWidth()) {
                    Text("Paste from system clipboard")
                }
                if (state.publicClips.isEmpty()) {
                    Text("No public clips yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.publicClips.forEach { clip ->
                    ClipRow(clip.text) {
                        OutlinedButton(onClick = { viewModel.moveToPrivate(clip.id) }) { Text("Move to private") }
                        OutlinedButton(onClick = { viewModel.copyToSystem(clip.text) }) { Text("Copy") }
                        OutlinedButton(onClick = { viewModel.deletePublic(clip.id) }) { Text("Delete") }
                    }
                }
                if (state.publicClips.isNotEmpty()) {
                    TextButton(onClick = viewModel::clearPublic) { Text("Clear public clips") }
                }
            }
        }

        SectionLabel("Private (encrypted on this device)")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.privateUnreadable) {
                    Text(
                        "Private clips are stored but cannot be read, for example after a Keystore reset. " +
                            "Nothing has been moved into them. Erase them to start again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = viewModel::eraseAllPrivate) { Text("Erase all private clips") }
                } else {
                    if (state.privateClips.isEmpty()) {
                        Text(
                            "No private clips. Use Move to private on a public clip.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.privateClips.forEach { clip ->
                        ClipRow(clip.text) {
                            OutlinedButton(onClick = { viewModel.copyToSystem(clip.text) }) { Text("Copy (exposes it)") }
                            OutlinedButton(onClick = { viewModel.deletePrivate(clip.id) }) { Text("Delete") }
                        }
                    }
                    if (state.privateClips.isNotEmpty()) {
                        TextButton(onClick = viewModel::eraseAllPrivate) { Text("Erase all private clips") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipRow(text: String, actions: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}
