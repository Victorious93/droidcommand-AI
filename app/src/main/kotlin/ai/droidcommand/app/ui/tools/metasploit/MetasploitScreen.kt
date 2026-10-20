package ai.droidcommand.app.ui.tools.metasploit

import ai.droidcommand.app.termux.AndroidPendingIntentRunCommandClient
import ai.droidcommand.app.ui.tools.ToolRunStatusCard
import ai.droidcommand.app.ui.tools.ToolRunUiState
import ai.droidcommand.metasploit.MetasploitModuleType
import ai.droidcommand.pentestswarm.LocalKaliPlaybookRunner
import ai.droidcommand.termux.RunCommandContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
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
 * `msfconsole` command. "Run Module" and the Kali playbook still have to
 * clear the real confirmation dialog
 * ([ai.droidcommand.app.approval.ApprovalHost]) before
 * [ai.droidcommand.metasploit.MetasploitTool] /
 * [ai.droidcommand.pentestswarm.KaliAuthorizedScanTool] is invoked.
 *
 * Kali tools run inside VictorSuite (Termux + proot Kali) through
 * `com.termux.RUN_COMMAND` + `RUN_COMMAND_PENDING_INTENT`. That path is
 * **NOT RUNTIME VERIFIED** on a device in the sessions that wired it.
 */
@Composable
fun MetasploitScreen(viewModel: MetasploitViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var pendingOptionKey by remember { mutableStateOf("") }
    var pendingOptionValue by remember { mutableStateOf("") }
    var pendingAfterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) pendingAfterPermission?.invoke()
        pendingAfterPermission = null
    }

    fun withRunCommandPermission(action: () -> Unit) {
        val installed = AndroidPendingIntentRunCommandClient.isVictorSuiteInstalled(context)
        val granted = AndroidPendingIntentRunCommandClient.hasRunCommandPermission(context)
        if (RunCommandContract.shouldRequestRuntimePermission(installed, granted)) {
            pendingAfterPermission = action
            permissionLauncher.launch(RunCommandContract.PERMISSION)
            return
        }
        action()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Metasploit", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Every field below is structured input — no command is ever typed. " +
                "Running a module always asks for explicit confirmation first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "VictorSuite (Termux + proot Kali) is the on-device backend. " +
                "Commands go through com.termux.RUN_COMMAND with RUN_COMMAND_BACKGROUND " +
                "and RUN_COMMAND_PENDING_INTENT — not ADB, root, or terminal scraping. " +
                "On-device msfconsole round-trip is UNVERIFIED. " +
                "Requires allow-external-apps=true, the RUN_COMMAND permission, and the app-shell runner. " +
                "See docs/VICTORSUITE_KALI_RUN_COMMAND.md.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Authorized Kali playbook", style = MaterialTheme.typography.labelLarge)
        Text(
            "nmap then an auxiliary msfconsole resource script against one named host. " +
                "Blank, subnet, and mass targets are refused. Active/exploit steps ask again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = form.scopeFilePath,
            onValueChange = { value -> viewModel.updateForm { it.copy(scopeFilePath = value) } },
            label = { Text("Scope file path (required)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { viewModel.writeScopeFileForHost(context.filesDir) },
            enabled = form.targetHost.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Write scope file for this host") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = form.targetsAuthorized,
                onCheckedChange = { checked -> viewModel.updateForm { it.copy(targetsAuthorized = checked) } },
            )
            Text(
                LocalKaliPlaybookRunner.AUTHORIZED_CONFIRMATION_PHRASE,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = { withRunCommandPermission(viewModel::runAuthorizedKaliPlaybook) },
            enabled = form.targetHost.isNotBlank() &&
                form.scopeFilePath.isNotBlank() &&
                form.targetsAuthorized &&
                uiState !is ToolRunUiState.Running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Run authorized Kali playbook") }

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
            onClick = { withRunCommandPermission(viewModel::runModule) },
            enabled = form.targetHost.isNotBlank() && form.modulePath.isNotBlank() && uiState !is ToolRunUiState.Running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Run Module") }

        ToolRunStatusCard(uiState)
    }
}
