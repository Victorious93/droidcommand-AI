package ai.droidcommand.app.navigation

import ai.droidcommand.app.clipboard.ClipboardScreen
import ai.droidcommand.app.ui.automation.AutomationScreen
import ai.droidcommand.app.ui.chat.ChatScreen
import ai.droidcommand.app.ui.coding.CodingScreen
import ai.droidcommand.app.ui.fleet.ConnectDeviceScreen
import ai.droidcommand.app.ui.fleet.FleetDashboardScreen
import ai.droidcommand.app.ui.fleet.FleetOrchestratorScreen
import ai.droidcommand.app.ui.fleet.FleetScreen
import ai.droidcommand.app.ui.help.HelpScreen
import ai.droidcommand.app.ui.logs.LogsScreen
import ai.droidcommand.app.ui.memory.MemoryScreen
import ai.droidcommand.app.ui.mode.PilotScreen
import ai.droidcommand.app.ui.models.ModelsScreen
import ai.droidcommand.app.ui.n8n.N8nScreen
import ai.droidcommand.app.ui.notes.NotesScreen
import ai.droidcommand.app.ui.onboarding.FirstRunScreen
import ai.droidcommand.app.ui.plan.PlanScreen
import ai.droidcommand.app.ui.settings.AdvancedScreen
import ai.droidcommand.app.ui.settings.SettingsScreen
import ai.droidcommand.app.ui.studio.NotesStudioScreen
import ai.droidcommand.app.ui.toolbox.SystemToolboxScreen
import ai.droidcommand.app.ui.tools.ToolsScreen
import ai.droidcommand.app.ui.tools.metasploit.MetasploitScreen
import ai.droidcommand.app.ui.tools.setoolkit.SetToolScreen
import ai.droidcommand.app.ui.update.ApkDownloadBar
import ai.droidcommand.app.ui.update.rememberInstalledVersionLabel
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch

