package ai.droidcommand.app.mcp

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Advanced → Extra AI tools (MCP). Off by default. The owner adds a server address (and an optional token,
 * kept in the phone's encrypted key storage), then picks that server's tools for a helper bot in Bot maker.
 */
@Composable
fun McpServersSection(viewModel: McpServersViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsState()
    SectionLabel("Extra AI tools (MCP)")
    DroidCard(modifier = Modifier.testTag("advanced_mcp")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Let a helper bot use tools from an MCP server you run, like one on your home PC. " +
                    "Off until you turn it on. A bot only gets the tools you pick for it, and this phone asks you " +
                    "before each one runs. Tools that change something ask again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Use MCP servers", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = ui.enabled,
                    onCheckedChange = viewModel::requestEnabled,
                    modifier = Modifier.testTag("advanced_mcp_switch"),
                )
            }
            ui.servers.forEach { row -> ServerRow(row, viewModel) }
            AddServer(ui, viewModel)
            DroidPrimaryButton(
                onClick = viewModel::refresh,
                enabled = ui.enabled && !ui.busy && ui.servers.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("advanced_mcp_refresh"),
            ) { Text(if (ui.busy) "Checking…" else "Check servers and load tools") }
            ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (ui.confirmEnable) {
        AlertDialog(
            onDismissRequest = viewModel::cancelEnable,
            title = { Text("Turn on MCP servers?") },
            text = {
                Text(
                    "This phone will connect to the servers you add and list their tools. " +
                        "Nothing runs until a helper bot asks and you say yes.",
                )
            },
            confirmButton = { DroidTextButton(onClick = viewModel::confirmEnable) { Text("Turn on") } },
            dismissButton = { DroidTextButton(onClick = viewModel::cancelEnable) { Text("Cancel") } },
        )
    }
    ui.pendingRemove?.let { server ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRemove,
            title = { Text("Remove ${server.label}?") },
            text = { Text("Its address and token are deleted from this phone. Helper bots lose its tools.") },
            confirmButton = { DroidTextButton(onClick = viewModel::confirmRemove) { Text("Remove") } },
            dismissButton = { DroidTextButton(onClick = viewModel::cancelRemove) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServerRow(row: McpServerRow, viewModel: McpServersViewModel) {
    var tokenDraft by remember(row.server.id) { mutableStateOf("") }
    HorizontalDivider()
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("advanced_mcp_server_${row.server.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(row.server.label, style = MaterialTheme.typography.bodyMedium)
                Text(row.server.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (row.hasToken) "Token saved" else "No token",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = row.server.enabled, onCheckedChange = { viewModel.setServerOn(row.server.id, it) })
        }
        row.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(
            value = tokenDraft,
            onValueChange = { tokenDraft = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(if (row.hasToken) "Replace token" else "Token (optional)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DroidSecondaryButton(
                onClick = {
                    viewModel.saveToken(row.server.id, tokenDraft)
                    tokenDraft = ""
                },
                enabled = tokenDraft.isNotBlank(),
            ) { Text("Save token") }
            if (row.hasToken) {
                DroidTextButton(onClick = { viewModel.clearToken(row.server.id) }) { Text("Remove token") }
            }
            DroidTextButton(onClick = { viewModel.askRemove(row.server) }) { Text("Remove") }
        }
    }
}

@Composable
private fun AddServer(ui: McpServersUi, viewModel: McpServersViewModel) {
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    HorizontalDivider()
    Text("Add a server", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = label,
        onValueChange = { label = it },
        modifier = Modifier.fillMaxWidth().testTag("advanced_mcp_label"),
        singleLine = true,
        label = { Text("Name") },
        placeholder = { Text("Home PC") },
    )
    OutlinedTextField(
        value = url,
        onValueChange = {
            url = it
            viewModel.checkUrl(it)
        },
        modifier = Modifier.fillMaxWidth().testTag("advanced_mcp_url"),
        singleLine = true,
        label = { Text("Server address") },
        placeholder = { Text("https://192.168.1.20:8080/mcp") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
    )
    ui.urlWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
    ui.urlProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        modifier = Modifier.fillMaxWidth().testTag("advanced_mcp_token"),
        singleLine = true,
        label = { Text("Token (optional)") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    )
    DroidSecondaryButton(
        onClick = {
            if (viewModel.addServer(label, url, token)) {
                label = ""
                url = ""
                token = ""
            }
        },
        enabled = url.isNotBlank(),
        modifier = Modifier.fillMaxWidth().testTag("advanced_mcp_add"),
    ) { Text("Add server") }
}
