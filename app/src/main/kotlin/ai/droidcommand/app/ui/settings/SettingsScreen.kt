package ai.droidcommand.app.ui.settings

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsState()
    val searchStatus by viewModel.searchStatus.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Settings")
        SectionLabel("API keys (stored encrypted on this device)")
        CloudProviderCatalog.all.forEach { provider ->
            ApiKeyRow(
                label = provider.label,
                status = status[provider] ?: KeyStatus.NOT_SET,
                onSave = { viewModel.save(provider, it) },
                onClear = { viewModel.clear(provider) },
            )
        }
        SectionLabel("Web search keys (optional — used only when the chat Web search toggle is on)")
        WebSearchCatalog.all.forEach { slot ->
            ApiKeyRow(
                label = slot.label,
                status = searchStatus[slot] ?: KeyStatus.NOT_SET,
                onSave = { viewModel.saveSearchKey(slot, it) },
                onClear = { viewModel.clearSearchKey(slot) },
            )
        }
        VoiceSettingsSection()
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
