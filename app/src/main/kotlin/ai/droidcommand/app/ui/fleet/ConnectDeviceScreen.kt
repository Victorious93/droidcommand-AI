package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.DroidGroup
import ai.droidcommand.app.ui.components.DroidListRow
import ai.droidcommand.app.ui.components.DroidNote
import ai.droidcommand.app.ui.components.DroidRowDivider
import ai.droidcommand.app.ui.components.DroidSwitchRow
import ai.droidcommand.app.ui.components.DroidTabs
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.components.StatusPill
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.deviceconnect.DeviceConnect
import ai.droidcommand.deviceconnect.DeviceConnectPhase
import ai.droidcommand.deviceconnect.DeviceKind
import ai.droidcommand.deviceconnect.DeviceStatus
import ai.droidcommand.deviceconnect.TrustedDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    var mode by rememberSaveable { mutableStateOf(ConnectMode.WAIT) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val (statusText, statusTone) = ConnectDevicePresentation.statusLine(state.status)
    val passwordProblem = ConnectDevicePresentation.passwordProblem(state.password)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("connect_device"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitleWithHelp("Connect a device", ScreenHelpCatalog.CONNECT_DEVICE)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Link this phone with a computer or another phone on the same Wi-Fi. Both sides type the same one-time password; it is never sent as readable text.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            StatusPill(statusText, statusTone)
        }

        SectionLabel("1 · What are you connecting?")
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

        SectionLabel("2 · One-time password")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPassword,
                    modifier = Modifier.fillMaxWidth().testTag("connect_password"),
                    singleLine = true,
                    label = { Text("Password") },
                    isError = passwordProblem != null,
                    supportingText = {
                        Text(passwordProblem ?: "At least ${DeviceConnect.MIN_PASSWORD_CHARS} characters. Share it only with the device you are pairing.")
                    },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                )
                // A made-up password is no use if it cannot be read: show it, so it can be typed on the other device.
                DroidSecondaryButton(
                    onClick = {
                        onGenerate()
                        showPassword = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Make one for me and show it") }
            }
        }

        SectionLabel("3 · Link up")
        DroidTabs(
            labels = ConnectMode.entries.map { it.label },
            selectedIndex = mode.ordinal,
            onSelect = { mode = ConnectMode.entries[it] },
        )
        when (mode) {
            ConnectMode.WAIT -> WaitCard(state, context, onKeepListening, onWait, onStop)
            ConnectMode.JOIN -> JoinCard(state, onAddress, onJoin)
        }
        state.message?.let {
            DroidNote(it, modifier = Modifier.testTag("connect_message"))
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
        DroidNote(
            "After pairing, this only checks that the other device is there, reads a short status, or puts a note in Chat. " +
                "Anything that can change the phone still needs Remote control in Settings, which stays off.",
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
            confirmButton = { DroidTextButton(onClick = onConfirmCode) { Text("Yes, they match") } },
            dismissButton = { DroidTextButton(onClick = onRejectCode) { Text("No") } },
        )
    }
}

@Composable
private fun WaitCard(
    state: ConnectDeviceUi,
    context: Context,
    onKeepListening: (Boolean) -> Unit,
    onWait: () -> Unit,
    onStop: () -> Unit,
) {
    val listening = state.status.listening
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (listening) {
                    "Waiting on this Wi-Fi. On the other device, type one of these addresses, or paste the invite."
                } else {
                    "Nothing is listening until you tap Wait here. Leaving this screen stops it unless you keep it on."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (listening) {
                DroidGroup {
                    val addresses = state.status.localAddresses.map { "$it:${state.status.port}" }
                    addresses.forEachIndexed { index, address ->
                        if (index > 0) DroidRowDivider()
                        DroidListRow(
                            title = address,
                            trailing = { DroidTextButton(onClick = { copyText(context, "Address", address) }) { Text("Copy") } },
                        )
                    }
                    state.status.invite?.let { invite ->
                        if (addresses.isNotEmpty()) DroidRowDivider()
                        DroidListRow(
                            title = "Invite",
                            subtitle = invite,
                            modifier = Modifier.testTag("connect_invite"),
                            trailing = { DroidTextButton(onClick = { copyText(context, "Invite", invite) }) { Text("Copy") } },
                        )
                    }
                }
            }
            DroidSwitchRow(
                title = "Keep waiting after I leave this screen",
                checked = state.status.keepListening,
                onCheckedChange = onKeepListening,
                modifier = Modifier.testTag("connect_keep_listening"),
            )
            if (listening) {
                DroidSecondaryButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop waiting") }
            } else {
                val ready = ConnectDevicePresentation.canWait(state.password)
                DroidPrimaryButton(onClick = onWait, enabled = ready, modifier = Modifier.fillMaxWidth().testTag("connect_wait")) { Text("Wait here") }
                ConnectDevicePresentation.waitHint(state.password)?.let { HintText(it) }
            }
        }
    }
}

@Composable
private fun JoinCard(state: ConnectDeviceUi, onAddress: (String) -> Unit, onJoin: () -> Unit) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.address,
                onValueChange = onAddress,
                modifier = Modifier.fillMaxWidth().testTag("connect_address"),
                singleLine = true,
                label = { Text("Address or invite") },
                supportingText = { Text("Example: 192.168.1.8:17890, or paste the invite the other device shows.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            DroidPrimaryButton(
                onClick = onJoin,
                enabled = ConnectDevicePresentation.canJoin(state.password, state.address),
                modifier = Modifier.fillMaxWidth().testTag("connect_join"),
            ) { Text("Join") }
            ConnectDevicePresentation.joinHint(state.password, state.address)?.let { HintText(it) }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(peer.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusPill(if (peer.kind == DeviceKind.PC) "Computer" else "Phone")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DroidSecondaryButton(onClick = onPing) { Text("Check connection") }
                DroidTextButton(onClick = onRevoke) { Text("Forget") }
            }
            OutlinedTextField(
                value = note,
                onValueChange = onNote,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Note for Chat") },
            )
            DroidPrimaryButton(onClick = onSend, enabled = note.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Send note to Chat") }
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
