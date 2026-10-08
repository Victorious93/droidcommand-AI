package ai.droidcommand.app.ui.chat

import ai.droidcommand.llm.factory.CloudProviderCatalog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Phase 1 chat: pick a provider and model, type, and watch the reply stream in. Compiles; never run
 * on a device or against a live API. The model field is free text pre-filled with a suggestion,
 * because model ids change and no list here was verified.
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val rows = state.lines + if (state.streaming.isNotEmpty()) listOf(ChatLine(false, state.streaming)) else emptyList()

    LaunchedEffect(rows.size, state.streaming) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column {
                OutlinedButton(onClick = { menuOpen = true }) { Text(state.provider.label) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    CloudProviderCatalog.all.forEach { spec ->
                        DropdownMenuItem(text = { Text(spec.label) }, onClick = {
                            viewModel.selectProvider(spec)
                            menuOpen = false
                        })
                    }
                }
            }
            OutlinedButton(onClick = viewModel::newChat, enabled = !state.sending) { Text("New chat") }
        }
        OutlinedTextField(
            value = state.model,
            onValueChange = viewModel::setModel,
            label = { Text("Model (suggested default — edit if it is outdated)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows) { line ->
                Text(
                    text = (if (line.fromUser) "You: " else "AI: ") + line.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Switch(checked = state.memoryEnabled, onCheckedChange = viewModel::setMemoryEnabled)
            Text("Memory (applies next time you open Chat)", style = MaterialTheme.typography.bodySmall)
        }
        if (state.memoryEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = viewModel::rememberChat, enabled = !state.sending) { Text("Remember this chat") }
                state.memoryStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), label = { Text("Message") })
            Button(
                onClick = {
                    viewModel.send(input)
                    input = ""
                },
                enabled = !state.sending && input.isNotBlank(),
            ) { Text(if (state.sending) "…" else "Send") }
        }
    }
}
