package ai.droidcommand.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Phase 0 stub. Real API-key entry (backed by Android Keystore via a
 * `KeystoreSecretsVault`) is Phase 1 — see docs/ARCHITECTURE.md's Consumer
 * Roadmap section.
 */
@Composable
fun SettingsScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            "API key entry and provider/model configuration arrive in Phase 1. " +
                "This screen is an intentional stub.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
