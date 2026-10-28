package ai.droidcommand.app.codeassist

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.AccentRed
import ai.droidcommand.security.CodeAssist
import ai.droidcommand.security.CodeAssistRequest
import ai.droidcommand.security.CodeAssistTask
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Real Allow/Deny consent. [onAllow] is only invoked from the Allow tap;
 * nothing here auto-approves or runs the model.
 */
@Composable
fun CodeAssistConsentScreen(
    callerPackage: String,
    request: CodeAssistRequest,
    running: Boolean,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
        ) {
            ScreenTitle("Code assist request")
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DroidCard {
                    SectionLabel("Caller")
                    Text(callerPackage, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    SectionLabel("Task")
                    Text(taskLabel(request.task), style = MaterialTheme.typography.bodyLarge)
                    if (request.fileName.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        SectionLabel("File")
                        Text(request.fileName, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (request.language.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        SectionLabel("Language")
                        Text(request.language, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (request.truncated) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "VictorFiles marked this code as truncated.",
                            color = AccentRed,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                DroidCard {
                    SectionLabel("Code preview")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = CodeAssist.preview(request.code),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (running) {
                Text(
                    "Running DroidCommand AI…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Deny")
                }
                Button(
                    onClick = onAllow,
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Allow")
                }
            }
        }
    }
}

private fun taskLabel(task: CodeAssistTask): String = when (task) {
    CodeAssistTask.EXPLAIN -> "Explain"
    CodeAssistTask.SUGGEST_EDIT -> "Suggest edit"
}
