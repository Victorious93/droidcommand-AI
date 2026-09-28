package ai.droidcommand.app.ui.tools.setoolkit

import ai.droidcommand.app.ui.tools.ToolRunStatusCard
import ai.droidcommand.app.ui.tools.ToolRunUiState
import ai.droidcommand.setoolkit.SetAttackVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Every field here is structured input — a tapped attack-vector button, a
 * target field, an options row — never a raw command string. "Launch"
 * only enables once at least one of target host / target email is set
 * (mirroring [ai.droidcommand.setoolkit.SetCommand]'s own "no blank/mass
 * target" rule), and still has to clear the real confirmation dialog
 * before [ai.droidcommand.setoolkit.SetTool] is invoked.
 */
@Composable
fun SetToolScreen(viewModel: SetToolViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    var pendingOptionKey by remember { mutableStateOf("") }
    var pendingOptionValue by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Social-Engineer Toolkit", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Every field below is structured input — no command is ever typed. " +
                "Launching an attack vector always asks for explicit confirmation first.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Attack vector", style = MaterialTheme.typography.labelLarge)
        val vectors = SetAttackVector.entries.toList()
        for (row in vectors.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { vector ->
                    val selected = form.attackVector == vector
                    val label = vector.name.replace('_', ' ')
                    if (selected) {
                        Button(onClick = { viewModel.updateForm { it.copy(attackVector = vector) } }) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { viewModel.updateForm { it.copy(attackVector = vector) } }) { Text(label) }
                    }
                }
            }
        }

        OutlinedTextField(
            value = form.targetHost,
            onValueChange = { value -> viewModel.updateForm { it.copy(targetHost = value) } },
            label = { Text("Target host (optional if target email is set)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.targetEmail,
            onValueChange = { value -> viewModel.updateForm { it.copy(targetEmail = value) } },
            label = { Text("Target email (optional if target host is set)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.payload,
            onValueChange = { value -> viewModel.updateForm { it.copy(payload = value) } },
            label = { Text("Payload (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Options", style = MaterialTheme.typography.labelLarge)
        form.options.forEachIndexed { index, pair ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${pair.first} = ${pair.second}", modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    viewModel.updateForm { f -> f.copy(options = f.options.filterIndexed { i, _ -> i != index }) }
                }) { Text("Remove") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = pendingOptionKey,
                onValueChange = { pendingOptionKey = it },
                label = { Text("Key, e.g. SMTP_SERVER") },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = pendingOptionValue,
                onValueChange = { pendingOptionValue = it },
                label = { Text("Value") },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                if (pendingOptionKey.isNotBlank()) {
                    viewModel.updateForm { f -> f.copy(options = f.options + (pendingOptionKey to pendingOptionValue)) }
                    pendingOptionKey = ""
                    pendingOptionValue = ""
                }
            }) { Text("Add") }
        }

        Button(
            onClick = viewModel::runAttack,
            enabled = (form.targetHost.isNotBlank() || form.targetEmail.isNotBlank()) && uiState !is ToolRunUiState.Running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Launch") }

        ToolRunStatusCard(uiState)
    }
}
