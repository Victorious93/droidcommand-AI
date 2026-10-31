package ai.droidcommand.app.ui.settings

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.SectionLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
 * Password pairing settings. OFF by default. Does not turn on
 * external control. Excluded apps cannot be linked.
 */
@Composable
fun PasswordPairingSection(viewModel: PasswordPairingViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsState()
    var password by remember { mutableStateOf("") }
    var linkPackage by remember { mutableStateOf("") }
    var linkPassword by remember { mutableStateOf("") }
    SectionLabel("Password pairing")
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Link by password", style = MaterialTheme.typography.titleMedium)
            Text(
                "OFF by default. A user-set password can link a pending-refused inbound app " +
                    "or an outbound cert mismatch after an on-screen confirm. The password is " +
                    "stored hashed. Later reconnects refuse if the signing cert changes. " +
                    "Excluded apps can never be linked. HackerAI, Pentest-Swarm, and EDL still " +
                    "need the same-signer OS permission — a password link does not bypass it. " +
                    "External control stays OFF.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Allow password pairing")
                Switch(checked = ui.enabled, onCheckedChange = viewModel::setEnabled)
            }
            Text(
                if (ui.hasPassword) "Pairing password is set (hashed)." else "No pairing password is set.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("New pairing password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.setPassword(password)
                        password = ""
                    },
                    enabled = password.isNotBlank(),
                ) { Text("Save password") }
                OutlinedButton(onClick = viewModel::clearPassword, enabled = ui.hasPassword) {
                    Text("Clear")
                }
            }
            Text(
                "Link an installed inbound app on this device (package + observed cert, TOFU).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = linkPackage,
                onValueChange = { linkPackage = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Package name") },
            )
            OutlinedTextField(
                value = linkPassword,
                onValueChange = { linkPassword = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Pairing password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            Button(
                onClick = {
                    viewModel.linkInstalled(linkPackage.trim(), linkPassword)
                    linkPassword = ""
                },
                enabled = ui.enabled && ui.hasPassword && linkPackage.isNotBlank() && linkPassword.isNotBlank(),
            ) { Text("Link installed package") }
            if (ui.links.isEmpty()) {
                Text("No password links.", style = MaterialTheme.typography.bodySmall)
            } else {
                ui.links.forEach { link ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(link.packageName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { viewModel.revoke(link.packageName) }) { Text("Revoke") }
                    }
                }
            }
            ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
