package ai.droidcommand.app.ui.tools.setoolkit

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.DroidGroup
import ai.droidcommand.app.ui.components.DroidListRow
import ai.droidcommand.app.ui.components.DroidNote
import ai.droidcommand.app.ui.components.DroidRowDivider
import ai.droidcommand.app.ui.components.DroidSwitchRow
import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.components.StatusPill
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import ai.droidcommand.app.ui.tools.ToolRunStatusCard
import ai.droidcommand.app.ui.tools.ToolRunUiState
import ai.droidcommand.setoolkit.SetAttackVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Every field here is structured input: a chosen attack vector, a target field, an ordered list of menu answers, an
 * options row — never a raw command string. Launch only enables once at least one of target host / target email is
 * set (mirroring [ai.droidcommand.setoolkit.SetCommand]'s own "no blank/mass target" rule) **and** a backend is
 * available; it still has to clear the real confirmation dialog before [ai.droidcommand.setoolkit.SetTool] is
 * invoked.
 *
 * SET runs through [ai.droidcommand.setoolkit.TermuxSetExecutor] — the same VictorSuite Kali proot Metasploit uses,
 * driven by piping a sequence of lines into `setoolkit`'s stdin (SET has no official scripting flag; this is the
 * real mechanical fact that a Python CLI reading `input()` lets stdin stand in for a keyboard). The review card
 * below shows the exact sequence before every run — see [SetToolReview.stdinPreview].
 */
@Composable
fun SetToolScreen(viewModel: SetToolViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    var optionKey by remember { mutableStateOf("") }
    var optionValue by remember { mutableStateOf("") }
    var answerDraft by remember { mutableStateOf("") }
    var showHow by rememberSaveable { mutableStateOf(false) }
    val backend = viewModel.backendAvailable
    val running = uiState is ToolRunUiState.Running
    val keyProblem = SetToolReview.optionKeyProblem(optionKey)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Social-Engineer Toolkit")
        if (!backend) {
            DroidNote(
                "No Social-Engineer Toolkit program is connected on this device yet. It runs inside VictorSuite's Kali proot, the " +
                    "same way Metasploit does — install VictorSuite and grant the RUN_COMMAND permission (see Security tools), " +
                    "then check again. Launch stays off until then.",
                tone = PillTone.Warning,
                modifier = Modifier.testTag("set_no_backend"),
            )
        }
        DroidNote("Every field is structured input. A launch always asks for your confirmation first, and only against a named target.")
        DroidTextButton(onClick = { showHow = !showHow }) { Text(if (showHow) "Hide how this runs" else "How this runs") }
        if (showHow) {
            Text(
                "SET has no official non-interactive flag, so this types into it the same way a person would: a sequence of lines sent " +
                    "to its stdin, matching its menu's own numbered prompts. The main-menu digits to reach each attack vector below are " +
                    "a best-effort default from SET's own long-stable menu layout — not verified against a real install. Turn off " +
                    "\"Start from the main menu\" and type the whole sequence yourself if your installed version's menu differs. " +
                    "The exact sequence is always shown in Review before it runs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionLabel("1 · Attack vector")
        DroidGroup {
            SetAttackVector.entries.forEachIndexed { index, vector ->
                if (index > 0) DroidRowDivider()
                DroidListRow(
                    title = SetToolReview.vectorLabel(vector),
                    onClick = { viewModel.updateForm { it.copy(attackVector = vector) } },
                    trailing = if (form.attackVector == vector) ({ StatusPill("Chosen", PillTone.Success) }) else null,
                )
            }
        }

        SectionLabel("2 · Target")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.targetHost,
                    onValueChange = { value -> viewModel.updateForm { it.copy(targetHost = value) } },
                    label = { Text("Target host") },
                    supportingText = { Text("Optional if a target email is set.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.targetEmail,
                    onValueChange = { value -> viewModel.updateForm { it.copy(targetEmail = value) } },
                    label = { Text("Target email") },
                    supportingText = { Text("Optional if a target host is set.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.payload,
                    onValueChange = { value -> viewModel.updateForm { it.copy(payload = value) } },
                    label = { Text("Payload (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionLabel("3 · Menu answers")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DroidSwitchRow(
                    title = "Start from the main menu",
                    subtitle = "Adds SET's own digits for ${SetToolReview.vectorLabel(form.attackVector)} before the answers below. Turn off if your version's menu is different.",
                    checked = form.useMenuPath,
                    onCheckedChange = { on -> viewModel.updateForm { it.copy(useMenuPath = on) } },
                    modifier = Modifier.testTag("set_use_menu_path"),
                )
                Text(
                    "The rest: what SET asks after the vector is picked — target, template, listener, confirmations — in the order " +
                        "your version asks for it. Re-type the target/payload above here if SET asks for them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                form.menuAnswers.forEachIndexed { index, answer ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(answer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        DroidTextButton(
                            onClick = { viewModel.updateForm { f -> f.copy(menuAnswers = f.menuAnswers.filterIndexed { i, _ -> i != index }) } },
                        ) { Text("Remove") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = answerDraft,
                        onValueChange = { answerDraft = it },
                        label = { Text("Next answer") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    DroidSecondaryButton(
                        onClick = {
                            viewModel.updateForm { f -> f.copy(menuAnswers = f.menuAnswers + answerDraft) }
                            answerDraft = ""
                        },
                        enabled = answerDraft.isNotEmpty(),
                        modifier = Modifier.testTag("set_add_menu_answer"),
                    ) { Text("Add") }
                }
            }
        }

        SectionLabel("4 · Options (optional)")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                form.options.forEachIndexed { index, pair ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${pair.first} = ${pair.second}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        DroidTextButton(onClick = { viewModel.updateForm { f -> f.copy(options = f.options.filterIndexed { i, _ -> i != index }) } }) { Text("Remove") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = optionKey,
                        onValueChange = { optionKey = it },
                        label = { Text("Key, e.g. SMTP_SERVER") },
                        isError = keyProblem != null,
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
                keyProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                DroidSecondaryButton(
                    onClick = {
                        val key = optionKey
                        val value = optionValue
                        viewModel.updateForm { f -> f.copy(options = f.options + (key to value)) }
                        optionKey = ""
                        optionValue = ""
                    },
                    enabled = optionKey.isNotBlank() && keyProblem == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add option") }
            }
        }

        SectionLabel("5 · Review and launch")
        DroidCard(modifier = Modifier.testTag("set_review"), borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SetToolReview.summary(form).forEach { (label, value) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.35f))
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
        DroidPrimaryButton(
            onClick = viewModel::runAttack,
            enabled = SetToolReview.canLaunch(form, backend, running),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Launch") }
        SetToolReview.launchHint(form, backend)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        ToolRunStatusCard(uiState)
    }
}
