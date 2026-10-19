package ai.droidcommand.app.shizuku

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.AccentBlueBright
import ai.droidcommand.app.ui.theme.AccentCyan
import ai.droidcommand.app.ui.theme.AccentSuccess
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.shizuku.ShizukuCopy
import ai.droidcommand.shizuku.ShizukuState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** Settings / Tools status card. Four states, neon indicator, request-permission copy. */
@Composable
fun ShizukuStatusSection(viewModel: ShizukuStatusViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    SectionLabel(ShizukuCopy.SECTION_TITLE)
    ShizukuStatusCard(state = state, onRequestPermission = viewModel::requestPermission)
}

@Composable
fun ShizukuStatusCard(
    state: ShizukuState,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DroidCard(modifier = modifier.testTag("shizuku_status_card")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    modifier = Modifier.size(10.dp).testTag("shizuku_status_dot"),
                    shape = CircleShape,
                    color = indicatorColor(state),
                ) {}
                Text(
                    ShizukuCopy.statusTitle(state),
                    style = MaterialTheme.typography.titleMedium,
                    color = indicatorColor(state),
                )
            }
            Text(
                ShizukuCopy.statusBody(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state == ShizukuState.DENIED) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.testTag("shizuku_request_permission"),
                ) {
                    Text(ShizukuCopy.REQUEST_BUTTON)
                }
            }
        }
    }
}

private fun indicatorColor(state: ShizukuState): Color = when (state) {
    ShizukuState.GRANTED -> AccentSuccess
    ShizukuState.DENIED -> AccentBlueBright
    ShizukuState.NOT_RUNNING -> AccentCyan
    ShizukuState.NOT_INSTALLED -> TextSecondary
}
