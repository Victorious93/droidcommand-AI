package ai.droidcommand.app.ui.tools

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.components.StatusPill
import ai.droidcommand.app.ui.theme.DroidTextButton
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The result of a tool run, shared by every tool screen. Hidden until something has been started, so an idle form
 * does not carry a card that says "Idle". Output is selectable, monospaced and copyable, and scrolls inside the card
 * instead of stretching the page.
 */
@Composable
fun ToolRunStatusCard(state: ToolRunUiState, modifier: Modifier = Modifier) {
    if (state is ToolRunUiState.Idle) return
    val (label, tone) = when (state) {
        is ToolRunUiState.Running -> "Running" to PillTone.Neutral
        is ToolRunUiState.Success -> "Finished" to PillTone.Success
        is ToolRunUiState.Failed -> "Did not complete" to PillTone.Error
        is ToolRunUiState.Idle -> return
    }
    DroidCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Result", modifier = Modifier.weight(1f))
                StatusPill(label, tone)
            }
            when (state) {
                is ToolRunUiState.Running -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Waiting for your approval, then running…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ToolRunUiState.Success -> OutputBlock(state.output.ifBlank { "The tool finished and returned no output." })
                is ToolRunUiState.Failed -> OutputBlock(state.reason)
                is ToolRunUiState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun OutputBlock(text: String) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        DroidTextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Tool output", text))
        }) { Text("Copy output") }
    }
}
