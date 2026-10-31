package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.deviceconnect.DeviceConnect
import ai.droidcommand.deviceconnect.DeviceConnectPhase
import ai.droidcommand.deviceconnect.DeviceKind
import ai.droidcommand.deviceconnect.DeviceStatus
import ai.droidcommand.deviceconnect.TrustedDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ConnectDeviceScreen(viewModel: ConnectDeviceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    DisposableEffect(Unit) {
        viewModel.onScreenOpen()
        onDispose { viewModel.onScreenClose() }
    }
    ConnectDeviceContent(
        state = state,
        onKind = viewModel::setKind,
        onWait = viewModel::waitHere,
        onJoin = viewModel::join,
        onPassword = viewModel::setPassword,
        onGenerate = viewModel::generatePassword,
        onAddress = viewModel::setAddress,
        onKeepListening = viewModel::setKeepListening,
        onConfirmCode = viewModel::confirmCode,
        onRejectCode = viewModel::rejectCode,
        onPing = viewModel::ping,
        onSendNote = viewModel::sendNote,
        onNoteText = viewModel::setNote,
        onRevoke = viewModel::revoke,
        onStop = viewModel::stopWaiting,
    )
}

@Composable
fun ConnectDeviceContent(
    state: ConnectDeviceUi,
    onKind: (DeviceKind) -> Unit,
    onWait: () -> Unit,
    onJoin: () -> Unit,
    onPassword: (String) -> Unit,
    onGenerate: () -> Unit,
    onAddress: (String) -> Unit,
    onKeepListening: (Boolean) -> Unit,
    onConfirmCode: () -> Unit,
    onRejectCode: () -> Unit,
    onPing: (String) -> Unit,
    onSendNote: (String) -> Unit,
    onNoteText: (String) -> Unit,
    onRevoke: (String) -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("connect_device"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitleWithHelp("Connect a device", ScreenHelpCatalog.CONNECT_DEVICE)
        Text(
            "Link this phone to a computer or another phone on the same Wi-Fi. " +
                "You both type the same one-time password. The password is not sent as readable text.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionLabel("What are you connecting?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.kind == DeviceKind.PC,
                onClick = { onKind(DeviceKind.PC) },
                label = { Text("A computer") },
                modifier = Modifier.testTag("connect_kind_pc"),
            )
            FilterChip(
                selected = state.kind == DeviceKind.PHONE,
                onClick = { onKind(DeviceKind.PHONE) },
                label = { Text("Another phone") },
                modifier = Modifier.testTag("connect_kind_phone"),
            )
        }
        SectionLabel("One-time password")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPassword,
                    modifier = Modifier.fillMaxWidth().testTag("connect_password"),
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                )
                OutlinedButton(onClick = onGenerate, modifier = Modifier.fillMaxWidth()) {
                    Text("Make a password for me")
                }
                Text(
                    "At least ${DeviceConnect.MIN_PASSWORD_CHARS} characters. Share it only with the device you are pairing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SectionLabel("This phone waits")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (state.status.listening) {
                        "Waiting on this Wi-Fi. On the other device, type one of these addresses or the invite below."
                    } else {
                        "Nothing is listening until you tap Wait here. Leaving this screen stops it unless you keep it on."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.status.listening) {
                    state.status.localAddresses.forEach { host ->
                        Text("$host:${state.status.port}", style = MaterialTheme.typography.titleMedium)
                    }
                    state.status.invite?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("connect_invite"))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Keep waiting after I leave this screen")
                    Switch(
                        checked = state.status.keepListening,
                        onCheckedChange = onKeepListening,
                        modifier = Modifier.testTag("connect_keep_listening"),
                    )
                }
                if (state.status.listening) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop waiting") }
                } else {
                    Button(onClick = onWait, modifier = Modifier.fillMaxWidth().testTag("connect_wait")) { Text("Wait here") }
                }
            }
        }
        SectionLabel("Join the other device")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.address,
                    onValueChange = onAddress,
                    modifier = Modifier.fillMaxWidth().testTag("connect_address"),
                    singleLine = true,
                    label = { Text("Address or invite") },
                    supportingText = { Text("Example: 192.168.1.8:17890") },
                )
                Button(onClick = onJoin, modifier = Modifier.fillMaxWidth().testTag("connect_join")) { Text("Join") }
            }
        }
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("connect_message"))
        }
        if (state.status.peers.isNotEmpty()) {
            SectionLabel("Paired devices")
            state.status.peers.forEach { peer ->
                PairedDeviceCard(
                    peer = peer,
                    note = state.note,
                    onNote = onNoteText,
                    onPing = { onPing(peer.deviceId) },
                    onSend = { onSendNote(peer.deviceId) },
                    onRevoke = { onRevoke(peer.deviceId) },
                )
            }
        }
        Text(
            "After pairing, this only checks that the other device is there, reads a short status, or puts a note in Chat. " +
                "Anything that can change the phone still needs Remote control in Settings, which stays off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val code = state.status.verificationCode
    if (code != null && state.status.phase == DeviceConnectPhase.VERIFY) {
        AlertDialog(
            onDismissRequest = onRejectCode,
            title = { Text("Do both codes match?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The other device should show the same code. If it does not, tap No.")
                    Text(code, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("connect_verify_code"))
                    state.status.peerName?.let { Text("Other device: $it") }
                }
            },
            confirmButton = { TextButton(onClick = onConfirmCode) { Text("Yes, they match") } },
            dismissButton = { TextButton(onClick = onRejectCode) { Text("No") } },
        )
    }
}

@Composable
private fun PairedDeviceCard(
    peer: TrustedDevice,
    note: String,
    onNote: (String) -> Unit,
    onPing: () -> Unit,
    onSend: () -> Unit,
    onRevoke: () -> Unit,
) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(peer.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                if (peer.kind == DeviceKind.PC) "Computer" else "Phone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPing) { Text("Check connection") }
                OutlinedButton(onClick = onRevoke) { Text("Forget") }
            }
            OutlinedTextField(
                value = note,
                onValueChange = onNote,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Note for Chat") },
            )
            Button(onClick = onSend, enabled = note.isNotBlank()) { Text("Send note to Chat") }
        }
    }
}

data class ConnectDeviceUi(
    val kind: DeviceKind = DeviceKind.PHONE,
    val password: String = "",
    val address: String = "",
    val note: String = "",
    val message: String? = null,
    val status: DeviceStatus = DeviceStatus(
        phase = DeviceConnectPhase.IDLE,
        listening = false,
        keepListening = false,
        localAddresses = emptyList(),
        port = null,
        deviceId = null,
        invite = null,
        verificationCode = null,
        peerName = null,
        peers = emptyList(),
    ),
)
