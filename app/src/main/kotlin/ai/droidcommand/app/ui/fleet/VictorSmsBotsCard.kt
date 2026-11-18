package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.theme.AccentRed
import ai.droidcommand.app.ui.theme.TextSecondary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** Lets helper bots queue texts through VictorSMS. Off by default; VictorSMS still asks before every text. */
@Composable
fun VictorSmsBotsCard(viewModel: VictorSmsBotsViewModel = hiltViewModel()) {
    DroidCard(modifier = Modifier.testTag("victorsms_bots_card")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Let bots text through VictorSMS", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Off by default. A bot also needs the VictorSMS text tool ticked in Bot maker, can only text the " +
                            "numbers below, and VictorSMS asks you to Approve every single text on the phone. In VictorSMS, " +
                            "External control must be on and this app approved with the send permission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                Switch(checked = viewModel.switchedOn, onCheckedChange = viewModel::setEnabled)
            }
            OutlinedTextField(
                value = viewModel.recipientsText,
                onValueChange = viewModel::setRecipients,
                label = { Text("Numbers bots may text (one per line)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("victorsms_bots_numbers"),
            )
            if (viewModel.invalidLines.isNotEmpty()) {
                Text(
                    "Not a phone number, ignored: " + viewModel.invalidLines.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentRed,
                )
            }
            Text(
                "Limit: 5 texts an hour, 480 characters each. Bots cannot read your messages or place calls.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}
