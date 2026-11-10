package ai.droidcommand.app.ui.remotepc

import ai.droidcommand.app.remotepc.RemotePcIntents
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.theme.AccentRed
import ai.droidcommand.app.ui.theme.AccentSuccess
import ai.droidcommand.app.ui.theme.DroidDangerButton
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.remotepc.CommandConfirmState
import ai.droidcommand.remotepc.OpenPcRequest
import ai.droidcommand.remotepc.OpenPcResultCopy
import ai.droidcommand.remotepc.PcListState
import ai.droidcommand.remotepc.PcMode
import ai.droidcommand.remotepc.PcOnlineState
import ai.droidcommand.remotepc.PcProfile
import ai.droidcommand.remotepc.PcProfileContract
import ai.droidcommand.remotepc.PcProfileLoader
import ai.droidcommand.remotepc.SavedPcCommand
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private sealed class PcTileDialog {
    data class PickCommand(val profile: PcProfile) : PcTileDialog()
    data object AddCommand : PcTileDialog()
    data class DeleteCommand(val command: SavedPcCommand) : PcTileDialog()
}

/**
 * "My PC" hub tile. Off until Don turns it on. Lists the PCs paired in VictorSuite, shows whether each one
 * answers on its SSH port, and opens a terminal or files on it inside VictorSuite (`OPEN_PC`). This app holds
 * no SSH key: VictorSuite does the connecting and the host-key check.
 */
