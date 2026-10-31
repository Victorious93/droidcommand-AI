package ai.droidcommand.app.shizuku

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.AccentBlueBright
import ai.droidcommand.app.ui.theme.AccentCyan
import ai.droidcommand.app.ui.theme.AccentSuccess
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.shizuku.EmbeddedShizukuCopy
import ai.droidcommand.shizuku.EmbeddedStartMethod
import ai.droidcommand.shizuku.ShizukuCopy
import ai.droidcommand.shizuku.ShizukuState
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** Settings / Tools status card. Four states, neon indicator, request-permission copy. */
@Composable
fun ShizukuStatusSection(
    showEmbeddedControls: Boolean = false,
    viewModel: ShizukuStatusViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsState()
    SectionLabel(if (showEmbeddedControls) EmbeddedShizukuCopy.SECTION_TITLE else ShizukuCopy.SECTION_TITLE)
    ShizukuStatusCard(
        state = ui.state,
        onRequestPermission = viewModel::requestPermission,
    )
    if (showEmbeddedControls) {
        EmbeddedShizukuCard(ui = ui, viewModel = viewModel)
    }
}

@Composable
fun ShizukuStatusCard(
    state: ShizukuState,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DroidCard(modifier = modifier.testTag("shizuku_status_card")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    modifier = Modifier.size(10.dp).testTag("shizuku_status_dot"),
                    shape = CircleShape,
                    color = indicatorColor(state),
                ) {}
                Text(
                    ShizukuCopy.statusTitle(state),
                    style = MaterialTheme.typography.titleMedium,
                    color = indicatorColor(state),
                )
            }
            Text(
                ShizukuCopy.statusBody(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state == ShizukuState.DENIED) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.testTag("shizuku_request_permission"),
                ) {
                    Text(ShizukuCopy.REQUEST_BUTTON)
                }
            }
        }
    }
}

@Composable
private fun EmbeddedShizukuCard(
    ui: ShizukuScreenState,
    viewModel: ShizukuStatusViewModel,
) {
    val context = LocalContext.current
    DroidCard(modifier = Modifier.testTag("shizuku_embedded_card")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                EmbeddedShizukuCopy.methodTitle(ui.startMethod),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("shizuku_start_method"),
            )
            Text(
                EmbeddedShizukuCopy.bootSummary(ui.bootMethod),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("shizuku_boot_method"),
            )
            Text(
                EmbeddedShizukuCopy.REBOOT_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.startMethod == EmbeddedStartMethod.WIRELESS_PAIR ||
                ui.startMethod == EmbeddedStartMethod.WIRELESS_PAIRED
            ) {
                OutlinedTextField(
                    value = ui.pairCode,
                    onValueChange = viewModel::setPairCode,
                    label = { Text("Pairing code") },
                    modifier = Modifier.fillMaxWidth().testTag("shizuku_pair_code"),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = ui.pairPort,
                    onValueChange = viewModel::setPairPort,
                    label = { Text("Pairing port") },
                    modifier = Modifier.fillMaxWidth().testTag("shizuku_pair_port"),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = ui.connectPort,
                    onValueChange = viewModel::setConnectPort,
                    label = { Text("Wireless debugging port (optional)") },
                    modifier = Modifier.fillMaxWidth().testTag("shizuku_connect_port"),
                    singleLine = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::startBuiltIn,
                    enabled = !ui.busy && ui.startMethod != EmbeddedStartMethod.UNAVAILABLE,
                    modifier = Modifier.testTag("shizuku_start"),
                ) {
                    Text(if (ui.busy) "Starting…" else EmbeddedShizukuCopy.START_BUTTON)
                }
                if (ui.rootButtonVisible) {
                    OutlinedButton(
                        onClick = viewModel::startWithRoot,
                        enabled = !ui.busy,
                        modifier = Modifier.testTag("shizuku_start_root"),
                    ) {
                        Text(EmbeddedShizukuCopy.START_ROOT_BUTTON)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(EmbeddedShizukuCopy.START_ON_BOOT)
                    Text(
                        EmbeddedShizukuCopy.START_ON_BOOT_DEFAULT_OFF,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = ui.startOnBoot,
                    onCheckedChange = viewModel::setStartOnBoot,
                    modifier = Modifier.testTag("shizuku_start_on_boot"),
                )
            }
            if (!ui.writeSecureSettingsGranted &&
                (ui.bootMethod == EmbeddedStartMethod.NOTIFICATION || ui.bootMethod == EmbeddedStartMethod.SECURE_SETTINGS_WADB)
            ) {
                Text(
                    EmbeddedShizukuCopy.WRITE_SECURE_SETTINGS_HELP,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    EmbeddedShizukuCopy.WRITE_SECURE_SETTINGS_COMMAND,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("shizuku_wss_command"),
                )
                OutlinedButton(
                    onClick = { copyCommand(context) },
                    modifier = Modifier.testTag("shizuku_copy_wss"),
                ) {
                    Text("Copy the one-time command")
                }
            }
            Text("Setup", style = MaterialTheme.typography.titleSmall)
            EmbeddedShizukuCopy.setupSteps.forEachIndexed { index, step ->
                Text(
                    "${index + 1}. $step",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                EmbeddedShizukuCopy.EXTERNAL_FALLBACK,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                EmbeddedShizukuCopy.NO_SILENT_GRANT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                EmbeddedShizukuCopy.ATTRIBUTION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("shizuku_start_message"))
            }
        }
    }
}

private fun copyCommand(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(
        ClipData.newPlainText("WRITE_SECURE_SETTINGS", EmbeddedShizukuCopy.WRITE_SECURE_SETTINGS_COMMAND),
    )
}

private fun indicatorColor(state: ShizukuState): Color = when (state) {
    ShizukuState.GRANTED -> AccentSuccess
    ShizukuState.DENIED -> AccentBlueBright
    ShizukuState.NOT_RUNNING -> AccentCyan
    ShizukuState.NOT_INSTALLED -> TextSecondary
}
