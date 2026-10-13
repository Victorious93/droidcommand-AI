package ai.droidcommand.app.ui.settings

import ai.droidcommand.app.modes.ModeLabelViewModel
import ai.droidcommand.app.modes.ProductMode
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.TextSecondary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** Rename the two mode labels. Blank saves restore "Mode 1" and "Mode 2". The engines stay Pilot and Forge. */
@Composable
fun ModeNamesSection(viewModel: ModeLabelViewModel = hiltViewModel()) {
    val labels by viewModel.labels.collectAsState()
    var modeOne by remember(labels.modeOne) { mutableStateOf(labels.modeOne) }
    var modeTwo by remember(labels.modeTwo) { mutableStateOf(labels.modeTwo) }
    SectionLabel("Mode names")
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Mode 1 runs the Pilot engine (chat, plus one direct tool). Mode 2 runs the Forge engine (the objective loop). These names are yours; the engines do not change.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            OutlinedTextField(
                value = modeOne,
                onValueChange = { modeOne = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("${ProductMode.MODE_ONE.engineName} label") },
            )
            OutlinedTextField(
                value = modeTwo,
                onValueChange = { modeTwo = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("${ProductMode.MODE_TWO.engineName} label") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.rename(ProductMode.MODE_ONE, modeOne)
                        viewModel.rename(ProductMode.MODE_TWO, modeTwo)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save names") }
                OutlinedButton(onClick = viewModel::resetToDefaults, modifier = Modifier.weight(1f)) { Text("Reset") }
            }
        }
    }
}
