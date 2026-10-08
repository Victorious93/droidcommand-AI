package ai.droidcommand.app.ui.tools

import ai.droidcommand.app.navigation.Routes
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

/**
 * The tool launcher. Every entry here is a button leading to a form-based
 * screen — never a place to type a raw command. The Security Tools section
 * (Metasploit, Social-Engineer Toolkit) is this PR's actual deliverable;
 * every invocation still goes through the same `SecureToolExecutor`
 * confirmation gate the rest of this app's tools do.
 */
@Composable
fun ToolsScreen(navController: NavHostController) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Tools")

        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Security Tools", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Runs against an explicitly named target only, and always asks for " +
                        "confirmation before anything executes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { navController.navigate(Routes.METASPLOIT) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Metasploit") }
                Button(
                    onClick = { navController.navigate(Routes.SETOOLKIT) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Social-Engineer Toolkit") }
            }
        }
    }
}
