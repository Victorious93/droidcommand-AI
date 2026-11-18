package ai.droidcommand.app.ui.tools

import ai.droidcommand.app.navigation.Routes
import ai.droidcommand.app.shizuku.ShizukuStatusSection
import ai.droidcommand.app.ui.components.DroidGroup
import ai.droidcommand.app.ui.components.DroidListRow
import ai.droidcommand.app.ui.components.DroidRowDivider
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.components.StatusPillWithChevron
import ai.droidcommand.app.ui.tools.security.rememberSecurityReadiness
import ai.droidcommand.app.ux.ScreenHelpCatalog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Verified
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

/**
 * The tool launcher. Every entry is a row leading to a form-based screen, never a place to type a raw command. The
 * security tools sit behind their own screen ([Routes.SECURITY]) that says whether this phone can run them at all;
 * every invocation still goes through the same `SecureToolExecutor` confirmation gate the rest of this app's tools do.
 */
@Composable
fun ToolsScreen(navController: NavHostController) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitleWithHelp("Extra tools", ScreenHelpCatalog.TOOLS)

        ShizukuStatusSection()

        SectionLabel("Fleet")
        DroidGroup {
            DroidListRow(
                title = "Open Fleet",
                subtitle = "Your other Victorious apps. Pairing only means you trust that install.",
                icon = Icons.Filled.Apps,
                onClick = { navController.navigate(Routes.FLEET) },
                modifier = Modifier.testTag("tools_open_fleet"),
            )
            DroidRowDivider()
            DroidListRow(
                title = "Approved commands",
                subtitle = "Commands you have allowed. They still ask before they run.",
                icon = Icons.Filled.Verified,
                onClick = { navController.navigate(Routes.FLEET_ORCHESTRATOR) },
                modifier = Modifier.testTag("tools_open_fleet_orchestrator"),
            )
            DroidRowDivider()
            DroidListRow(
                title = "Open Help",
                icon = Icons.Filled.Help,
                onClick = { navController.navigate(Routes.HELP) },
                modifier = Modifier.testTag("tools_open_help"),
            )
        }

        SectionLabel("Security Tools")
        val readiness = rememberSecurityReadiness()
        DroidGroup {
            val (msfText, msfTone) = readiness.metasploitPill()
            DroidListRow(
                title = "Metasploit",
                subtitle = "An authorized Kali scan, or one module against one named host.",
                icon = Icons.Filled.Security,
                onClick = { navController.navigate(Routes.METASPLOIT) },
                modifier = Modifier.testTag("tools_open_metasploit"),
                trailing = { StatusPillWithChevron(msfText, msfTone) },
            )
            DroidRowDivider()
            val (setText, setTone) = readiness.setPill()
            DroidListRow(
                title = "Social-Engineer Toolkit",
                subtitle = "Structured attack-vector forms. Launching needs a backend this version does not have yet.",
                icon = Icons.Filled.Security,
                onClick = { navController.navigate(Routes.SETOOLKIT) },
                modifier = Modifier.testTag("tools_open_setoolkit"),
                trailing = { StatusPillWithChevron(setText, setTone) },
            )
            DroidRowDivider()
            DroidListRow(
                title = "Check readiness",
                subtitle = "What this phone is ready for, and what is missing.",
                icon = Icons.Filled.Verified,
                onClick = { navController.navigate(Routes.SECURITY) },
                modifier = Modifier.testTag("tools_open_security"),
            )
        }

        SectionLabel("Everyday")
        DroidGroup {
            DroidListRow(
                title = "Clipboards",
                subtitle = "A public list of recent clips and an encrypted private list. You choose what moves between them.",
                icon = Icons.Filled.ContentPaste,
                onClick = { navController.navigate(Routes.CLIPBOARDS) },
            )
            DroidRowDivider()
            DroidListRow(
                title = "Automation keys",
                subtitle = "Saved shell commands and AI prompts. Each run asks for your approval first.",
                icon = Icons.Filled.Key,
                onClick = { navController.navigate(Routes.AUTOMATION) },
            )
            DroidRowDivider()
            DroidListRow(
                title = "Brain",
                subtitle = "The same markdown vault as Advanced → Brain.",
                icon = Icons.Filled.Psychology,
                onClick = { navController.navigate(Routes.NOTES) },
            )
        }

        SectionLabel("Coding")
        DroidGroup {
            DroidListRow(
                title = "Coding session (preview)",
                subtitle = "Plan, edit, report. A local stand-in: it calls no cloud service and edits no repository.",
                icon = Icons.Filled.Code,
                onClick = { navController.navigate(Routes.CODING) },
            )
        }
    }
}
