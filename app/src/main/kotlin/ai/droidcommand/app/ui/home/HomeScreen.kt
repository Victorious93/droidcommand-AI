package ai.droidcommand.app.ui.home

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.app.engine.EngineModeRepository
import ai.droidcommand.app.navigation.Routes
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.theme.TextSecondary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(private val modes: EngineModeRepository) : ViewModel() {
    val labels = modes.labels
    val mode = modes.mode

    fun select(mode: AgentMode) = modes.select(mode)
}

/**
 * Product shell: the current engine mode and the screens that are actually wired.
 * Never run on a device.
 */
@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val labels by viewModel.labels.collectAsState()
    val mode by viewModel.mode.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Home")
        Text(
            "Chat asks a model. ${labels.labelFor(AgentMode.PILOT)} runs one Pilot tool. ${labels.labelFor(AgentMode.FORGE)} runs a Forge objective on the Plan tab.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Engine", style = MaterialTheme.typography.titleMedium)
                FilterChip(
                    selected = mode == AgentMode.PILOT,
                    onClick = { viewModel.select(AgentMode.PILOT) },
                    label = { Text("${labels.labelFor(AgentMode.PILOT)} · Pilot") },
                )
                FilterChip(
                    selected = mode == AgentMode.FORGE,
                    onClick = { viewModel.select(AgentMode.FORGE) },
                    label = { Text("${labels.labelFor(AgentMode.FORGE)} · Forge") },
                )
                Text(
                    "Rename these labels in Settings. The engine behind each one does not change.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        HomeLink("Chat", "Model replies, plus Run for the selected engine mode.") { onNavigate(Routes.CHAT) }
        HomeLink("Plan", "Forge objectives and the steps they take.") { onNavigate(Routes.PLAN) }
        HomeLink("Tools", "Security tools, clipboards, the vault, and the cloud session.") { onNavigate(Routes.TOOLS) }
        HomeLink("Memory", "Facts saved from chat.") { onNavigate(Routes.MEMORY) }
        HomeLink("Logs", "The on-device audit trail.") { onNavigate(Routes.LOGS) }
        HomeLink("Settings", "API keys, mode names, and toolbar choices.") { onNavigate(Routes.SETTINGS) }
    }
}

@Composable
private fun HomeLink(title: String, body: String, onClick: () -> Unit) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("Open") }
        }
    }
}
