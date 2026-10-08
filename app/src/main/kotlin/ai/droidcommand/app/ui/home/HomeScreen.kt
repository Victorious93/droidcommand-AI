package ai.droidcommand.app.ui.home

import ai.droidcommand.app.navigation.Routes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

/**
 * Phase 0 stub — per the Consumer Roadmap's own scope for this phase
 * ("scaffolding for every later phase... stub screens only, no inference,
 * no chat"). Real content (multi-provider chat, model picker) is Phase 1.
 */
@Composable
fun HomeScreen(navController: NavHostController) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("DroidCommand AI", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Phase 0 shell. Chat and multi-provider AI arrive in Phase 1 — " +
                "this build's real, working surface is the Tools screen below.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { navController.navigate(Routes.TOOLS) }) { Text("Tools") }
        Button(onClick = { navController.navigate(Routes.CHAT) }) { Text("Chat (stub)") }
        Button(onClick = { navController.navigate(Routes.MEMORY) }) { Text("Memory") }
        Button(onClick = { navController.navigate(Routes.SETTINGS) }) { Text("Settings (stub)") }
    }
}
