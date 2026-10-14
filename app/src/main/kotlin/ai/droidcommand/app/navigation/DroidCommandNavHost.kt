package ai.droidcommand.app.navigation

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.app.clipboard.ClipboardScreen
import ai.droidcommand.app.ui.automation.AutomationScreen
import ai.droidcommand.app.ui.chat.ChatScreen
import ai.droidcommand.app.ui.coding.CodingScreen
import ai.droidcommand.app.ui.logs.LogsScreen
import ai.droidcommand.app.ui.memory.MemoryScreen
import ai.droidcommand.app.ui.mode.EngineModeViewModel
import ai.droidcommand.app.ui.mode.PilotScreen
import ai.droidcommand.app.ui.notes.NotesScreen
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/**
 * App shell. The top bar selects Pilot (Mode 1 by default) or Forge (Mode 2 by default) and opens
 * that engine's screen. The labels are the user's names from Settings. The bottom bar's second tab
 * follows the selected mode so it opens the same screen.
 */
@Composable
fun DroidCommandNavHost(
    navController: NavHostController = rememberNavController(),
    modeViewModel: EngineModeViewModel = hiltViewModel(),
) {
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val mode by modeViewModel.state.collectAsState()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            ModeSwitcher(
                pilotLabel = mode.pilotLabel,
                forgeLabel = mode.forgeLabel,
                selected = mode.selected,
                switchError = mode.switchError,
                onSelect = { chosen ->
                    if (modeViewModel.select(chosen)) {
                        navController.navigate(routeFor(chosen)) {
                            popUpTo(Routes.CHAT) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
            )
        },
        bottomBar = { DroidBottomBar(route, mode.selected, mode.label(mode.selected), navController) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CHAT,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.CHAT) { ChatScreen() }
            composable(Routes.PILOT) { PilotScreen() }
            composable(Routes.PLAN) { PlanScreen() }
            composable(Routes.LOGS) { LogsScreen() }
            composable(Routes.TOOLS) { ToolsScreen(navController) }
            composable(Routes.METASPLOIT) { MetasploitScreen() }
            composable(Routes.SETOOLKIT) { SetToolScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.MEMORY) { MemoryScreen() }
            composable(Routes.CLIPBOARDS) { ClipboardScreen() }
            composable(Routes.AUTOMATION) { AutomationScreen() }
            composable(Routes.CODING) { CodingScreen() }
            composable(Routes.NOTES) { NotesScreen() }
        }
    }
}

private fun routeFor(mode: AgentMode): String = when (mode) {
    AgentMode.PILOT -> Routes.PILOT
    AgentMode.FORGE -> Routes.PLAN
}

@Composable
private fun ModeSwitcher(
    pilotLabel: String,
    forgeLabel: String,
    selected: AgentMode,
    switchError: String?,
    onSelect: (AgentMode) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton(pilotLabel, selected == AgentMode.PILOT, Modifier.weight(1f)) { onSelect(AgentMode.PILOT) }
            ModeButton(forgeLabel, selected == AgentMode.FORGE, Modifier.weight(1f)) { onSelect(AgentMode.FORGE) }
        }
        switchError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DroidBottomBar(
    currentRoute: String?,
    selectedMode: AgentMode,
    selectedModeLabel: String,
    navController: NavHostController,
) {
    val engineRoute = routeFor(selectedMode)
    val engineIcon = if (selectedMode == AgentMode.PILOT) Icons.Filled.PlayArrow else Icons.AutoMirrored.Filled.List
    val tabs = listOf(
        Tab(Routes.CHAT, "Chat", Icons.AutoMirrored.Filled.Chat),
        Tab(engineRoute, selectedModeLabel, engineIcon),
        Tab(Routes.MEMORY, "Memory", Icons.Filled.Star),
        Tab(Routes.TOOLS, "Tools", Icons.Filled.Build),
        Tab(Routes.LOGS, "Logs", Icons.Filled.History),
        Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
    )
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    NavigationBar(
        containerColor = NavBarDark,
        modifier = Modifier.clip(shape).border(1.dp, BorderDark, shape),
    ) {
        tabs.forEach { tab ->
            val selected = if (tab.route == engineRoute) {
                currentRoute == Routes.PILOT || currentRoute == Routes.PLAN
            } else {
                currentRoute == tab.route || currentRoute?.startsWith(tab.route + "/") == true
            }
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(Routes.CHAT) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
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
