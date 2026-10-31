package ai.droidcommand.app.ui.settings

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.app.mode.ModeLabels
import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.app.prefs.CodingTunnelChoice
import ai.droidcommand.app.ui.chat.ToolbarToggle
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.llm.factory.CloudProviderCatalog
import ai.droidcommand.llm.factory.WebSearchCatalog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Phase 1 API-key entry. UNBUILT/UNTESTED (no Android SDK here). Keys go straight to the
 * Keystore-backed vault; this screen never displays a stored key, only its [KeyStatus].
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onOpenHelp: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    onOpenAdvanced: () -> Unit = {},
    onReplaySetup: () -> Unit = {},
) {
    val status by viewModel.status.collectAsState()
    val searchStatus by viewModel.searchStatus.collectAsState()
    val hfStatus by viewModel.hfStatus.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitleWithHelp("Settings", ScreenHelpCatalog.SETTINGS)
        SectionLabel("Setup")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "See the first-run guide again: what this app is, pick a model, pair your other apps, and optional permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onReplaySetup, modifier = Modifier.fillMaxWidth()) { Text("Show setup guide again") }
            }
        }
        SectionLabel("API keys (stored encrypted on this device)")
        DroidCard {
            Text(
                "Chat uses the provider last chosen on the Chat screen. " +
                    "Without a key for that provider — and without an on-device model — Chat cannot send. A key is never shown again after you save it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionLabel("On-device GGUF models")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Import a .gguf from this device, scan Download/models, or download a free Hugging Face catalog model. Chat uses the selected model on the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) { Text("On-device models") }
            }
        }
        CloudProviderCatalog.all.forEach { provider ->
            ApiKeyRow(
                label = provider.label,
                status = status[provider] ?: KeyStatus.NOT_SET,
                onSave = { viewModel.save(provider, it) },
                onClear = { viewModel.clear(provider) },
            )
        }
        SectionLabel("Web search keys (optional — used only when the chat Web search toggle is on)")
        SectionLabel("Hugging Face (optional)")
        ApiKeyRow(
            label = "Hugging Face token",
            status = hfStatus,
            onSave = viewModel::saveHuggingFaceToken,
            onClear = viewModel::clearHuggingFaceToken,
        )
        Text(
            "Not required. Public GGUF search and download work without it. A token is stored encrypted like the provider keys and is only sent to huggingface.co.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WebSearchCatalog.all.forEach { slot ->
            ApiKeyRow(
                label = slot.label,
                status = searchStatus[slot] ?: KeyStatus.NOT_SET,
                onSave = { viewModel.saveSearchKey(slot, it) },
                onClear = { viewModel.clearSearchKey(slot) },
            )
        }
        VoiceSettingsSection()
        SectionLabel("Help")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "What this app is, and what it will never do without asking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenHelp, modifier = Modifier.fillMaxWidth()) { Text("Open Help") }
            }
        }
        SectionLabel("Advanced")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "One-step commands, plans, memory, security tools, and remote control live on the Advanced tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenAdvanced, modifier = Modifier.fillMaxWidth()) { Text("Open Advanced") }
            }
        }
    }
}

@Composable
internal fun ModeNamesSection(viewModel: SettingsViewModel) {
    val mode by viewModel.modeState.collectAsState()
    var pilotDraft by remember { mutableStateOf<String?>(null) }
    var forgeDraft by remember { mutableStateOf<String?>(null) }
    val pilotText = pilotDraft ?: mode.pilotLabel
    val forgeText = forgeDraft ?: mode.forgeLabel
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Mode 1 is the Pilot engine (one tool). Mode 2 is the Forge engine (an objective). A new name is only a label — it is saved on this device and does not change what either engine does. Leave a field blank to restore its default. At most ${ModeLabels.MAX_LENGTH} characters.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = pilotText,
                onValueChange = { pilotDraft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Mode 1 label (Pilot)") },
            )
            OutlinedTextField(
                value = forgeText,
                onValueChange = { forgeDraft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Mode 2 label (Forge)") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    viewModel.renameMode(AgentMode.PILOT, pilotText)
                    viewModel.renameMode(AgentMode.FORGE, forgeText)
                    pilotDraft = null
                    forgeDraft = null
                }) { Text("Save names") }
                OutlinedButton(onClick = {
                    viewModel.resetModeLabels()
                    pilotDraft = null
                    forgeDraft = null
                }) { Text("Reset") }
            }
        }
    }
}

@Composable
internal fun AdvancedSection(viewModel: SettingsViewModel) {
    val toolbar by viewModel.toolbarToggles.collectAsState()
    val publicLimit by viewModel.publicClipLimit.collectAsState()
    val codingTunnel by viewModel.codingTunnel.collectAsState()
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Chat toolbar", style = MaterialTheme.typography.titleMedium)
            Text(
                "Hides a control from the chat toolbar. It does not change what the control does.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToolbarToggle.entries.forEach { toggle ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(toggle.label)
                    Switch(checked = toggle in toolbar, onCheckedChange = { viewModel.setToolbarToggle(toggle, it) })
                }
            }
            Text("Public clipboard history size", style = MaterialTheme.typography.titleMedium)
            AdvancedPrefs.PUBLIC_LIMIT_CHOICES.forEach { choice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = choice == publicLimit, onClick = { viewModel.setPublicClipLimit(choice) })
                    Text("$choice clips")
                }
            }
            Text("Coding tunnel", style = MaterialTheme.typography.titleMedium)
            Text(
                "On this device runs the plan, edit, and report stand-in. Paired is saved but not connected: no cloud host is built.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CodingTunnelChoice.entries.forEach { choice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = choice == codingTunnel, onClick = { viewModel.setCodingTunnel(choice) })
                    Text(if (choice == CodingTunnelChoice.LOCAL) "On this device" else "Paired (not connected)")
                }
            }
        }
    }
}

@Composable
private fun ApiKeyRow(label: String, status: KeyStatus, onSave: (String) -> Unit, onClear: () -> Unit) {
    // Not rememberSaveable: a typed-but-unsaved key must not be written into the saved-instance Bundle.
    var input by remember { mutableStateOf("") }
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(statusText(status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("API key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onSave(input)
                    input = ""
                }, enabled = input.isNotBlank()) { Text("Save") }
                OutlinedButton(onClick = onClear, enabled = status != KeyStatus.NOT_SET) { Text("Remove") }
            }
        }
    }
}

private fun statusText(status: KeyStatus) = when (status) {
    KeyStatus.NOT_SET -> "Not set"
    KeyStatus.SAVED -> "Saved"
    KeyStatus.NEEDS_REENTRY -> "Stored key can no longer be decrypted — enter it again"
    KeyStatus.STORAGE_ERROR -> "Storage error — try again"
}
