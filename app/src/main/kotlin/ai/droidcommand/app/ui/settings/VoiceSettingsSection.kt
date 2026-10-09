package ai.droidcommand.app.ui.settings

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.voice.TtsEngineChoice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Voice section of Settings. UNBUILT/UNTESTED. Options this build cannot honour are shown disabled with the
 * reason, not hidden and not pretended.
 */
@Composable
fun VoiceSettingsSection(viewModel: VoiceSettingsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsState()
    val s = ui.settings
    SectionLabel("Voice")
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Spoken replies", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.setEngine(TtsEngineChoice.SYSTEM) }, enabled = s.ttsEngine != TtsEngineChoice.SYSTEM) { Text("System voice") }
                OutlinedButton(onClick = { viewModel.setEngine(TtsEngineChoice.NEURAL) }, enabled = ui.features.neuralTts && s.ttsEngine != TtsEngineChoice.NEURAL) { Text("Neural voice") }
            }
            Note(if (ui.features.neuralTts) "Neural voice falls back to the system voice if its model is missing." else "Download the English voice under Voice models to use it.")
        }
    }
    ToggleCard(
        title = "Wake word",
        checked = s.wakeWord.enabled,
        enabled = ui.features.wakeWord,
        note = if (ui.features.wakeWord) "Keeps the microphone on and shows a notification while listening. Audio is not recorded or sent." else "Download the wake word model under Voice models to use it.",
        onChange = viewModel::setWakeWord,
    )
    ToggleCard(
        title = "Keep listening when the screen is off",
        checked = s.wakeWord.listenWhenScreenOff,
        enabled = ui.features.wakeWord && s.wakeWord.enabled,
        note = "Off by default: costs battery and keeps the microphone open while the phone is locked.",
        onChange = viewModel::setWakeWordScreenOff,
    )
    ToggleCard(
        title = "Voice approvals",
        checked = s.approval.enabled,
        enabled = true,
        note = "When an action asks for approval, the request is read aloud and you can say \"deny\" to refuse it. Approving always needs the on-screen button. Off by default.",
        onChange = viewModel::setApprovals,
    )
    ToggleCard(
        title = "Allow approving by voice",
        checked = s.approval.allowApproveByVoice,
        enabled = false,
        note = "Not available: every action that asks for approval in this app is treated as destructive, and destructive actions can only be approved on screen.",
        onChange = viewModel::setApproveByVoice,
    )
    SectionLabel("Voice models")
    if (ui.models.isEmpty()) {
        Note("No voice models are listed.")
    }
    ui.models.forEach { m ->
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(m.name, style = MaterialTheme.typography.titleMedium)
                Note("License: ${m.license}")
                m.message?.let { Note(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.install(m.id) }, enabled = !m.busy && !m.installed) { Text(if (m.busy) "Downloading…" else "Download") }
                    OutlinedButton(onClick = { viewModel.delete(m.id) }, enabled = !m.busy && m.installed) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun ToggleCard(title: String, checked: Boolean, enabled: Boolean, note: String, onChange: (Boolean) -> Unit) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
            }
            Note(note)
        }
    }
}

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
