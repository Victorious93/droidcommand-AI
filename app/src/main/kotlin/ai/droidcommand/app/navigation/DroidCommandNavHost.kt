package ai.droidcommand.app.navigation

import ai.droidcommand.app.ui.chat.ChatScreen
import ai.droidcommand.app.ui.logs.LogsScreen
import ai.droidcommand.app.ui.memory.MemoryScreen
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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

object Routes {
    const val CHAT = "chat"
    const val PLAN = "plan"
    const val LOGS = "logs"
    const val TOOLS = "tools"
    const val METASPLOIT = "tools/metasploit"
    const val SETOOLKIT = "tools/setoolkit"
    const val SETTINGS = "settings"
    const val MEMORY = "memory"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

// Same order as OpenDroid's bar, with Tools where its Macros tab is (this app has no macro UI).
private val tabs = listOf(
    Tab(Routes.CHAT, "Chat", Icons.AutoMirrored.Filled.Chat),
    Tab(Routes.PLAN, "Plan", Icons.AutoMirrored.Filled.List),
    Tab(Routes.MEMORY, "Memory", Icons.Filled.Star),
    Tab(Routes.TOOLS, "Tools", Icons.Filled.Build),
    Tab(Routes.LOGS, "Logs", Icons.Filled.History),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

/**
 * App shell: a bottom [NavigationBar] over the top-level screens (OpenDroid-style rounded,
 * bordered bar with a neon pill on the selected tab), and the Tools sub-screens pushed on top of
 * the Tools tab. The bar stays visible on those sub-screens with Tools highlighted.
 */
@Composable
fun DroidCommandNavHost(navController: NavHostController = rememberNavController()) {
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    Scaffold(
        containerColor = BackgroundDark,
        bottomBar = { DroidBottomBar(route, navController) },
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
        }
    }
}

@Composable
private fun DroidBottomBar(currentRoute: String?, navController: NavHostController) {
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    NavigationBar(
        containerColor = NavBarDark,
        modifier = Modifier.clip(shape).border(1.dp, BorderDark, shape),
    ) {
        tabs.forEach { tab ->
            val selected = currentRoute == tab.route || currentRoute?.startsWith(tab.route + "/") == true
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
