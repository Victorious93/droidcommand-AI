package ai.droidcommand.app.ui.pchost

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.settings.KeyStatus
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.llm.pchost.PcHostCopy
import ai.droidcommand.llm.pchost.PcHostKind
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
 * Settings, PC-hosted models: one card per server kind with its address, optional key, a connection test, the
 * models the server lists (each marked "on your PC" or "cloud-backed") and the switch that makes AI Chat use it.
 * Compiles; never run on a device.
 */
@Composable
fun PcHostScreen(viewModel: PcHostViewModel = hiltViewModel(), onOpenModels: () -> Unit = {}) {
    val cards by viewModel.cards.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitleWithHelp("PC-hosted models", ScreenHelpCatalog.PC_HOSTS)
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(PcHostCopy.TWO_WORKFLOWS, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(PcHostCopy.TOOLS, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(PcHostCopy.CHAT_ONLY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DroidSecondaryButton(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) { Text("Import a model file onto this phone instead") }
            }
        }
        cards.forEach { card ->
            SectionLabel(card.kind.label)
            ServerCard(card, viewModel)
        }
    }
}

@Composable
private fun ServerCard(card: PcHostCard, viewModel: PcHostViewModel) {
    val kind = card.kind
    // Starts from the saved address once it has loaded; after that it is whatever the owner is typing.
    var draft by remember(card.address) { mutableStateOf(card.address.orEmpty()) }
    var typedModel by remember(card.address) { mutableStateOf("") }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (kind) {
                    PcHostKind.OLLAMA -> "The address of the PC running Ollama, on your Wi-Fi or VPN. Port 11434 is added if you leave it out."
                    PcHostKind.OPENAI_COMPATIBLE -> "The address and port of an OpenAI-compatible server on your PC (llama.cpp's llama-server, LM Studio, vLLM). A trailing /v1 is fine."
                },
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("PC address, e.g. ${kind.example}") },
                isError = card.addressError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            card.addressError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            card.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (card.testing) {
                    DroidSecondaryButton(onClick = { viewModel.cancelTest(kind) }) { Text("Cancel test") }
                } else {
                    DroidPrimaryButton(onClick = { viewModel.saveAndTest(kind, draft) }, enabled = draft.isNotBlank()) { Text("Save and test connection") }
                }
                DroidTextButton(onClick = { viewModel.forget(kind) }, enabled = card.address != null) { Text("Forget") }
            }
            card.testMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (card.testOk == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }

            if (card.address != null) {
                Text(
                    if (card.model.isBlank()) "No model chosen yet." else "Model: ${card.model} — ${card.modelLocation.orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                card.models.forEach { model ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = model.id == card.model, onClick = { viewModel.pickModel(kind, model) })
                        Column {
                            Text(model.id, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull(model.locationLabel(), model.detail).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = muted,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = typedModel,
                    onValueChange = { typedModel = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Or type a model name") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
                DroidSecondaryButton(
                    onClick = {
                        viewModel.typeModel(kind, typedModel)
                        typedModel = ""
                    },
                    enabled = typedModel.isNotBlank(),
                ) { Text("Use this name") }

                card.toolStatus?.let { status ->
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    card.toolDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
                    DroidSecondaryButton(onClick = { viewModel.checkTools(kind) }, enabled = !card.checkingTools) {
                        Text(if (card.checkingTools) "Asking the server…" else "Check tool support")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = card.toolsForced, onCheckedChange = { viewModel.setToolsForced(kind, it) })
                        Text(
                            "Offer tools anyway. For a server that cannot report tool support. If the model cannot use them, the reply says so.",
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    }
                }

                if (card.usedByChat) {
                    Text("AI Chat sends its messages to this server.", style = MaterialTheme.typography.bodyMedium)
                    DroidSecondaryButton(onClick = { viewModel.stopUsingInChat(kind) }, modifier = Modifier.fillMaxWidth()) { Text("Stop using in AI Chat") }
                } else {
                    DroidPrimaryButton(onClick = { viewModel.useInChat(kind) }, enabled = card.model.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text("Use in AI Chat")
                    }
                }
            }

            KeyRow(card.keyStatus, onSave = { viewModel.saveKey(kind, it) }, onClear = { viewModel.clearKey(kind) })
        }
    }
}

@Composable
private fun KeyRow(status: KeyStatus, onSave: (String) -> Unit, onClear: () -> Unit) {
    // Not rememberSaveable: a typed-but-unsaved key must not be written into the saved-instance Bundle.
    var input by remember { mutableStateOf("") }
    Text(
        "API key (optional): only if you set one on the server. Stored encrypted on this phone, sent only to this server, never shown again. " +
            when (status) {
                KeyStatus.NOT_SET -> "None saved."
                KeyStatus.SAVED -> "A key is saved."
                KeyStatus.NEEDS_REENTRY -> "The stored key can no longer be decrypted — enter it again."
                KeyStatus.STORAGE_ERROR -> "Storage error — try again."
            },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("API key (optional)") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DroidSecondaryButton(onClick = {
            onSave(input)
            input = ""
        }, enabled = input.isNotBlank()) { Text("Save key") }
        DroidTextButton(onClick = onClear, enabled = status != KeyStatus.NOT_SET) { Text("Remove key") }
    }
}
