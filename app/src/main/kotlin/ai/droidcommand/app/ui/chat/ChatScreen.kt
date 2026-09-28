package ai.droidcommand.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Phase 0 stub. Real multi-provider streaming chat is Phase 1 — see docs/ARCHITECTURE.md. */
@Composable
fun ChatScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Chat", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Multi-provider streaming chat arrives in Phase 1 (Multi-Provider Cloud BYOK). " +
                "This screen is an intentional stub.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
