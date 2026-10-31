package ai.droidcommand.app.navigation

import ai.droidcommand.app.clipboard.ClipboardScreen
import ai.droidcommand.app.ui.automation.AutomationScreen
import ai.droidcommand.app.ui.chat.ChatScreen
import ai.droidcommand.app.ui.coding.CodingScreen
import ai.droidcommand.app.ui.fleet.FleetDashboardScreen
import ai.droidcommand.app.ui.fleet.FleetOrchestratorScreen
import ai.droidcommand.app.ui.fleet.FleetScreen
import ai.droidcommand.app.ui.help.HelpScreen
import ai.droidcommand.app.ui.logs.LogsScreen
import ai.droidcommand.app.ui.memory.MemoryScreen
import ai.droidcommand.app.ui.mode.PilotScreen
import ai.droidcommand.app.ui.models.ModelsScreen
import ai.droidcommand.app.ui.notes.NotesScreen
import ai.droidcommand.app.ui.onboarding.FirstRunScreen
import ai.droidcommand.app.ui.plan.PlanScreen
import ai.droidcommand.app.ui.settings.SettingsScreen
import ai.droidcommand.app.ui.theme.AccentBlueBright
import ai.droidcommand.app.ui.theme.BackgroundDark
import ai.droidcommand.app.ui.theme.BorderDark
import ai.droidcommand.app.ui.theme.NavBarDark
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.app.ui.tools.ToolsScreen
import ai.droidcommand.app.ui.tools.metasploit.MetasploitScreen
import ai.droidcommand.app.ui.tools.setoolkit.SetToolScreen
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

object Routes {
    const val CHAT = "chat"
    const val PILOT = "pilot"
    const val PLAN = "plan"
    const val LOGS = "logs"
    const val TOOLS = "tools"
    const val METASPLOIT = "tools/metasploit"
    const val SETOOLKIT = "tools/setoolkit"
    const val SETTINGS = "settings"
    const val MEMORY = "memory"
    const val CLIPBOARDS = "tools/clipboards"
    const val AUTOMATION = "tools/automation"
    const val CODING = "tools/coding"
    const val NOTES = "tools/notes"
    const val FLEET = "fleet"
    const val FLEET_PAIRING = "fleet/pairing"
    const val FLEET_ORCHESTRATOR = "fleet/orchestrator"
    const val HELP = "help"
    const val MODELS = "models"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/**
 * App shell. Four owner-facing tabs: Fleet, AI Chat, Models, Settings.
 * First-run setup covers the whole window until it is finished or skipped.
 * Pilot, plans, memory, logs, and extra tools stay reachable from Settings.
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

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            // Status-bar inset only. The old Mode 1 / Mode 2 switcher is gone from chrome.
            Spacer(
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
            )
        },
        bottomBar = { DroidBottomBar(route, navController) },
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
                    onOpenPilot = { navController.navigate(Routes.PILOT) },
                    onOpenPlan = { navController.navigate(Routes.PLAN) },
                    onOpenMemory = { navController.navigate(Routes.MEMORY) },
                    onOpenTools = { navController.navigate(Routes.TOOLS) },
                    onOpenLogs = { navController.navigate(Routes.LOGS) },
                    onReplaySetup = shell::replayFirstRun,
                )
            }
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
                )
            }
            composable(Routes.FLEET_PAIRING) { FleetScreen() }
            composable(Routes.FLEET_ORCHESTRATOR) { FleetOrchestratorScreen() }
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
    )
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    NavigationBar(
        containerColor = NavBarDark,
        modifier = Modifier.clip(shape).border(1.dp, BorderDark, shape),
    ) {
        tabs.forEach { tab ->
            val selected = currentRoute == tab.route ||
                (tab.route == Routes.FLEET && currentRoute?.startsWith("fleet") == true) ||
                (tab.route == Routes.SETTINGS && currentRoute in settingsChildRoutes)
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
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = BackgroundDark,
                    selectedTextColor = AccentBlueBright,
                    indicatorColor = AccentBlueBright,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                ),
            )
        }
    }
}

private val settingsChildRoutes = setOf(
    Routes.HELP,
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
)
