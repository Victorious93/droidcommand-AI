package ai.droidcommand.app.companionexec

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.security.ExecConsent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Shows exactly what will run. [onAllow] is only reachable from the Allow tap; nothing auto-approves. */
@Composable
fun CompanionExecConsentScreen(
    consent: ExecConsent,
    running: Boolean,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onExpired: () -> Unit,
) {
    var remainingMs by remember { mutableLongStateOf(consent.expiresAtMs - System.currentTimeMillis()) }
    LaunchedEffect(consent.expiresAtMs) {
        while (remainingMs > 0) {
            delay(500)
            remainingMs = consent.expiresAtMs - System.currentTimeMillis()
        }
        onExpired()
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            ScreenTitle("Run on this device?")
            Spacer(Modifier.height(12.dp))
            DroidCard {
                SectionLabel("Asked by")
                Text(consent.caller, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                SectionLabel("Will run (exactly this)")
                Text(consent.capability.id, style = MaterialTheme.typography.bodyLarge)
                Text(consent.capability.description, style = MaterialTheme.typography.bodyMedium)
                Text("Risk: ${consent.capability.risk}", style = MaterialTheme.typography.bodySmall)
                if (consent.args.isNotEmpty()) Text("Arguments: ${consent.args}", style = MaterialTheme.typography.bodySmall)
                if (consent.reason.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    SectionLabel("Reason given by the caller (not verified)")
                    Text(consent.reason, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Allow runs this one request and nothing else. It is not a standing permission. " +
                        "Expires in ${(remainingMs.coerceAtLeast(0) / 1000)} s.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DroidSecondaryButton(onClick = onDeny, enabled = !running, modifier = Modifier.weight(1f)) { Text("Deny") }
                DroidPrimaryButton(onClick = onAllow, enabled = !running && remainingMs > 0, modifier = Modifier.weight(1f)) { Text("Allow") }
            }
        }
    }
}
