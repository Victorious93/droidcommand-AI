package ai.droidcommand.app.ui.tools.metasploit

import ai.droidcommand.app.ui.tools.ToolRunStatusCard
import ai.droidcommand.app.ui.tools.ToolRunUiState
import ai.droidcommand.metasploit.MetasploitModuleType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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

private val COMMON_MODULES = listOf(
    "auxiliary/scanner/portscan/tcp",
    "auxiliary/scanner/http/http_version",
    "exploit/multi/handler",
)

private val COMMON_PAYLOADS = listOf(
    "payload/windows/x64/meterpreter/reverse_tcp",
    "payload/linux/x64/meterpreter/reverse_tcp",
    "payload/generic/shell_reverse_tcp",
)

/**
 * Every field here is structured input filled in by tapping a button or
 * typing into a single-purpose field — never a place to type an
 * `msfconsole` command. "Run Module" only enables once a target host and
 * module path are set, and the tap itself still has to clear the real
 * confirmation dialog ([ai.droidcommand.app.approval.ApprovalHost]) before
 * [ai.droidcommand.metasploit.MetasploitTool] is actually invoked.
 */
@Composable
fun MetasploitScreen(viewModel: MetasploitViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    var pendingOptionKey by remember { mutableStateOf("") }
    var pendingOptionValue by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Metasploit", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Every field below is structured input — no command is ever typed. " +
                "Running a module always asks for explicit confirmation first.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Module type", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetasploitModuleType.entries.forEach { type ->
                val selected = form.moduleType == type
                if (selected) {
                    Button(onClick = { viewModel.updateForm { it.copy(moduleType = type) } }) { Text(type.name) }
                } else {
                    OutlinedButton(onClick = { viewModel.updateForm { it.copy(moduleType = type) } }) { Text(type.name) }
                }
            }
        }

        Text("Module (tap to select, or edit below)", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(COMMON_MODULES) { module ->
                OutlinedButton(onClick = { viewModel.updateForm { it.copy(modulePath = module) } }) {
                    Text(module, maxLines = 1)
                }
            }
        }
        OutlinedTextField(
            value = form.modulePath,
            onValueChange = { value -> viewModel.updateForm { it.copy(modulePath = value) } },
            label = { Text("Module path") },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = form.targetHost,
            onValueChange = { value -> viewModel.updateForm { it.copy(targetHost = value) } },
            label = { Text("Target host (required, authorized target only)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.targetPort,
            onValueChange = { value -> viewModel.updateForm { it.copy(targetPort = value) } },
            label = { Text("Target port (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Payload (optional, tap to select, or edit below)", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(COMMON_PAYLOADS) { payload ->
                OutlinedButton(onClick = { viewModel.updateForm { it.copy(payload = payload) } }) {
                    Text(payload, maxLines = 1)
                }
            }
        }
        OutlinedTextField(
            value = form.payload,
            onValueChange = { value -> viewModel.updateForm { it.copy(payload = value) } },
            label = { Text("Payload path") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Options", style = MaterialTheme.typography.labelLarge)
        form.options.forEachIndexed { index, pair ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${pair.first} = ${pair.second}", modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    viewModel.updateForm { f -> f.copy(options = f.options.filterIndexed { i, _ -> i != index }) }
                }) { Text("Remove") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = pendingOptionKey,
                onValueChange = { pendingOptionKey = it },
                label = { Text("Key, e.g. LHOST") },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = pendingOptionValue,
                onValueChange = { pendingOptionValue = it },
                label = { Text("Value") },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                if (pendingOptionKey.isNotBlank()) {
                    viewModel.updateForm { f -> f.copy(options = f.options + (pendingOptionKey to pendingOptionValue)) }
                    pendingOptionKey = ""
                    pendingOptionValue = ""
                }
            }) { Text("Add") }
        }

        Button(
            onClick = viewModel::runModule,
            enabled = form.targetHost.isNotBlank() && form.modulePath.isNotBlank() && uiState !is ToolRunUiState.Running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Run Module") }

        ToolRunStatusCard(uiState)
    }
}
