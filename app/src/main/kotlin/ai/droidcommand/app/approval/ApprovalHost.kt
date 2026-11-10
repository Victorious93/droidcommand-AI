package ai.droidcommand.app.approval

import ai.droidcommand.app.ui.theme.DroidTextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/**
 * Placed once at the app root ([ai.droidcommand.app.MainActivity]) so a
 * confirmation dialog can appear above whatever screen is currently
 * showing. Every `SENSITIVE`/`ROOT` tool — `run_metasploit_module` and
 * `run_setoolkit_attack` included — routes through [ComposeApprovalPrompt],
 * so this one host covers every tool's confirmation, not just the security
 * tools' screens. The text scrolls, because an approval can show a long
 * command or prompt.
 */
@Composable
fun ApprovalHost(prompt: ComposeApprovalPrompt) {
    val pending by prompt.pending.collectAsState()
    val request = pending
    if (request != null) {
        AlertDialog(
            onDismissRequest = { prompt.resolve(false) },
            title = { Text("Confirm action") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(request.reason)
                }
            },
            confirmButton = {
                DroidTextButton(onClick = { prompt.resolve(true) }) { Text("Approve") }
            },
            dismissButton = {
                DroidTextButton(onClick = { prompt.resolve(false) }) { Text("Deny") }
            },
        )
    }
}
