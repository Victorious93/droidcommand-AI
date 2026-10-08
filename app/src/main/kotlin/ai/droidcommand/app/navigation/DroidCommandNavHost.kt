package ai.droidcommand.app.navigation

import ai.droidcommand.app.ui.chat.ChatScreen
import ai.droidcommand.app.ui.home.HomeScreen
import ai.droidcommand.app.ui.memory.MemoryScreen
import ai.droidcommand.app.ui.settings.SettingsScreen
import ai.droidcommand.app.ui.tools.ToolsScreen
import ai.droidcommand.app.ui.tools.metasploit.MetasploitScreen
import ai.droidcommand.app.ui.tools.setoolkit.SetToolScreen
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

object Routes {
    const val HOME = "home"
    const val CHAT = "chat"
    const val TOOLS = "tools"
    const val METASPLOIT = "tools/metasploit"
    const val SETOOLKIT = "tools/setoolkit"
    const val MEMORY = "memory"
    const val SETTINGS = "settings"
}

/**
 * Phase 0's navigation skeleton: [ai.droidcommand.app.ui.home.HomeScreen] and
 * [ai.droidcommand.app.ui.chat.ChatScreen] are stub screens (per the Consumer
 * Roadmap's own Phase 0 scope — "no inference, no chat"); the Tools graph
 * (this PR's actual scope) is real and wired to a live [ai.droidcommand.agent.ToolRunner].
 */
@Composable
fun DroidCommandNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(navController) }
        composable(Routes.CHAT) { ChatScreen() }
        composable(Routes.TOOLS) { ToolsScreen(navController) }
        composable(Routes.METASPLOIT) { MetasploitScreen() }
        composable(Routes.SETOOLKIT) { SetToolScreen() }
        composable(Routes.MEMORY) { MemoryScreen() }
        composable(Routes.SETTINGS) { SettingsScreen() }
    }
}