object Routes {
    const val CHAT = "chat"
    const val PILOT = "pilot"
    const val PLAN = "plan"
    const val LOGS = "logs"
    const val TOOLS = "tools"
    const val METASPLOIT = "tools/metasploit"
    const val SETOOLKIT = "tools/setoolkit"
    const val SETTINGS = "settings"
    const val ADVANCED = "advanced"
    const val MEMORY = "memory"
    const val CLIPBOARDS = "tools/clipboards"
    const val AUTOMATION = "tools/automation"
    const val CODING = "tools/coding"
    const val NOTES = "tools/notes"
    const val FLEET = "fleet"
    const val FLEET_PAIRING = "fleet/pairing"
    const val FLEET_ORCHESTRATOR = "fleet/orchestrator"
    const val CONNECT_DEVICE = "fleet/connect"
    const val HELP = "help"
    const val MODELS = "models"
    const val N8N = "advanced/n8n"
    const val SYSTEM_TOOLBOX = "advanced/toolbox"
    const val NOTES_STUDIO = "advanced/notes-studio"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/**
 * App shell. Five tabs: Fleet, AI Chat, Models, Settings, Advanced.
 * First-run setup covers the whole window until it is finished or skipped.
 * The top bar is the APK download chip plus this install's version.
 * Pilot, plans, memory, logs, and extra tools live on Advanced.
 */
@Composable
fun DroidCommandNavHost(
    navController: NavHostController = rememberNavController(),
    shell: AppShellViewModel = hiltViewModel(),
) {
    val showFirstRun by shell.showFirstRun.collectAsState()
    if (showFirstRun) {
        FirstRunScreen(onFinished = shell::onFirstRunFinished)
        return
    }

    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val versionLabel = rememberInstalledVersionLabel()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
            ) {
                ApkDownloadBar(
                    versionLabel = versionLabel,
                    onCopied = { scope.launch { snackbar.showSnackbar("Link copied") } },
                )
            }
        },
        bottomBar = { DroidBottomBar(route, navController) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        LaunchedEffect(Unit) {
            if (shell.consumeOpenModels()) {
                navController.navigate(Routes.MODELS) { launchSingleTop = true }
            }
        }
        NavHost(
            navController = navController,
            startDestination = Routes.FLEET,
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding).imePadding(),
        ) {
            composable(Routes.CHAT) { ChatScreen(onOpenModels = { navController.navigate(Routes.MODELS) }) }
            composable(Routes.PILOT) { PilotScreen() }
            composable(Routes.PLAN) { PlanScreen() }
            composable(Routes.LOGS) { LogsScreen() }
            composable(Routes.TOOLS) { ToolsScreen(navController) }
            composable(Routes.METASPLOIT) { MetasploitScreen() }
            composable(Routes.SETOOLKIT) { SetToolScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenHelp = { navController.navigate(Routes.HELP) },
                    onOpenModels = { navController.navigate(Routes.MODELS) },
                    onOpenAdvanced = { navController.navigate(Routes.ADVANCED) },
                    onReplaySetup = shell::replayFirstRun,
                )
            }
            composable(Routes.ADVANCED) {
                AdvancedScreen(
                    onOpenPilot = { navController.navigate(Routes.PILOT) },
                    onOpenPlan = { navController.navigate(Routes.PLAN) },
                    onOpenMemory = { navController.navigate(Routes.MEMORY) },
                    onOpenBrain = { navController.navigate(Routes.NOTES) },
                    onOpenTools = { navController.navigate(Routes.TOOLS) },
                    onOpenLogs = { navController.navigate(Routes.LOGS) },
                    onOpenN8n = { navController.navigate(Routes.N8N) },
                    onOpenToolbox = { navController.navigate(Routes.SYSTEM_TOOLBOX) },
                    onOpenStudio = { navController.navigate(Routes.NOTES_STUDIO) },
                    onCopiedLink = { scope.launch { snackbar.showSnackbar("Link copied") } },
                )
            }
            composable(Routes.N8N) { N8nScreen() }
            composable(Routes.SYSTEM_TOOLBOX) { SystemToolboxScreen() }
            composable(Routes.NOTES_STUDIO) { NotesStudioScreen() }
            composable(Routes.HELP) { HelpScreen() }
            composable(Routes.MODELS) { ModelsScreen() }
            composable(Routes.MEMORY) { MemoryScreen() }
            composable(Routes.CLIPBOARDS) { ClipboardScreen() }
            composable(Routes.AUTOMATION) { AutomationScreen() }
            composable(Routes.CODING) { CodingScreen() }
            composable(Routes.NOTES) { NotesScreen() }
            composable(Routes.FLEET) {
                FleetDashboardScreen(
                    onOpenPairing = { navController.navigate(Routes.FLEET_PAIRING) },
                    onOpenOrchestrator = { navController.navigate(Routes.FLEET_ORCHESTRATOR) },
                    onOpenConnectDevice = { navController.navigate(Routes.CONNECT_DEVICE) },
                )
            }
            composable(Routes.FLEET_PAIRING) { FleetScreen() }
            composable(Routes.FLEET_ORCHESTRATOR) { FleetOrchestratorScreen() }
            composable(Routes.CONNECT_DEVICE) { ConnectDeviceScreen() }
        }
    }
}

@Composable
private fun DroidBottomBar(
    currentRoute: String?,
    navController: NavHostController,
) {
    val tabs = listOf(
        Tab(Routes.FLEET, "Fleet", Icons.Filled.Apps),
        Tab(Routes.CHAT, "AI Chat", Icons.AutoMirrored.Filled.Chat),
        Tab(Routes.MODELS, "Models", Icons.Filled.Storage),
        Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
        Tab(Routes.ADVANCED, "Advanced", Icons.Filled.Tune),
    )
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.clip(shape).border(1.dp, MaterialTheme.colorScheme.outline, shape),
    ) {
        tabs.forEach { tab ->
            val selected = currentRoute == tab.route ||
                (tab.route == Routes.FLEET && currentRoute?.startsWith("fleet") == true) ||
                (tab.route == Routes.ADVANCED && currentRoute in advancedChildRoutes)
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(Routes.FLEET) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Icon(
                        tab.icon,
                        contentDescription = null,
                        modifier = Modifier.testTag("nav_${tab.route.substringBefore('/')}"),
                    )
                },
                label = {
                    Text(
                        tab.label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

private val advancedChildRoutes = setOf(
    Routes.PILOT,
    Routes.PLAN,
    Routes.MEMORY,
    Routes.LOGS,
    Routes.TOOLS,
    Routes.METASPLOIT,
    Routes.SETOOLKIT,
    Routes.CLIPBOARDS,
    Routes.AUTOMATION,
    Routes.CODING,
    Routes.NOTES,
    Routes.N8N,
    Routes.SYSTEM_TOOLBOX,
    Routes.NOTES_STUDIO,
)
