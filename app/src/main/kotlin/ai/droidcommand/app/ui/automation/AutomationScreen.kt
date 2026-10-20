package ai.droidcommand.app.ui.automation

import ai.droidcommand.app.automation.AutomationAction
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.llm.factory.CloudProviderCatalog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Automation keys: saved shell commands and AI slash commands. Every run waits for the owner's approval, and a shell
 * key runs only an executable on the allowlist.
 */
@Composable
fun AutomationScreen(viewModel: AutomationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Automation keys")
        Text(
            "A key runs one shell command or sends one prompt to a cloud AI provider. Each run asks you to approve it first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        SectionLabel("Keys")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.keys.isEmpty()) {
                    Text(
                        "No keys yet. Add one below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.keys.forEach { key ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(key.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            describe(key.action),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.runKey(key.id) }, enabled = !state.running) { Text("Run") }
                            OutlinedButton(onClick = { viewModel.deleteKey(key.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        }

        if (state.running || state.lastRun != null) {
            SectionLabel("Last run")
            DroidCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.running) {
                        Text("Waiting for your approval, then for the run to finish.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        state.lastRun?.let { run ->
                            Text(state.lastRunLabel.orEmpty(), style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (run.succeeded) "Succeeded" else "Did not succeed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(run.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        }
                    }
                }
            }
        }

        SectionLabel("New key")
        NewKeyCard(onSave = viewModel::addKey)

        SectionLabel("Allowed executables")
        AllowlistCard(
            entries = state.allowedExecutables,
            onAdd = viewModel::addAllowedExecutable,
            onRemove = viewModel::removeAllowedExecutable,
        )
    }
}

@Composable
private fun NewKeyCard(onSave: (label: String, shell: Boolean, executable: String, args: String, slashCommand: String) -> Unit) {
    var shell by rememberSaveable { mutableStateOf(true) }
    var label by rememberSaveable { mutableStateOf("") }
    var executable by rememberSaveable { mutableStateOf("") }
    var args by rememberSaveable { mutableStateOf("") }
    var slashCommand by rememberSaveable { mutableStateOf("") }
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = shell, onClick = { shell = true }, label = { Text("Shell command") })
                FilterChip(selected = !shell, onClick = { shell = false }, label = { Text("AI slash command") })
            }
            if (shell) {
                OutlinedTextField(
                    value = executable,
                    onValueChange = { executable = it },
                    label = { Text("Executable (must be allowed below)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    label = { Text("Arguments, separated by spaces") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = slashCommand,
                    onValueChange = { slashCommand = it },
                    label = { Text("Slash command, e.g. /claude Summarise my notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = { onSave(label, shell, executable, args, slashCommand) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save key") }
        }
    }
}

@Composable
private fun AllowlistCard(entries: Set<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var newEntry by rememberSaveable { mutableStateOf("") }
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "A shell key can run only an executable listed here, and the name must match the key's executable exactly. " +
                    "Nothing runs while this list is empty.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entries.isEmpty()) {
                Text("No executables allowed yet.", style = MaterialTheme.typography.bodySmall)
            }
            entries.sorted().forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    TextButton(onClick = { onRemove(entry) }) { Text("Remove") }
                }
            }
            OutlinedTextField(
                value = newEntry,
                onValueChange = { newEntry = it },
                label = { Text("Executable") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { onAdd(newEntry) }, modifier = Modifier.fillMaxWidth()) { Text("Allow executable") }
        }
    }
}

private fun describe(action: AutomationAction): String = when (action) {
    is AutomationAction.Shell -> listOf("Shell:", action.executable, action.args).filter { it.isNotBlank() }.joinToString(" ")
    is AutomationAction.Ai -> "AI, ${CloudProviderCatalog.byId(action.providerId)?.label ?: action.providerId}: ${action.prompt}"
}
