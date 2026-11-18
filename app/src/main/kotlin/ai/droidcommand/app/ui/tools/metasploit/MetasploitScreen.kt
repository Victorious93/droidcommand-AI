package ai.droidcommand.app.ui.tools.metasploit

import ai.droidcommand.app.termux.AndroidPendingIntentRunCommandClient
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.DroidCheckRow
import ai.droidcommand.app.ui.components.DroidGroup
import ai.droidcommand.app.ui.components.DroidListRow
import ai.droidcommand.app.ui.components.DroidNote
import ai.droidcommand.app.ui.components.DroidRowDivider
import ai.droidcommand.app.ui.components.DroidTabs
import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.components.StatusPill
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Every field here is structured input filled in by tapping or typing into a single-purpose field, never a place to
 * type an `msfconsole` command. Running a scan or a module still has to clear the real confirmation dialog
 * ([ai.droidcommand.app.approval.ApprovalHost]) before [ai.droidcommand.metasploit.MetasploitTool] or
 * [ai.droidcommand.pentestswarm.KaliAuthorizedScanTool] is invoked. This screen only arranges the inputs and shows
 * what is about to be asked; it does not relax a single check (see [MetasploitReview]).
 *
 * Kali tools run inside VictorSuite (Termux + proot Kali) through `com.termux.RUN_COMMAND` +
 * `RUN_COMMAND_PENDING_INTENT`. That path is **NOT RUNTIME VERIFIED** on a device in the sessions that wired it.
 */
