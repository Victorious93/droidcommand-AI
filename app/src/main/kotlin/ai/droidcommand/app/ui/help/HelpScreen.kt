package ai.droidcommand.app.ui.help

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ux.ScreenHelpCatalog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * In-app Help for a first-time owner. Technical remote-control notes stay in
 * `docs/MANUAL.md`; this screen does not dump them.
 */
@Composable
fun HelpScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("help_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitleWithHelp("Help", ScreenHelpCatalog.HELP)
        HelpCard(
            title = "What this app is",
            body = "DroidCommand is AI chat on this phone, plus a list of your other Victorious apps. " +
                "Pick a model so Chat can answer without a cloud key. Pair an app only if you trust that install. " +
                "Remote control stays off unless you turn it on in Settings. Anything that can change the phone still asks first.",
        )
        HelpCard(
            title = "The four tabs",
            body = "Fleet is your other apps. AI Chat is where you ask questions. Models is where you pick or download " +
                "an on-phone brain. Settings holds keys, voice, and extras (one-step commands, plans, memory, logs, security tools).",
        )
        HelpCard(
            title = "Pairing",
            body = "Pairing stores that you trust the install that is on this phone now. If that app is later replaced, " +
                "the connection stops until you pair again. Pairing does not turn on remote control and does not run a command.",
        )
        HelpCard(
            title = "Models and keys",
            body = "A .gguf file on this phone lets Chat work without a cloud key. You can pick a file you already have " +
                "or download a free one from the catalog. Cloud keys are stored encrypted and are never shown again after you save them.",
        )
        HelpCard(
            title = "What this app will not do without asking",
            body = "Wipe, power-off, lock, flashing, and security-tool runs still show a confirmation. " +
                "Remote commands from another app are ignored until you turn remote control on. " +
                "Those protections are not changed by this help text.",
        )
    }
}

@Composable
private fun HelpCard(title: String, body: String) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
