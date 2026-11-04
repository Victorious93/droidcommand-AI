package ai.droidcommand.app.ui.n8n

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.settings.KeyStatus
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.n8n.N8nWorkflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun N8nScreen(viewModel: N8nViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("n8n_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitleWithHelp("n8n", ScreenHelpCatalog.N8N)
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Connect to your n8n server", style = MaterialTheme.typography.titleMedium)
                Text(
                    "n8n is a tool you run on a computer you own — a PC, a Raspberry Pi, or a home server. " +
                        "This app talks to that computer. The API key stays encrypted on this phone and is never shown again after you save it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SectionLabel("Server")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ui.serverUrl,
                    onValueChange = viewModel::setServerUrl,
                    modifier = Modifier.fillMaxWidth().testTag("n8n_server_url"),
                    singleLine = true,
                    label = { Text("Server address") },
                    placeholder = { Text("https://192.168.1.20:5678") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
                ui.urlWarning?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                ui.urlError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    keyStatusText(ui.keyStatus),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var keyDraft by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    modifier = Modifier.fillMaxWidth().testTag("n8n_api_key"),
                    singleLine = true,
                    label = { Text("API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.saveApiKey(keyDraft)
                            keyDraft = ""
                        },
                        enabled = keyDraft.isNotBlank(),
                    ) { Text("Save key") }
                    OutlinedButton(onClick = viewModel::clearApiKey, enabled = ui.keyStatus != KeyStatus.NOT_SET) { Text("Remove key") }
                }
                Button(
                    onClick = viewModel::testConnection,
                    enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth().testTag("n8n_test_connection"),
                ) { Text(if (ui.busy) "Working…" else "Test connection") }
            }
        }
        SectionLabel("Workflows")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::refreshWorkflows,
                    enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Load workflows") }
                if (ui.workflows.isEmpty()) {
                    Text(
                        "No workflows loaded yet. Test the connection or load them after n8n is reachable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ui.workflows.forEach { workflow ->
                    WorkflowRow(workflow, selected = workflow.id == ui.selectedId, onSelect = { viewModel.selectWorkflow(workflow.id) })
                }
            }
        }
        val selected = ui.workflows.firstOrNull { it.id == ui.selectedId }
        if (selected != null) {
            SectionLabel("Run once")
            DroidCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selected.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (selected.canTrigger) {
                            "This sends JSON to the webhook on ${selected.name}. n8n then does whatever that workflow is set up to do. You will be asked to confirm."
                        } else {
                            "This workflow has no webhook, so this app cannot start it."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = ui.jsonInput,
                        onValueChange = viewModel::setJsonInput,
                        modifier = Modifier.fillMaxWidth().testTag("n8n_json_input"),
                        minLines = 3,
                        label = { Text("JSON to send") },
                    )
                    Button(
                        onClick = viewModel::requestRun,
                        enabled = !ui.busy && selected.canTrigger,
                        modifier = Modifier.fillMaxWidth().testTag("n8n_run"),
                    ) { Text("Run (asks first)") }
                }
            }
        }
        ui.status?.let {
            SectionLabel("Result")
            DroidCard(modifier = Modifier.testTag("n8n_result")) {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        SectionLabel("AI / Kai tools")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Off by default. When this is on, the AI can ask to list or run an n8n workflow. " +
                        "This phone still asks you before every run. Leave it off unless you want that.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Let the AI use n8n")
                    Switch(
                        checked = ui.aiToolsEnabled,
                        onCheckedChange = viewModel::setAiToolsEnabled,
                        modifier = Modifier.testTag("n8n_ai_tools_switch"),
                    )
                }
            }
        }
    }
    if (ui.confirmRun) {
        val name = ui.workflows.firstOrNull { it.id == ui.selectedId }?.name ?: "this workflow"
        AlertDialog(
            onDismissRequest = viewModel::cancelRun,
            title = { Text("Run this n8n workflow?") },
            text = {
                Text("This will send your JSON to $name on your n8n server. n8n will do whatever that workflow is set up to do.")
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRun, modifier = Modifier.testTag("n8n_run_confirm")) { Text("Run") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRun) { Text("Cancel") }
            },
        )
    }
    if (ui.confirmAiTools) {
        AlertDialog(
            onDismissRequest = viewModel::cancelAiToolsOn,
            title = { Text("Let the AI run n8n workflows?") },
            text = {
                Text("When this is on, Chat or a one-step command can ask to run a workflow on your n8n server. This phone still asks before every run. Leave this off unless you need it.")
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmAiToolsOn, modifier = Modifier.testTag("n8n_ai_tools_confirm")) { Text("Turn on") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelAiToolsOn) { Text("Keep off") }
            },
        )
    }
}

@Composable
private fun WorkflowRow(workflow: N8nWorkflow, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(workflow.name, style = MaterialTheme.typography.bodyLarge)
            val hook = if (workflow.canTrigger) "webhook ${workflow.webhook?.path}" else "no webhook"
            val on = if (workflow.active) "on" else "off"
            Text("$on · $hook", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun keyStatusText(status: KeyStatus) = when (status) {
    KeyStatus.NOT_SET -> "API key: not set"
    KeyStatus.SAVED -> "API key: saved (encrypted on this phone)"
    KeyStatus.NEEDS_REENTRY -> "API key: stored key can no longer be decrypted — enter it again"
    KeyStatus.STORAGE_ERROR -> "API key: storage error — try again"
}
