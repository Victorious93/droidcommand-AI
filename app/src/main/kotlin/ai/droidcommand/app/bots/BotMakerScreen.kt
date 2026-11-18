package ai.droidcommand.app.bots

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.DroidDangerButton
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import ai.droidcommand.skills.mcp.McpSelections
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Make small helper bots. The page is a list of your bots first (what each can do, at a glance), and one editor
 * that opens on demand, split into four plain sections: Basics, Tools, Talks to other bots, and Own sandbox.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BotMakerScreen(viewModel: BotMakerViewModel = hiltViewModel(), onRunInChat: () -> Unit = {}) {
    val ui by viewModel.state.collectAsState()
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var instructions by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var talksTo by rememberSaveable { mutableStateOf(listOf<String>()) }
    var sandbox by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(listOf<String>()) }
    val scroll = rememberScrollState()
    // The editor opens at the top of the page. Without this, tapping Edit on a card further down would open it
    // out of sight, and it would look as if nothing happened.
    LaunchedEffect(editorOpen, editingId) { if (editorOpen) scroll.animateScrollTo(0) }
    val close = {
        editorOpen = false
        editingId = null
        name = ""
        instructions = ""
        model = ""
        selected = emptyList()
        talksTo = emptyList()
        sandbox = false
        query = ""
        expanded = emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp)
            .testTag("bot_maker_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Bot maker")
        Text(
            "A helper bot does one job. It runs in AI Chat with your usual AI provider, is saved only on this phone, " +
                "and anything that can change the phone still asks you first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!ui.enabled) {
            DroidCard(modifier = Modifier.testTag("bot_maker_off")) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bot maker is off", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Helper bots can be given tools that change this phone, so this ships switched off. " +
                            "Turning it on lets you make bots here; every risky tool still asks you first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DroidPrimaryButton(onClick = viewModel::turnOn, modifier = Modifier.fillMaxWidth().testTag("bot_maker_turn_on")) {
                        Text("Turn on Bot maker")
                    }
                }
            }
        }
        ui.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        // 1. The editor, only while making or changing a bot. It sits above the list so it is the first thing seen.
        if (editorOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("bot_editor")) {
                SectionLabel(if (editingId == null) "New bot" else "Edit bot")
                EditorSection("1 · Basics") {
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
                        label = { Text("Model (optional)") },
                        supportingText = { Text("Leave blank to use the model Chat uses.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                EditorSection("2 · Tools") {
                    ToolPicker(
                        groups = ui.groups,
                        selected = selected,
                        query = query,
                        expanded = expanded,
                        onQuery = { query = it },
                        onExpanded = { expanded = it },
                        onSelected = { selected = it },
                    )
                }
                EditorSection("3 · Talks to other bots") {
                    PeerPicker(others = ui.bots.filter { it.id != editingId }, talksTo = talksTo, onChange = { talksTo = it })
                }
                EditorSection("4 · Own sandbox") {
                    SandboxPicker(on = sandbox, linuxOn = ui.linuxOn, onChange = { sandbox = it })
                }
                ui.error?.let { if (ui.enabled) Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "${selected.size} tools · " + (if (talksTo.isEmpty()) "talks to no bots" else "talks to ${talksTo.size} bot${if (talksTo.size == 1) "" else "s"}") +
                        " · sandbox " + if (sandbox) "on" else "off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DroidPrimaryButton(
                        onClick = { if (viewModel.save(editingId, name, instructions, selected.toSet(), model, talksTo.toSet(), sandbox)) close() },
                        modifier = Modifier.testTag("bot_save_button"),
                    ) { Text("Save bot") }
                    DroidTextButton(onClick = close) { Text("Cancel") }
                }
            }
        }
        // 2. Your bots, with what each one can do at a glance.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Your bots")
            if (!editorOpen) {
                DroidPrimaryButton(onClick = { editorOpen = true }, enabled = ui.enabled, modifier = Modifier.testTag("bot_new_button")) { Text("New bot") }
            }
        }
        if (ui.bots.isEmpty()) {
            Text("No bots yet. Tap New bot to make one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ui.bots.forEach { bot ->
            BotCard(
                bot = bot,
                ui = ui,
                onRun = {
                    viewModel.run(bot)
                    onRunInChat()
                },
                onEdit = {
                    editorOpen = true
                    editingId = bot.id
                    name = bot.name
                    instructions = bot.instructions
                    model = bot.model
                    selected = viewModel.tickedFor(bot)
                    talksTo = bot.talksTo.toList()
                    sandbox = bot.sandbox
                    expanded = ui.groups.filter { g -> g.tools.any { it.name in selected } }.map { it.key }
                },
                onDelete = { viewModel.askDelete(bot) },
            )
        }
    }

    if (ui.pendingSave != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRiskySave,
            title = { Text("Allow tools that can change the phone?") },
            text = {
                Text(
                    "This bot may use: ${ui.pendingRiskyTools.joinToString(", ")}. " +
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
            text = { Text("The bot is removed from this phone, and from every other bot's \"talks to\" list. This cannot be undone.") },
            confirmButton = { DroidDangerButton(onClick = viewModel::confirmDelete) { Text("Delete") } },
            dismissButton = { DroidTextButton(onClick = viewModel::cancelDelete) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BotCard(bot: HelperBot, ui: BotMakerUi, onRun: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val toolState = ui.toolState[bot.id]
    val pickAgain = toolState?.needsReselect.orEmpty()
    val shownTools = (bot.allowedTools - pickAgain.toSet()).size
    val peerNames = bot.talksTo.mapNotNull { id -> ui.bots.firstOrNull { it.id == id }?.name }.sorted()
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(bot.name, style = MaterialTheme.typography.titleMedium)
            Text(bot.instructions, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoChip(if (shownTools == 0) "Chat only" else "$shownTools tool${if (shownTools == 1) "" else "s"}")
                if (peerNames.isNotEmpty()) InfoChip("Talks to " + peerNames.joinToString(", "))
                if (bot.sandbox) InfoChip(if (ui.linuxOn) "Sandbox" else "Sandbox (Linux switch off)")
                if (bot.model.isNotBlank()) InfoChip(bot.model)
            }
            McpSelections.reselectWords(pickAgain)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("bot_pick_again_${bot.id}"),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DroidPrimaryButton(onClick = onRun) { Text("Run") }
                DroidSecondaryButton(onClick = onEdit) { Text("Edit") }
                DroidTextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EditorSection(title: String, content: @Composable () -> Unit) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun ToolPicker(
    groups: List<BotToolGroup>,
    selected: List<String>,
    query: String,
    expanded: List<String>,
    onQuery: (String) -> Unit,
    onExpanded: (List<String>) -> Unit,
    onSelected: (List<String>) -> Unit,
) {
    Text(
        "Tick what this bot may use. Tools marked \"asks first\" still need your approval every time. MCP tools are grouped by server.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(query, onQuery, label = { Text("Search tools") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("${selected.size} selected", style = MaterialTheme.typography.labelMedium)
    if (groups.isEmpty()) Text("No tools are available on this phone.", style = MaterialTheme.typography.bodySmall)
    groups.forEach { group ->
        val shown = BotToolGroups.filter(group.tools, query)
        if (shown.isEmpty()) return@forEach
        val open = query.isNotBlank() || group.key in expanded
        val picked = group.tools.count { it.name in selected }
        HorizontalDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onExpanded(if (group.key in expanded) expanded - group.key else expanded + group.key) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(group.title, style = MaterialTheme.typography.titleSmall)
                Text("$picked of ${group.tools.size} ticked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DroidTextButton(onClick = {
                val names = group.tools.map { it.name }.toSet()
                onSelected(if (picked == group.tools.size) selected.filterNot { it in names } else (selected + names).distinct())
            }) { Text(if (picked == group.tools.size) "None" else "All") }
            Text(if (open) "▲" else "▼", style = MaterialTheme.typography.labelMedium)
        }
        if (open) {
            shown.forEach { tool ->
                val on = tool.name in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelected(if (on) selected - tool.name else selected + tool.name) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tool.name + if (tool.risky) "  (asks first)" else "", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            tool.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerPicker(others: List<HelperBot>, talksTo: List<String>, onChange: (List<String>) -> Unit) {
    Text(
        "A bot can only message the bots you tick here, and only one way: ticking Writer for Research lets Research " +
            "send to Writer, not the other way round. A message waits in the other bot's mailbox until you run that bot; " +
            "it reads it with its own tool, and nothing in it gives it any extra permission. Mailboxes are kept in memory " +
            "and are cleared when the app restarts. Every send is logged in the Activity log.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (others.isEmpty()) Text("Make a second bot first, then you can let them talk.", style = MaterialTheme.typography.bodySmall)
    others.forEach { other ->
        val on = other.id in talksTo
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onChange(if (on) talksTo - other.id else talksTo + other.id) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
            Text(other.name, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SandboxPicker(on: Boolean, linuxOn: Boolean, onChange: (Boolean) -> Unit) {
    Text(
        "Give this bot its own Linux sandbox in Termux or VictorSuite, kept apart from your Termux files and from other " +
            "bots. It is a separate userland, not a locked vault: it can still use the network. The app asks you before " +
            "every step.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Own Linux sandbox", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = on, onCheckedChange = onChange, enabled = linuxOn)
    }
    if (!linuxOn) {
        Text(
            "Turn on \"Linux environment\" under Advanced → Kai capabilities first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
