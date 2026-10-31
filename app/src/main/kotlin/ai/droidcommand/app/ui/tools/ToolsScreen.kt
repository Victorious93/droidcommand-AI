package ai.droidcommand.app.ui.tools

import ai.droidcommand.app.navigation.Routes
import ai.droidcommand.app.shizuku.ShizukuStatusSection
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ux.ScreenHelpCatalog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitleWithHelp("Extra tools", ScreenHelpCatalog.TOOLS)

        ShizukuStatusSection()

        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fleet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Your other Victorious apps live on the Fleet tab. Pairing only means you trust that install. " +
                        "Approved commands still ask before they run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { navController.navigate(Routes.FLEET) },
                    modifier = Modifier.fillMaxWidth().testTag("tools_open_fleet"),
                ) { Text("Open Fleet") }
                Button(
                    onClick = { navController.navigate(Routes.FLEET_ORCHESTRATOR) },
                    modifier = Modifier.fillMaxWidth().testTag("tools_open_fleet_orchestrator"),
                ) { Text("Approved commands") }
                Button(
                    onClick = { navController.navigate(Routes.HELP) },
                    modifier = Modifier.fillMaxWidth().testTag("tools_open_help"),
                ) { Text("Open Help") }
            }
        }

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

        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Clipboards", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A public list of recent clips and an encrypted private list. You choose what moves between them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { navController.navigate(Routes.CLIPBOARDS) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open clipboards") }
            }
        }

        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Automation keys", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Saved shell commands and AI prompts. Each run asks for your approval first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { navController.navigate(Routes.AUTOMATION) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open automation keys") }
            }
        }

        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cloud coding", style = MaterialTheme.typography.titleMedium)
                Text(
                    "One session: plan, then edit, then report. The on-device stand-in does not call a cloud service.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { navController.navigate(Routes.CODING) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open coding session") }
            }
        }

        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Notes", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Folders and markdown on this device. No plugins are installed until you add one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { navController.navigate(Routes.NOTES) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open notes") }
            }
        }
    }
}