@Composable
fun RemotePcTile(viewModel: RemotePcViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<PcTileDialog?>(null) }
    var openingName by remember { mutableStateOf("your PC") }

    // Started for result so VictorSuite can read getCallingPackage() and re-check its pin and consent (§7.1).
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onOpenResult(
            ok = result.resultCode == Activity.RESULT_OK,
            error = result.data?.getStringExtra(PcProfileContract.RESULT_EXTRA_ERROR),
            pcName = openingName,
        )
    }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refresh()
    }

    fun open(profile: PcProfile, mode: PcMode) {
        openingName = profile.name
        try {
            openLauncher.launch(RemotePcIntents.toIntent(OpenPcRequest.build(profile.profileId, mode)))
        } catch (_: ActivityNotFoundException) {
            viewModel.showNotice(OpenPcResultCopy.NOT_INSTALLED)
        } catch (_: IllegalArgumentException) {
            viewModel.showNotice("VictorSuite sent a PC this app cannot open. Tap Refresh.")
        }
    }

    // Both confirmations are done: copy the command and open the PC's terminal in VictorSuite to paste it.
    LaunchedEffect(state.confirm) {
        if (state.confirm is CommandConfirmState.Approved) {
            val approved = viewModel.consumeApproved() ?: return@LaunchedEffect
            copyToClipboard(context, approved.command.label, approved.command.command)
            viewModel.showNotice("Copied \"${approved.command.label}\". Paste it in the terminal and press Enter.")
            open(approved.profile, PcMode.TERMINAL)
        }
    }

    DroidCard(modifier = Modifier.testTag("remote_pc_tile")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("My PC", style = MaterialTheme.typography.titleMedium)
            if (!state.enabled) {
                Text(
                    "See the PCs you paired in VictorSuite and open a terminal or files on them. Off until you turn it on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                DroidSecondaryButton(
                    onClick = { viewModel.setEnabled(true) },
                    modifier = Modifier.fillMaxWidth().testTag("remote_pc_turn_on"),
                ) { Text("Turn on My PC") }
                return@Column
            }

            state.notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("remote_pc_notice"))
            }
            when (val list = state.list) {
                PcListState.Loading -> Text("Looking for your PCs…", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                is PcListState.Unavailable -> Text(list.message, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                is PcListState.NeedsConsent -> {
                    Text(list.message, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    DroidPrimaryButton(
                        onClick = {
                            try {
                                consentLauncher.launch(RemotePcIntents.toIntent(OpenPcRequest.consent()))
                            } catch (_: ActivityNotFoundException) {
                                viewModel.showNotice(PcProfileLoader.PROVIDER_MISSING)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("remote_pc_consent"),
                    ) { Text("Allow in VictorSuite") }
                }
                is PcListState.Ready -> {
                    if (list.profiles.isEmpty()) {
                        Text(PcProfileLoader.NO_PCS, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    list.profiles.forEach { profile ->
                        PcRow(
                            profile = profile,
                            online = state.online[profile.profileId],
                            hasCommands = state.commands.isNotEmpty(),
                            onTerminal = { open(profile, PcMode.TERMINAL) },
                            onFiles = { open(profile, PcMode.SFTP) },
                            onRunCommand = { dialog = PcTileDialog.PickCommand(profile) },
                        )
                    }
                }
            }

            SavedCommandsSection(
                commands = state.commands,
                onAdd = { dialog = PcTileDialog.AddCommand },
                onDelete = { dialog = PcTileDialog.DeleteCommand(it) },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                DroidSecondaryButton(
                    onClick = viewModel::refresh,
                    modifier = Modifier.weight(1f).testTag("remote_pc_refresh"),
                ) { Text("Refresh") }
                DroidTextButton(
                    onClick = { viewModel.setEnabled(false) },
                    modifier = Modifier.testTag("remote_pc_turn_off"),
                ) { Text("Turn off") }
            }
        }
    }

    when (val shown = dialog) {
        is PcTileDialog.PickCommand -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Run a saved command on ${shown.profile.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.commands.forEach { command ->
                        DroidTextButton(
                            onClick = {
                                dialog = null
                                viewModel.startRun(shown.profile, command)
                            },
                            modifier = Modifier.fillMaxWidth().testTag("remote_pc_pick_${command.id}"),
                        ) { Text(command.label) }
                    }
                }
            },
            confirmButton = { DroidTextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        PcTileDialog.AddCommand -> AddCommandDialog(
            onSave = { label, command -> viewModel.addCommand(label, command).also { if (it == null) dialog = null } },
            onDismiss = { dialog = null },
        )
        is PcTileDialog.DeleteCommand -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete \"${shown.command.label}\"?") },
            text = { Text("This removes the saved command from this phone. It does not change anything on your PC.") },
            confirmButton = {
                DroidTextButton(onClick = {
                    dialog = null
                    viewModel.deleteCommand(shown.command.id)
                }) { Text("Delete") }
            },
            dismissButton = { DroidTextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
    }

    ConfirmCommandDialogs(
        state = state.confirm,
        onConfirm = viewModel::confirmStep,
        onCancel = viewModel::cancelRun,
    )
}

@Composable
private fun PcRow(
    profile: PcProfile,
    online: PcOnlineState?,
    hasCommands: Boolean,
    onTerminal: () -> Unit,
    onFiles: () -> Unit,
    onRunCommand: () -> Unit,
) {
    val (label, color) = onlineLabel(online)
    Column(
        modifier = Modifier.fillMaxWidth().testTag("remote_pc_row_${profile.profileId}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(profile.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.testTag("remote_pc_online_${profile.profileId}"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DroidPrimaryButton(
                onClick = onTerminal,
                modifier = Modifier.weight(1f).testTag("remote_pc_terminal_${profile.profileId}"),
            ) { Text("Terminal") }
            if (profile.supportsSftp) {
                DroidSecondaryButton(
                    onClick = onFiles,
                    modifier = Modifier.weight(1f).testTag("remote_pc_files_${profile.profileId}"),
                ) { Text("Files") }
            }
        }
        if (hasCommands) {
            DroidTextButton(onClick = onRunCommand, modifier = Modifier.testTag("remote_pc_run_${profile.profileId}")) {
                Text("Run a saved command")
            }
        }
    }
}

@Composable
private fun onlineLabel(state: PcOnlineState?): Pair<String, Color> = when (state) {
    is PcOnlineState.Online -> "Online" to AccentSuccess
    is PcOnlineState.Offline -> (if (state.timedOut) "Not answering" else "Offline") to AccentRed
    PcOnlineState.Checking, null -> "Checking…" to TextSecondary
}

@Composable
private fun SavedCommandsSection(
    commands: List<SavedPcCommand>,
    onAdd: () -> Unit,
    onDelete: (SavedPcCommand) -> Unit,
) {
    Text("Saved commands", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    if (commands.isEmpty()) {
        Text(
            "Save commands you use often. Each one asks before it runs, and risky ones ask twice.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
    commands.forEach { command ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("remote_pc_cmd_${command.id}")) {
            Column(Modifier.weight(1f)) {
                Text(command.label, style = MaterialTheme.typography.bodyMedium)
                Text(command.command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextSecondary)
            }
            DroidTextButton(onClick = { onDelete(command) }) { Text("Delete") }
        }
    }
    DroidTextButton(onClick = onAdd, modifier = Modifier.testTag("remote_pc_add_command")) { Text("Add a command") }
}

@Composable
private fun AddCommandDialog(onSave: (String, String) -> String?, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a command") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Command") })
                Text("Never put a password or key here.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            DroidTextButton(onClick = { error = onSave(label, command) }, modifier = Modifier.testTag("remote_pc_save_command")) { Text("Save") }
        },
        dismissButton = { DroidTextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** First confirm for every saved command; a second, red confirm for risky ones. */
@Composable
private fun ConfirmCommandDialogs(
    state: CommandConfirmState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        is CommandConfirmState.FirstConfirm -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Run \"${state.command.label}\" on ${state.profile.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.command.command, fontFamily = FontFamily.Monospace)
                    Text(
                        "VictorSuite will open a terminal on ${state.profile.name}. The command is copied so you can paste it there.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                DroidTextButton(onClick = onConfirm, modifier = Modifier.testTag("remote_pc_confirm_1")) {
                    Text(if (state.risk.risky) "Continue" else "Run")
                }
            },
            dismissButton = { DroidTextButton(onClick = onCancel) { Text("Cancel") } },
        )
        is CommandConfirmState.SecondConfirm -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Are you sure?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.command.command, fontFamily = FontFamily.Monospace)
                    Text(
                        "This command ${state.risk.reasons.joinToString(", ")}. It may not be undoable.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                DroidDangerButton(onClick = onConfirm, modifier = Modifier.testTag("remote_pc_confirm_2")) { Text("Yes, run it") }
            },
            dismissButton = { DroidTextButton(onClick = onCancel) { Text("Cancel") } },
        )
        else -> Unit
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
