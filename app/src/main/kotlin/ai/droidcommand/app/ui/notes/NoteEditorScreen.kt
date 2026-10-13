package ai.droidcommand.app.ui.notes

import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.theme.AccentCyan
import ai.droidcommand.app.ui.theme.CardDark
import ai.droidcommand.app.ui.theme.TextPrimary
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.notes.MarkdownBlock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

/** Edit one note, or preview it after enabled plugins have transformed the text in memory. */
@Composable
fun NoteEditorScreen(path: String, onBack: () -> Unit, viewModel: NotesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var preview by remember { mutableStateOf(false) }
    LaunchedEffect(path) { if (path.isNotBlank()) viewModel.open(path) }
    val ready = state.openPath == path

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            TextButton(onClick = { preview = !preview }, enabled = ready) { Text(if (preview) "Edit" else "Preview") }
        }
        ScreenTitle(if (ready) path.substringAfterLast('/').removeSuffix(".md") else "Note")
        Text(path, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        state.status?.let { Text(it, color = AccentCyan, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (!ready) {
            Text("Loading…", color = TextSecondary)
        } else if (preview) {
            MarkdownPreview(viewModel.preview(state.draft), modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()))
        } else {
            BasicTextField(
                value = state.draft,
                onValueChange = viewModel::updateDraft,
                textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            )
        }
        if (ready) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::save, modifier = Modifier.weight(1f)) { Text("Save") }
                OutlinedButton(onClick = viewModel::applyPlugins, modifier = Modifier.weight(1f)) { Text("Apply plugins") }
            }
        }
    }
}

@Composable
private fun MarkdownPreview(blocks: List<MarkdownBlock>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (blocks.isEmpty()) Text("Nothing to preview.", color = TextSecondary)
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    block.text,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = (24 - block.level * 2).sp,
                )
                is MarkdownBlock.Bullet -> Text("• ${block.text}", color = TextPrimary)
                is MarkdownBlock.Paragraph -> Text(block.text, color = TextPrimary)
                is MarkdownBlock.Code -> Text(
                    block.text,
                    color = AccentCyan,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().background(CardDark).padding(8.dp),
                )
            }
        }
    }
}
