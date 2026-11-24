package ai.droidcommand.app.ui.settings

import ai.droidcommand.app.mcp.McpServersSection
import ai.droidcommand.app.shizuku.ShizukuStatusSection
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.DroidGroup
import ai.droidcommand.app.ui.components.DroidListRow
import ai.droidcommand.app.ui.components.DroidRowDivider
import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.components.StatusPillWithChevron
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.MaterialTheme
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
    onOpenSecurity: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenN8n: () -> Unit = {},
    onOpenToolbox: () -> Unit = {},
    onOpenSystemSettings: () -> Unit = {},
    onOpenCommunications: () -> Unit = {},
    onOpenStudio: () -> Unit = {},
    onOpenBotMaker: () -> Unit = {},
    onOpenSkillFiles: () -> Unit = {},
    onOpenStatus: () -> Unit = {},
    onOpenFleet: () -> Unit = {},
    onCopiedLink: () -> Unit = {},
) {
    AdvancedContent(
        viewModel = viewModel,
        onOpenPilot = onOpenPilot,
        onOpenPlan = onOpenPlan,
        onOpenMemory = onOpenMemory,
        onOpenBrain = onOpenBrain,
        onOpenTools = onOpenTools,
        onOpenSecurity = onOpenSecurity,
        onOpenLogs = onOpenLogs,
        onOpenN8n = onOpenN8n,
        onOpenToolbox = onOpenToolbox,
        onOpenSystemSettings = onOpenSystemSettings,
        onOpenCommunications = onOpenCommunications,
        onOpenStudio = onOpenStudio,
        onOpenBotMaker = onOpenBotMaker,
        onOpenSkillFiles = onOpenSkillFiles,
        onOpenStatus = onOpenStatus,
        onOpenFleet = onOpenFleet,
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
    onOpenSecurity: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenN8n: () -> Unit,
    onOpenToolbox: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onOpenCommunications: () -> Unit,
    onOpenStudio: () -> Unit,
    onOpenBotMaker: () -> Unit,
    onOpenSkillFiles: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenFleet: () -> Unit,
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
        val capabilities by viewModel.capabilities.collectAsState()
        val botMakerOn = capabilities.isEnabled(AssistantCapability.BOT_MAKER)

        SectionLabel("Ask and plan")
        DroidGroup {
            DroidListRow(
                title = "One-step command",
                subtitle = "Ask for one approved action. It shows what it will do and waits.",
                icon = Icons.Filled.PlayArrow,
                onClick = onOpenPilot,
            )
            DroidRowDivider()
            DroidListRow(
                title = "Multi-step plan",
                subtitle = "Give it a goal and it breaks the work into steps.",
                icon = Icons.Filled.FormatListNumbered,
                onClick = onOpenPlan,
            )
            DroidRowDivider()
            DroidListRow(
                title = "Memory",
                subtitle = "Facts the app can remember across chats, if you turn that on.",
                icon = Icons.Filled.Memory,
                onClick = onOpenMemory,
            )
            DroidRowDivider()
            DroidListRow(
                title = "Brain",
                subtitle = "Your markdown notes.",
                icon = Icons.Filled.Psychology,
                onClick = onOpenBrain,
                modifier = Modifier.testTag("advanced_open_brain"),
            )
            DroidRowDivider()
            DroidListRow(
                title = "Notes Studio",
                subtitle = "Write and check AI context files in your Brain vault.",
                icon = Icons.Filled.EditNote,
                onClick = onOpenStudio,
                modifier = Modifier.testTag("advanced_notes_studio"),
            )
        }

        SectionLabel("Helpers and skills")
        DroidGroup {
            DroidListRow(
                title = "Bot maker",
                subtitle = "Small helper bots for one job each.",
                icon = Icons.Filled.SmartToy,
                onClick = onOpenBotMaker,
                modifier = Modifier.testTag("advanced_bot_maker"),
                trailing = { StatusPillWithChevron(if (botMakerOn) "On" else "Off", if (botMakerOn) PillTone.Success else PillTone.Neutral) },
            )
            DroidRowDivider()
            DroidListRow(
                title = "Skill files",
                subtitle = "Instruction folders (SKILL.md) the AI can turn on.",
                icon = Icons.Filled.Extension,
                onClick = onOpenSkillFiles,
                modifier = Modifier.testTag("advanced_skill_files"),
            )
            DroidRowDivider()
            DroidListRow(
                title = "n8n (self-hosted)",
                subtitle = "Run workflows on your own n8n server. The AI can use it only if you allow it.",
                icon = Icons.Filled.AccountTree,
                onClick = onOpenN8n,
                modifier = Modifier.testTag("advanced_n8n"),
            )
        }

        SectionLabel("This phone")
        DroidGroup {
            DroidListRow(
                title = "System Toolbox (Shizuku / root)",
                subtitle = "A shell, app manager, permissions and logcat through Shizuku or root.",
                icon = Icons.Filled.Build,
                onClick = onOpenToolbox,
                modifier = Modifier.testTag("advanced_system_toolbox"),
            )
            DroidRowDivider()
            DroidListRow(
                title = "System Settings (animations, display, Private DNS)",
                subtitle = "A native editor for Android settings, with a way back for every change.",
                icon = Icons.Filled.Settings,
                onClick = onOpenSystemSettings,
                modifier = Modifier.testTag("advanced_system_settings"),
            )
            DroidRowDivider()
            DroidListRow(
                title = "Communications (VictorBots and VictorSMS)",
                subtitle = "Choose which apps may ask for AI help, texts and calls. Nothing is allowed by default.",
                icon = Icons.Filled.Email,
                onClick = onOpenCommunications,
                modifier = Modifier.testTag("advanced_communications"),
            )
            DroidRowDivider()
            DroidListRow(
                title = "Extra tools",
                subtitle = "Clipboards, automation keys, fleet shortcuts.",
                icon = Icons.Filled.Handyman,
                onClick = onOpenTools,
            )
        }

        SectionLabel("Security")
        DroidGroup {
            DroidListRow(
                title = "Security tools",
                subtitle = "Metasploit and the Social-Engineer Toolkit, with a readiness check.",
                icon = Icons.Filled.Security,
                onClick = onOpenSecurity,
                modifier = Modifier.testTag("advanced_security_tools"),
            )
            DroidRowDivider()
            DroidListRow(
                title = "Activity log",
                subtitle = "What was approved, denied and run.",
                icon = Icons.Filled.History,
                onClick = onOpenLogs,
            )
        }

        SectionLabel("Backup")
        DroidGroup {
            DroidListRow(
                title = "Status and backup",
                subtitle = "What this install is doing, and a copy of your data.",
                icon = Icons.Filled.Backup,
                onClick = onOpenStatus,
                modifier = Modifier.testTag("advanced_status_backup"),
            )
        }
        SectionLabel("Mode names")
        ModeNamesSection(viewModel)
        SectionLabel("Model and runtime")
        AdvancedSection(viewModel)
        ConnectedAppsSection(onOpenFleet = onOpenFleet)
        BuiltInFeaturesSection(viewModel)
        McpServersSection()
        RemoteControlSettingsSection()
        ShizukuStatusSection(showEmbeddedControls = true)
        SectionLabel("Device tools")
        DroidCard {
            Text(
                "Metasploit and the Social-Engineer Toolkit are under Security tools. " +
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
private fun BuiltInFeaturesSection(viewModel: SettingsViewModel) {
    val settings by viewModel.capabilities.collectAsState()
    val features = listOf(
        AssistantCapability.BRAIN to "Brain notes",
        AssistantCapability.BOT_MAKER to "Helper bots",
        AssistantCapability.SKILL_SCRIPTS to "Skill files",
    )
    SectionLabel("Built-in features")
    DroidCard(modifier = Modifier.testTag("advanced_kai_gates")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "These run inside this app and are off until you switch them on. " +
                    "Nothing here turns on remote control or skips a confirmation. " +
                    "Extra AI tools (MCP) are set up in their own section below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            features.forEach { (capability, label) ->
                val row = KaiCapabilityCopy.rows(settings).first { it.capability == capability }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("advanced_kai_gate_${capability.wireName}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            row.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = row.enabled,
                        onCheckedChange = { on ->
                            when (capability) {
                                AssistantCapability.BRAIN -> viewModel.setBrainEnabled(on)
                                AssistantCapability.BOT_MAKER -> viewModel.setBotMakerEnabled(on)
                                AssistantCapability.SKILL_SCRIPTS -> viewModel.setSkillScriptsEnabled(on)
                                else -> Unit
                            }
                        },
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
