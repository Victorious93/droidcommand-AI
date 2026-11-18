package ai.droidcommand.app.ui.n8n

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.DroidEmptyState
import ai.droidcommand.app.ui.components.DroidGroup
import ai.droidcommand.app.ui.components.DroidNote
import ai.droidcommand.app.ui.components.DroidRowDivider
import ai.droidcommand.app.ui.components.DroidSwitchRow
import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.components.StatusPill
import ai.droidcommand.app.ui.components.pillColors
import ai.droidcommand.app.ui.settings.KeyStatus
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.n8n.N8nWorkflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

/**
 * Your own n8n server: a three-step setup (address, key, workflows), then the workflows you can start. The screen
 * only reports what it knows. "Ready to test" is as far as it goes before a test has run, and every run still asks
 * first, so nothing here changes what the app is allowed to do.
 */
@Composable
fun N8nScreen(viewModel: N8nViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsState()
    val (statusText, statusTone) = N8nPresentation.statusPill(ui)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("n8n_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitleWithHelp("n8n", ScreenHelpCatalog.N8N)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "n8n runs on a computer you own: a PC, a Raspberry Pi or a home server. This app talks to that computer. The API key stays encrypted on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            StatusPill(statusText, statusTone)
        }

        SectionLabel("Setup")
        DroidGroup {
            N8nPresentation.steps(ui).forEachIndexed { index, step ->
                if (index > 0) DroidRowDivider()
                SetupRow(step.title, step.done, step.detail)
            }
        }

        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connect to your n8n server", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = ui.serverUrl,
                    onValueChange = viewModel::setServerUrl,
                    modifier = Modifier.fillMaxWidth().testTag("n8n_server_url"),
                    singleLine = true,
                    label = { Text("Server address") },
                    placeholder = { Text("https://192.168.1.20:5678") },
                    isError = ui.urlError != null,
                    supportingText = ui.urlError?.let { error -> { Text(error) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
                ui.urlWarning?.let { DroidNote(it, tone = PillTone.Warning) }
                var keyDraft by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    modifier = Modifier.fillMaxWidth().testTag("n8n_api_key"),
                    singleLine = true,
                    label = { Text(if (ui.keyStatus == KeyStatus.SAVED) "Replace the API key" else "API key") },
                    supportingText = { Text(keyStatusText(ui.keyStatus)) },
                    isError = ui.keyStatus == KeyStatus.NEEDS_REENTRY || ui.keyStatus == KeyStatus.STORAGE_ERROR,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DroidPrimaryButton(
                        onClick = {
                            viewModel.saveApiKey(keyDraft)
                            keyDraft = ""
                        },
                        enabled = keyDraft.isNotBlank(),
                    ) { Text("Save key") }
                    DroidTextButton(onClick = viewModel::clearApiKey, enabled = ui.keyStatus != KeyStatus.NOT_SET) { Text("Remove key") }
                }
                DroidSecondaryButton(
                    onClick = viewModel::testConnection,
                    enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth().testTag("n8n_test_connection"),
                ) { Text(if (ui.busy) "Working…" else "Test connection") }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Workflows", modifier = Modifier.weight(1f))
            if (ui.workflows.isNotEmpty()) {
                DroidTextButton(onClick = viewModel::refreshWorkflows, enabled = !ui.busy) { Text("Refresh") }
            }
        }
        if (ui.workflows.isEmpty()) {
            DroidEmptyState(
                icon = Icons.Filled.AccountTree,
                title = "No workflows loaded",
                body = "Finish the setup above, then load them from your server.",
            ) {
                DroidSecondaryButton(onClick = viewModel::refreshWorkflows, enabled = !ui.busy) { Text("Load workflows") }
            }
        }
        ui.workflows.forEach { workflow ->
            val selected = workflow.id == ui.selectedId
            WorkflowCard(
                workflow = workflow,
                selected = selected,
                onSelect = { viewModel.selectWorkflow(workflow.id) },
            ) {
                Text(
                    N8nPresentation.runExplanation(workflow),
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
                DroidPrimaryButton(
                    onClick = viewModel::requestRun,
                    enabled = N8nPresentation.canRun(ui, workflow),
                    modifier = Modifier.fillMaxWidth().testTag("n8n_run"),
                ) { Text("Run (asks first)") }
            }
        }

        ui.status?.let {
            SectionLabel("Result")
            DroidCard(modifier = Modifier.testTag("n8n_result")) {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionLabel("AI access")
        DroidCard {
            DroidSwitchRow(
                title = "Let the AI use n8n",
                subtitle = "Off by default. When on, the AI can ask to list or run a workflow. This phone still asks you before every run.",
                checked = ui.aiToolsEnabled,
                onCheckedChange = viewModel::setAiToolsEnabled,
                modifier = Modifier.testTag("n8n_ai_tools_switch"),
            )
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
                DroidTextButton(onClick = viewModel::confirmRun, modifier = Modifier.testTag("n8n_run_confirm")) { Text("Run") }
            },
            dismissButton = {
                DroidTextButton(onClick = viewModel::cancelRun) { Text("Cancel") }
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
                DroidTextButton(onClick = viewModel::confirmAiToolsOn, modifier = Modifier.testTag("n8n_ai_tools_confirm")) { Text("Turn on") }
            },
            dismissButton = {
                DroidTextButton(onClick = viewModel::cancelAiToolsOn) { Text("Keep off") }
            },
        )
    }
}

@Composable
private fun SetupRow(title: String, done: Boolean, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (done) "Done" else "Not done yet",
            tint = if (done) pillColors(PillTone.Success).second else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One workflow. Tapping it selects it; the selected one opens the panel for starting it, inside the same card. */
@Composable
private fun WorkflowCard(
    workflow: N8nWorkflow,
    selected: Boolean,
    onSelect: () -> Unit,
    panel: @Composable () -> Unit,
) {
    DroidCard(
        onClick = onSelect,
        borderColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(workflow.name, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                N8nPresentation.workflowBadges(workflow).forEach { (text, tone) -> StatusPill(text, tone) }
            }
            if (selected) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { panel() }
            } else {
                Text("Tap to open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun keyStatusText(status: KeyStatus) = when (status) {
    KeyStatus.NOT_SET -> "API key: not set"
    KeyStatus.SAVED -> "API key: saved (encrypted on this phone)"
    KeyStatus.NEEDS_REENTRY -> "API key: stored key can no longer be decrypted — enter it again"
    KeyStatus.STORAGE_ERROR -> "API key: storage error — try again"
}
