package ai.droidcommand.app.navigation

import ai.droidcommand.app.clipboard.ClipboardScreen
import ai.droidcommand.app.modes.ModeLabelViewModel
import ai.droidcommand.app.modes.ModeLabels
import ai.droidcommand.app.ui.chat.ChatScreen
import ai.droidcommand.app.ui.cloud.CloudScreen
import ai.droidcommand.app.ui.logs.LogsScreen
import ai.droidcommand.app.ui.memory.MemoryScreen
import ai.droidcommand.app.ui.notes.NoteEditorScreen
import ai.droidcommand.app.ui.notes.NotePluginsScreen
import ai.droidcommand.app.ui.notes.NotesScreen
import ai.droidcommand.app.ui.plan.PlanScreen
import ai.droidcommand.app.ui.settings.SettingsScreen
import ai.droidcommand.app.ui.theme.AccentNeonGreen
import ai.droidcommand.app.ui.theme.BackgroundDark
import ai.droidcommand.app.ui.theme.BorderDark
import ai.droidcommand.app.ui.theme.NavBarDark
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.app.ui.tools.ToolsScreen
import ai.droidcommand.app.ui.tools.metasploit.MetasploitScreen
import ai.droidcommand.app.ui.tools.setoolkit.SetToolScreen
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object Routes {
    const val CHAT = "chat"
    const val PLAN = "plan"
    const val LOGS = "logs"
    const val TOOLS = "tools"
    const val METASPLOIT = "tools/metasploit"
    const val SETOOLKIT = "tools/setoolkit"
    const val SETTINGS = "settings"
    const val MEMORY = "memory"
    const val CLIPBOARDS = "tools/clipboards"
    const val CLOUD = "cloud"
    const val NOTES = "notes"
    const val NOTE_EDITOR = "notes/editor"
    const val NOTE_PLUGINS = "notes/plugins"

    fun noteEditor(path: String): String = "$NOTE_EDITOR?path=${Uri.encode(path)}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

// Chat is Mode 1 (Pilot) and Plan is Mode 2 (Forge). Those two labels come from ModeLabelStore, so
// renaming a mode in Settings repaints these tabs. Memory and Logs stay reachable from Tools.
private fun bottomTabs(labels: ModeLabels): List<Tab> = listOf(
    Tab(Routes.CHAT, labels.modeOne, Icons.AutoMirrored.Filled.Chat),
    Tab(Routes.PLAN, labels.modeTwo, Icons.AutoMirrored.Filled.List),
    Tab(Routes.CLOUD, "Cloud", Icons.Filled.Cloud),
    Tab(Routes.NOTES, "Notes", Icons.Filled.Description),
    Tab(Routes.TOOLS, "Tools", Icons.Filled.Build),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

private val toolHubRoutes = setOf(Routes.MEMORY, Routes.LOGS)

/**
 * App shell: a bottom [NavigationBar] over the top-level screens, with tool, note and clipboard
 * screens pushed on top of their tab. The bar stays visible; the parent tab stays highlighted.
 */
@Composable
fun DroidCommandNavHost(navController: NavHostController = rememberNavController()) {
    val labels by hiltViewModel<ModeLabelViewModel>().labels.collectAsState()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    Scaffold(
        containerColor = BackgroundDark,
        bottomBar = { DroidBottomBar(route, navController, bottomTabs(labels)) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CHAT,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.CHAT) { ChatScreen() }
            composable(Routes.PLAN) { PlanScreen() }
            composable(Routes.LOGS) { LogsScreen() }
            composable(Routes.TOOLS) { ToolsScreen(navController) }
            composable(Routes.METASPLOIT) { MetasploitScreen() }
            composable(Routes.SETOOLKIT) { SetToolScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.MEMORY) { MemoryScreen() }
            composable(Routes.CLIPBOARDS) { ClipboardScreen() }
            composable(Routes.CLOUD) { CloudScreen() }
            composable(Routes.NOTES) { NotesScreen(navController) }
            composable(
                route = "${Routes.NOTE_EDITOR}?path={path}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                NoteEditorScreen(path = entry.arguments?.getString("path").orEmpty(), onBack = { navController.popBackStack() })
            }
            composable(Routes.NOTE_PLUGINS) { NotePluginsScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

@Composable
private fun DroidBottomBar(currentRoute: String?, navController: NavHostController, tabs: List<Tab>) {
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    NavigationBar(
        containerColor = NavBarDark,
        modifier = Modifier.clip(shape).border(1.dp, BorderDark, shape),
    ) {
        tabs.forEach { tab ->
            val selected = currentRoute == tab.route ||
                currentRoute?.startsWith(tab.route + "/") == true ||
                currentRoute?.startsWith(tab.route + "?") == true ||
                (tab.route == Routes.TOOLS && currentRoute in toolHubRoutes)
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
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = BackgroundDark,
                    selectedTextColor = AccentNeonGreen,
                    indicatorColor = AccentNeonGreen,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                ),
            )
        }
    }
}
