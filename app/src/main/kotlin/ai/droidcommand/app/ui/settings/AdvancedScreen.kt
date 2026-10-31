package ai.droidcommand.app.ui.settings

import ai.droidcommand.app.shizuku.ShizukuStatusSection
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.AccentBlueBright
import ai.droidcommand.app.ui.update.ApkDownloadBar
import ai.droidcommand.app.update.ApkDownload
import ai.droidcommand.app.ux.KaiCapabilityCopy
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.config.AssistantCapability
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun AdvancedScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onOpenPilot: () -> Unit = {},
    onOpenPlan: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenBrain: () -> Unit = {},
    onOpenTools: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenN8n: () -> Unit = {},
    onCopiedLink: () -> Unit = {},
) {
    AdvancedContent(
        viewModel = viewModel,
        onOpenPilot = onOpenPilot,
        onOpenPlan = onOpenPlan,
        onOpenMemory = onOpenMemory,
        onOpenBrain = onOpenBrain,
        onOpenTools = onOpenTools,
        onOpenLogs = onOpenLogs,
        onOpenN8n = onOpenN8n,
        onCopiedLink = onCopiedLink,
    )
}

@Composable
private fun AdvancedContent(
    viewModel: SettingsViewModel,
    onOpenPilot: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenBrain: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenN8n: () -> Unit,
    onCopiedLink: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("advanced_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitleWithHelp("Advanced", ScreenHelpCatalog.ADVANCED)
        DroidCard(
            modifier = Modifier.testTag("advanced_warning"),
            borderColor = AccentBlueBright.copy(alpha = 0.55f),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("These tools can change this phone", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Everyday chat, models, and pairing stay on the other tabs. " +
                        "Anything here that can wipe, flash, or run a security tool still asks first. " +
                        "Remote control stays off until you turn it on and confirm.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SectionLabel("Power-user screens")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenPilot, modifier = Modifier.fillMaxWidth()) { Text("One-step command") }
                OutlinedButton(onClick = onOpenPlan, modifier = Modifier.fillMaxWidth()) { Text("Multi-step plan") }
                OutlinedButton(onClick = onOpenMemory, modifier = Modifier.fillMaxWidth()) { Text("Memory") }
                OutlinedButton(onClick = onOpenBrain, modifier = Modifier.fillMaxWidth().testTag("advanced_open_brain")) { Text("Brain") }
                OutlinedButton(onClick = onOpenTools, modifier = Modifier.fillMaxWidth()) { Text("Extra tools") }
                OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) { Text("Activity log") }
                OutlinedButton(onClick = onOpenN8n, modifier = Modifier.fillMaxWidth().testTag("advanced_n8n")) { Text("n8n (self-hosted)") }
            }
        }
        SectionLabel("Mode names")
        ModeNamesSection(viewModel)
        SectionLabel("Model and runtime")
        AdvancedSection(viewModel)
        KaiCapabilityGatesSection(viewModel)
        RemoteControlSettingsSection()
        ShizukuStatusSection()
        SectionLabel("Device tools")
        DroidCard {
            Text(
                "Root, Termux, Shizuku, Metasploit, and the Social-Engineer Toolkit are under Extra tools. " +
                    "Each run still asks before it executes. Shizuku is optional.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionLabel("Developer info")
        DeveloperInfoCard(onCopiedLink = onCopiedLink)
    }
}

@Composable
private fun KaiCapabilityGatesSection(viewModel: SettingsViewModel) {
    val settings by viewModel.capabilities.collectAsState()
    val rows = remember(settings) { KaiCapabilityCopy.rows(settings) }
    SectionLabel("Kai capabilities")
    DroidCard(modifier = Modifier.testTag("advanced_kai_gates")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Later assistant features", style = MaterialTheme.typography.titleMedium)
            Text(
                "These stay off except Brain notes, which you can turn on. " +
                    "Nothing here turns on remote control or skips a confirmation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            rows.forEach { row ->
                val toggleable = row.capability == AssistantCapability.BRAIN
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("advanced_kai_gate_${row.capability.wireName}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(row.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            row.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = row.enabled,
                        onCheckedChange = if (toggleable) { on -> viewModel.setBrainEnabled(on) } else null,
                        enabled = toggleable,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeveloperInfoCard(onCopiedLink: () -> Unit) {
    val context = LocalContext.current
    val info = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    DroidCard(modifier = Modifier.testTag("advanced_developer")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "This install is ${info?.packageName ?: context.packageName}. " +
                    ApkDownload.versionLabel(info?.versionName) +
                    ". Debug builds are sideload-only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ApkDownloadBar(
                versionLabel = ApkDownload.versionLabel(info?.versionName),
                onCopied = onCopiedLink,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B1220)
@Composable
private fun AdvancedWarningPreview() {
    DroidCard(borderColor = AccentBlueBright.copy(alpha = 0.55f)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("These tools can change this phone", style = MaterialTheme.typography.titleMedium)
            Text(
                "Everyday chat, models, and pairing stay on the other tabs.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
