package ai.droidcommand.app.companionassist

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.AccentRed
import ai.droidcommand.security.CompanionAssist
import ai.droidcommand.security.CompanionAssistRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Real Allow/Deny consent. [onAllow] is only invoked from the Allow tap;
 * nothing here auto-approves or runs the model. A destructive flag only
 * adds a confirmation warning — this screen never runs tools.
 */
@Composable
fun CompanionAssistConsentScreen(
    callerPackage: String,
    displayName: String,
    request: CompanionAssistRequest,
    running: Boolean,
    onAllow: (typedPassword: String?) -> Unit,
    onDeny: () -> Unit,
    needsPassword: Boolean = false,
    observedCert: String? = null,
    pairingNote: String? = null,
) {
    var typedPassword by remember { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
        ) {
            ScreenTitle("Companion assist request")
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DroidCard {
                    SectionLabel("Caller")
                    Text(displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(callerPackage, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    SectionLabel("Capability")
                    Text(request.capability, style = MaterialTheme.typography.bodyLarge)
                    observedCert?.let { cert ->
                        Spacer(Modifier.height(8.dp))
                        SectionLabel("Observed signing cert")
                        Text(cert, style = MaterialTheme.typography.bodySmall)
                    }
                    pairingNote?.let { note ->
                        Spacer(Modifier.height(8.dp))
                        Text(note, style = MaterialTheme.typography.bodySmall, color = AccentRed)
                    }
                    if (needsPassword) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = typedPassword,
                            onValueChange = { typedPassword = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Pairing password") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false,
                            ),
                            enabled = !running,
                        )
                    }
                    if (request.destructive) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "This request is marked destructive. Allow is the confirmation. " +
                                "DroidCommand AI will only answer with text — it will not run " +
                                "tools, remote.command, or device actions.",
                            color = AccentRed,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                DroidCard {
                    SectionLabel("Prompt preview")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = CompanionAssist.preview(CompanionAssist.buildPrompt(request)),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (running) {
                Text(
                    "Running DroidCommand AI…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Deny")
                }
                Button(
                    onClick = { onAllow(typedPassword.takeIf { needsPassword }) },
                    enabled = !running && (!needsPassword || typedPassword.isNotBlank()),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Allow")
                }
            }
        }
    }
}
