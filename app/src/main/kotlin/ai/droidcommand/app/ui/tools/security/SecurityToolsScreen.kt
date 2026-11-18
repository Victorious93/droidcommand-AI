package ai.droidcommand.app.ui.tools.security

import ai.droidcommand.app.termux.AndroidPendingIntentRunCommandClient
import ai.droidcommand.app.ui.components.DroidGroup
import ai.droidcommand.app.ui.components.DroidListRow
import ai.droidcommand.app.ui.components.DroidNote
import ai.droidcommand.app.ui.components.DroidRowDivider
import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.components.StatusPillWithChevron
import ai.droidcommand.app.ui.components.pillColors
import ai.droidcommand.app.ui.theme.DroidTextButton
import ai.droidcommand.setoolkit.SetExecutor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Asks the one SET executor the app is wired with, so this screen and the tool registry cannot disagree about it. */
@HiltViewModel
class SecurityToolsViewModel @Inject constructor(private val setExecutor: SetExecutor) : ViewModel() {
    fun setBackendAvailable(): Boolean = setExecutor.isAvailable()
}

/**
 * Reads the three readiness facts from Android and from the wired SET executor. Read again whenever [recheck] changes,
 * because installing VictorSuite or granting a permission happens outside this app.
 */
@Composable
fun rememberSecurityReadiness(recheck: Int = 0, viewModel: SecurityToolsViewModel = hiltViewModel()): SecurityReadiness {
    val context = LocalContext.current
    return remember(recheck) {
        SecurityReadiness(
            suiteInstalled = AndroidPendingIntentRunCommandClient.isVictorSuiteInstalled(context),
            runCommandGranted = AndroidPendingIntentRunCommandClient.hasRunCommandPermission(context),
            setBackendAvailable = viewModel.setBackendAvailable(),
        )
    }
}

/**
 * The front door to the security tools: whether this phone can run them at all, then the tools themselves. The checks
 * are read from Android and from the wired executors, not assumed, and re-read on "Check again" (and whenever the
 * screen is entered), because installing VictorSuite or granting a permission happens outside this app.
 */
@Composable
fun SecurityToolsScreen(
    onOpenMetasploit: () -> Unit,
    onOpenSet: () -> Unit,
    onOpenLogs: () -> Unit,
    viewModel: SecurityToolsViewModel = hiltViewModel(),
) {
    var checks by rememberSaveable { mutableIntStateOf(0) }
    val readiness = rememberSecurityReadiness(checks, viewModel)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp).testTag("security_tools"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Security tools")
        DroidNote(
            "Each tool runs against one explicitly named target and always asks for your confirmation before anything executes.",
            icon = Icons.Filled.Security,
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Is this phone ready?", modifier = Modifier.weight(1f))
            DroidTextButton(onClick = { checks++ }) { Text("Check again") }
        }
        DroidGroup {
            ReadinessRow("VictorSuite or Termux", readiness.suiteInstalled, readiness.suiteDetail(), notOkTone = PillTone.Warning)
            DroidRowDivider()
            ReadinessRow("Permission to run commands in it", readiness.runCommandGranted, readiness.permissionDetail(), notOkTone = PillTone.Warning)
            DroidRowDivider()
            ReadinessRow("Social-Engineer Toolkit connection", readiness.setBackendAvailable, readiness.setDetail(), notOkTone = PillTone.Error)
        }
        if (readiness.metasploitUsable) {
            DroidNote(
                "Both checks pass, but an on-device Metasploit run has not been verified on a real device yet. Treat the first run as a test.",
                tone = PillTone.Warning,
            )
        }

        SectionLabel("Tools")
        DroidGroup {
            val (msfText, msfTone) = readiness.metasploitPill()
            DroidListRow(
                title = "Metasploit",
                subtitle = "An authorized Kali scan, or one module against one named host.",
                icon = Icons.Filled.Security,
                onClick = onOpenMetasploit,
                modifier = Modifier.testTag("security_open_metasploit"),
                trailing = { StatusPillWithChevron(msfText, msfTone) },
            )
            DroidRowDivider()
            val (setText, setTone) = readiness.setPill()
            DroidListRow(
                title = "Social-Engineer Toolkit",
                subtitle = "Structured attack-vector forms. Runs through the same VictorSuite Kali proot as Metasploit.",
                icon = Icons.Filled.Security,
                onClick = onOpenSet,
                modifier = Modifier.testTag("security_open_set"),
                trailing = { StatusPillWithChevron(setText, setTone) },
            )
        }

        SectionLabel("History")
        DroidGroup {
            DroidListRow(
                title = "Activity log",
                subtitle = "What was approved, denied and run.",
                icon = Icons.Filled.History,
                onClick = onOpenLogs,
                modifier = Modifier.testTag("security_open_logs"),
            )
        }
    }
}

/** One check: an icon that matches the result, what was checked, and one line on what it means. */
@Composable
private fun ReadinessRow(title: String, ok: Boolean, detail: String, notOkTone: PillTone) {
    val tone = if (ok) PillTone.Success else notOkTone
    val tint = pillColors(tone).second
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = when {
                ok -> Icons.Filled.CheckCircle
                notOkTone == PillTone.Error -> Icons.Filled.Error
                else -> Icons.Filled.Warning
            },
            contentDescription = if (ok) "Ready" else "Not ready",
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