@Composable
fun MetasploitScreen(viewModel: MetasploitViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(MetasploitMode.MODULE) }
    var showHow by rememberSaveable { mutableStateOf(false) }
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

    val running = uiState is ToolRunUiState.Running
    val suiteInstalled = remember { AndroidPendingIntentRunCommandClient.isVictorSuiteInstalled(context) }
    val targetProblem = MetasploitReview.targetProblem(form.targetHost)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Metasploit")
        if (!suiteInstalled) {
            DroidNote(
                "VictorSuite (Termux + Kali) is not installed, so a run cannot start. The Security tools screen lists what is missing.",
                tone = PillTone.Warning,
            )
        }
        DroidNote(
            "VictorSuite (Termux + proot Kali) is the on-device backend. An on-device msfconsole run has not been verified yet. " +
                "Every run asks you first, and only against one named host.",
        )
        DroidTextButton(onClick = { showHow = !showHow }) { Text(if (showHow) "Hide how this runs" else "How this runs") }
        if (showHow) {
            Text(
                "Commands go through com.termux.RUN_COMMAND with " +
                    "RUN_COMMAND_BACKGROUND and RUN_COMMAND_PENDING_INTENT, not ADB, root, or terminal scraping. " +
                    "It requires allow-external-apps=true, the RUN_COMMAND permission, and the app-shell runner. " +
                    "See docs/VICTORSUITE_KALI_RUN_COMMAND.md.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DroidTabs(
            labels = MetasploitMode.entries.map { it.label },
            selectedIndex = mode.ordinal,
            onSelect = { mode = MetasploitMode.entries[it] },
        )

        SectionLabel("1 · Target")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.targetHost,
                    onValueChange = { value -> viewModel.updateForm { it.copy(targetHost = value) } },
                    label = { Text("Target host (required, authorized target only)") },
                    isError = targetProblem != null,
                    supportingText = { Text(targetProblem ?: "One host you are authorized to test. Ranges, subnets and lists are refused.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (mode == MetasploitMode.MODULE) {
                    OutlinedTextField(
                        value = form.targetPort,
                        onValueChange = { value -> viewModel.updateForm { it.copy(targetPort = value) } },
                        label = { Text("Target port (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        when (mode) {
            MetasploitMode.SCAN -> ScanSteps(
                form = form,
                running = running,
                onForm = viewModel::updateForm,
                onWriteScope = { viewModel.writeScopeFileForHost(context.filesDir) },
                onRun = { withRunCommandPermission(viewModel::runAuthorizedKaliPlaybook) },
            )
            MetasploitMode.MODULE -> ModuleSteps(
                form = form,
                running = running,
                onForm = viewModel::updateForm,
                onRun = { withRunCommandPermission(viewModel::runModule) },
            )
        }

        ToolRunStatusCard(uiState)
    }
}

@Composable
private fun ScanSteps(
    form: MetasploitFormState,
    running: Boolean,
    onForm: ((MetasploitFormState) -> MetasploitFormState) -> Unit,
    onWriteScope: () -> Unit,
    onRun: () -> Unit,
) {
    SectionLabel("2 · Scope")
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "nmap, then a Metasploit auxiliary script, against the one host above. Blank, subnet and mass targets are refused, and active or exploit steps ask again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = form.scopeFilePath,
                onValueChange = { value -> onForm { it.copy(scopeFilePath = value) } },
                label = { Text("Scope file path (required)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DroidSecondaryButton(
                onClick = onWriteScope,
                enabled = form.targetHost.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create a scope file for this host") }
            DroidCheckRow(
                text = LocalKaliPlaybookRunner.AUTHORIZED_CONFIRMATION_PHRASE,
                checked = form.targetsAuthorized,
                onCheckedChange = { checked -> onForm { it.copy(targetsAuthorized = checked) } },
            )
        }
    }
    SectionLabel("3 · Review and run")
    ReviewCard(MetasploitReview.scanSummary(form))
    DroidPrimaryButton(
        onClick = onRun,
        enabled = MetasploitReview.canRunScan(form, running),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Run authorized Kali scan") }
    MetasploitReview.missingHint(MetasploitReview.scanMissing(form))?.let { Hint(it) }
}

@Composable
private fun ModuleSteps(
    form: MetasploitFormState,
    running: Boolean,
    onForm: ((MetasploitFormState) -> MetasploitFormState) -> Unit,
    onRun: () -> Unit,
) {
    var optionKey by remember { mutableStateOf("") }
    var optionValue by remember { mutableStateOf("") }

    SectionLabel("2 · Module")
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetasploitModuleType.entries.forEach { type ->
                    FilterChip(
                        selected = form.moduleType == type,
                        onClick = { onForm { it.copy(moduleType = type) } },
                        label = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            OutlinedTextField(
                value = form.modulePath,
                onValueChange = { value -> onForm { it.copy(modulePath = value) } },
                label = { Text("Module path") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    SuggestionList("Common modules", MetasploitReview.COMMON_MODULES, chosen = form.modulePath) { path -> onForm { it.copy(modulePath = path) } }

    SectionLabel("3 · Payload (optional)")
    DroidCard {
        OutlinedTextField(
            value = form.payload,
            onValueChange = { value -> onForm { it.copy(payload = value) } },
            label = { Text("Payload path") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    SuggestionList("Common payloads", MetasploitReview.COMMON_PAYLOADS, chosen = form.payload) { path -> onForm { it.copy(payload = path) } }

    SectionLabel("4 · Options (optional)")
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            form.options.forEachIndexed { index, pair ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${pair.first} = ${pair.second}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    DroidTextButton(onClick = { onForm { f -> f.copy(options = f.options.filterIndexed { i, _ -> i != index }) } }) { Text("Remove") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = optionKey,
                    onValueChange = { optionKey = it },
                    label = { Text("Key, e.g. LHOST") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = optionValue,
                    onValueChange = { optionValue = it },
                    label = { Text("Value") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            DroidSecondaryButton(
                onClick = {
                    if (optionKey.isNotBlank()) {
                        val key = optionKey
                        val value = optionValue
                        onForm { f -> f.copy(options = f.options + (key to value)) }
                        optionKey = ""
                        optionValue = ""
                    }
                },
                enabled = optionKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add option") }
        }
    }

    SectionLabel("5 · Review and run")
    ReviewCard(MetasploitReview.moduleSummary(form))
    DroidPrimaryButton(
        onClick = onRun,
        enabled = MetasploitReview.canRunModule(form, running),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Run Module") }
    MetasploitReview.missingHint(MetasploitReview.moduleMissing(form))?.let { Hint(it) }
}

/** Tap a known module or payload instead of typing its path; the one in the field is marked. */
@Composable
private fun SuggestionList(title: String, items: List<MetasploitSuggestion>, chosen: String, onPick: (String) -> Unit) {
    SectionLabel(title)
    DroidGroup {
        items.forEachIndexed { index, item ->
            if (index > 0) DroidRowDivider()
            DroidListRow(
                title = item.path,
                subtitle = item.what,
                onClick = { onPick(item.path) },
                trailing = if (chosen.trim() == item.path) ({ StatusPill("Chosen", PillTone.Success) }) else null,
            )
        }
    }
}

/** What is about to be asked, in plain lines. The confirmation dialog that follows is the real gate. */
@Composable
private fun ReviewCard(lines: List<Pair<String, String>>) {
    DroidCard(modifier = Modifier.testTag("metasploit_review"), borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEach { (label, value) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp).weight(0.35f))
                    Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.65f))
                }
            }
            Text(
                "You are asked to confirm before anything executes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
}
