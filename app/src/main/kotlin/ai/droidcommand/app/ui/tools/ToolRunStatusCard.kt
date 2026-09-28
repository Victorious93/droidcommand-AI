package ai.droidcommand.app.ui.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ToolRunStatusCard(state: ToolRunUiState) {
    val label = when (state) {
        is ToolRunUiState.Idle -> "Idle"
        is ToolRunUiState.Running -> "Running…"
        is ToolRunUiState.Success -> "Success"
        is ToolRunUiState.Failed -> "Failed"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            when (state) {
                is ToolRunUiState.Success -> Text(state.output)
                is ToolRunUiState.Failed -> Text(state.reason)
                else -> Unit
            }
        }
    }
}
