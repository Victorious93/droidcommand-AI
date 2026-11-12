package ai.droidcommand.app.bots

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.DroidDangerButton
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import ai.droidcommand.skills.mcp.McpSelections
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** Make small helper bots: a name, what they should do, which tools they may suggest, and an optional model. */
@Composable
fun BotMakerScreen(viewModel: BotMakerViewModel = hiltViewModel(), onRunInChat: () -> Unit = {}) {
    val ui by viewModel.state.collectAsState()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var instructions by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    val clear = {
        editingId = null
        name = ""
        instructions = ""
        model = ""
        selected = emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("bot_maker_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Bot maker")
        Text(
            "Make a helper bot for one job. It runs in AI Chat with your usual AI provider. " +
                "Bots are saved only on this phone, and anything that can change the phone still asks you first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!ui.enabled) {
            Text(
                ui.error ?: "Bot Maker is off. Turn it on under Advanced → Kai capabilities.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        SectionLabel(if (editingId == null) "New bot" else "Edit bot")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    instructions,
                    { instructions = it },
                    label = { Text("What should it do?") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    model,
                    { model = it },
                    label = { Text("Model (optional, blank = Chat's model)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Tools it may suggest", style = MaterialTheme.typography.titleSmall)
                if (ui.tools.isEmpty()) {
                    Text("No tools are available on this phone.", style = MaterialTheme.typography.bodySmall)
                }
                ui.tools.forEach { tool ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = tool.name in selected,
                            onCheckedChange = { on -> selected = if (on) selected + tool.name else selected - tool.name },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(tool.name + if (tool.risky) "  (asks first)" else "", style = MaterialTheme.typography.bodyMedium)
                            Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DroidPrimaryButton(onClick = {
                        if (viewModel.save(editingId, name, instructions, selected.toSet(), model)) clear()
                    }) { Text("Save bot") }
                    if (editingId != null) DroidTextButton(onClick = clear) { Text("Cancel") }
                }
            }
        }
        ui.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        SectionLabel("Your bots")
        if (ui.bots.isEmpty()) Text("No bots yet.", style = MaterialTheme.typography.bodySmall)
        ui.bots.forEach { bot ->
            val toolState = ui.toolState[bot.id]
            val pickAgain = toolState?.needsReselect.orEmpty()
            val shownTools = (bot.allowedTools - pickAgain.toSet()).map { toolState?.current?.get(it) ?: it }.sorted()
            DroidCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(bot.name, style = MaterialTheme.typography.titleMedium)
                    Text(bot.instructions, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    Text(
                        (if (shownTools.isEmpty()) "No tools" else "Tools: " + shownTools.joinToString(", ")) +
                            (if (bot.model.isBlank()) "" else " · Model: ${bot.model}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    McpSelections.reselectWords(pickAgain)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("bot_pick_again_${bot.id}"),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DroidPrimaryButton(onClick = {
                            viewModel.run(bot)
                            onRunInChat()
                        }) { Text("Run") }
                        DroidSecondaryButton(onClick = {
                            editingId = bot.id
                            name = bot.name
                            instructions = bot.instructions
                            model = bot.model
                            selected = viewModel.tickedFor(bot)
                        }) { Text("Edit") }
                        DroidTextButton(onClick = { viewModel.askDelete(bot) }) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (ui.pendingSave != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRiskySave,
            title = { Text("Allow tools that can change the phone?") },
            text = {
                Text(
                    "This bot may suggest: ${ui.pendingRiskyTools.joinToString(", ")}. " +
                        "Each time one of these would run, the app will still ask you to approve it.",
                )
            },
            confirmButton = { DroidPrimaryButton(onClick = viewModel::confirmRiskySave) { Text("Save anyway") } },
            dismissButton = { DroidTextButton(onClick = viewModel::cancelRiskySave) { Text("Go back") } },
        )
    }
    ui.pendingDelete?.let { bot ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("Delete \"${bot.name}\"?") },
            text = { Text("The bot is removed from this phone. This cannot be undone.") },
            confirmButton = { DroidDangerButton(onClick = viewModel::confirmDelete) { Text("Delete") } },
            dismissButton = { DroidTextButton(onClick = viewModel::cancelDelete) { Text("Cancel") } },
        )
    }
}
