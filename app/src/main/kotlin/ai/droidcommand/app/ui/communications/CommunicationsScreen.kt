package ai.droidcommand.app.ui.communications

import ai.droidcommand.communications.CommsPermission
import ai.droidcommand.communications.ContentRefs
import ai.droidcommand.communications.FileOp
import ai.droidcommand.communications.ShareForm
import ai.droidcommand.communications.ShareKind
import ai.droidcommand.communications.ShareScope
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private val APPS = listOf("com.victorious.bots" to "VictorBots", "com.victorious.sms" to "VictorSMS")

/**
 * Victorious Framework: who may ask droidcommand-AI to read, draft, send or call through VictorSMS. Nothing is allowed by
 * default. A send always asks you each time unless you also allow automatic replies for one bot.
 */
@Composable
fun CommunicationsScreen(vm: CommunicationsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val reviewing by vm.reviewing.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refresh() }
    LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Communications", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Text(
                "Lets VictorBots and VictorSMS ask this app for AI help and for texts and calls through VictorSMS. Every send waits for you unless you allow automatic replies for one bot.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ui.message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.primary) } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = ui.stopped, onCheckedChange = vm::setStopped)
                Column {
                    Text("Emergency stop", fontWeight = FontWeight.Bold)
                    Text("Blocks every send, call and automatic reply until you turn it off. Reading and pausing still work.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = ui.agentTools, onCheckedChange = vm::setAgentTools)
                Column {
                    Text("Let the assistant use these tools", fontWeight = FontWeight.Bold)
                    Text(
                        "Off by default. When on, Chat, Plan, Pilot and Forge can list, read and search the conversations you shared in VictorSMS, and draft or summarise replies. " +
                            "These tools cannot send, call or change anything. Whatever they read is sent to the model your inference policy picks, which may be a PC or a cloud service.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (ui.agentTools) Text("${ui.agentToolCount} tools offered right now.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Text(
                "Connection: ${ui.available} of ${ui.total} abilities available" + (ui.firstReason?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item { Text("Waiting for you", fontWeight = FontWeight.Bold) }
        if (ui.pending.isEmpty()) item { Text("Nothing is waiting.", style = MaterialTheme.typography.bodySmall) }
        items(ui.pending, key = { it.id }) { a ->
            Column(Modifier.fillMaxWidth()) {
                Text("${APPS.firstOrNull { it.first == a.caller }?.second ?: a.caller} asks to: ${a.summary}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.approve(a.id) }) { Text("Allow once") }
                    OutlinedButton(onClick = { vm.deny(a.id) }) { Text("Deny") }
                }
            }
        }
        item { Text("Files requested by a conversation", fontWeight = FontWeight.Bold) }
        if (ui.fileRequests.isEmpty()) item { Text("No file requests.", style = MaterialTheme.typography.bodySmall) }
        items(ui.fileRequests, key = { it.id }) { r ->
            Column(Modifier.fillMaxWidth()) {
                Text("Conversation ${r.subject.conversation} wants to ${r.op.name.lowercase().replace('_', ' ')}: ${r.path}")
                if (r.triggeredByIncomingMessage) Text("Prompted by a received message. That message cannot grant access; only you can.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.approveFileRequest(r.id) }) { Text("Share this file (24 h)") }
                    OutlinedButton(onClick = { vm.denyFileRequest(r.id) }) { Text("Deny") }
                }
            }
        }
        item { Text("Files you shared", fontWeight = FontWeight.Bold) }
        item { Button(onClick = { sharing = true }) { Text("Share files with a conversation…") } }
        if (ui.shares.isEmpty()) item { Text("No files are shared with any conversation.", style = MaterialTheme.typography.bodySmall) }
        items(ui.shares, key = { it.id }) { g ->
            Column(Modifier.fillMaxWidth()) {
                Text("Conversation ${g.subject.conversation}", fontWeight = FontWeight.Bold)
                Text(g.scopes.joinToString { sc -> shareScopeLabel(sc) }, style = MaterialTheme.typography.bodySmall)
                Text(
                    g.ops.joinToString { it.name.lowercase().replace('_', ' ') } + (if (g.readOnly) " · read-only" else "") + (if (g.requireApproval) " · asks each time" else "") +
                        (g.expiresAtMs?.let { " · expires" } ?: " · no expiry"),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { vm.revokeShare(g.id) }) { Text("Stop sharing") }
            }
        }
        item { Text("Reply drafts", fontWeight = FontWeight.Bold) }
        item {
            Text(
                "For conversations you switch on, this writes a suggested reply to each NEW message. It never sends anything: you read it and decide. Messages that were already there, and imported history, are never answered. It checks when you press the button, or automatically if you turn that on below.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::loadConversations) { Text("Choose conversations") }
                Button(onClick = vm::checkNow, enabled = ui.draftsOn.isNotEmpty()) { Text("Check now") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = ui.autoCheck, onCheckedChange = vm::setAutoCheck)
                Column {
                    Text("Check automatically", fontWeight = FontWeight.Bold)
                    Text("About every 15 minutes or more; Android may delay it to save battery. Only when the battery is not low. Still drafts only.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(ui.sharedConversations, key = { "c" + it.first }) { (id, title) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = id in ui.draftsOn, onCheckedChange = { vm.setDrafts(id, it) })
                Text(title.ifBlank { id })
            }
        }
        if (ui.drafts.isEmpty()) item { Text("No drafts yet.", style = MaterialTheme.typography.bodySmall) }
        items(ui.drafts, key = { it.id }) { d ->
            Column(Modifier.fillMaxWidth()) {
                val conversation = ui.sharedConversations.firstOrNull { it.first == d.conversation }?.second
                val title = if (d.state == "failed") "No draft" else "Suggested reply"
                val subtitle = conversation?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                Text(title + subtitle, fontWeight = FontWeight.Bold)
                Text(d.text.ifBlank { "(needs more information)" })
                d.needsInfo?.let { Text("It needs to know: $it", style = MaterialTheme.typography.bodySmall) }
                if (d.state == "pending" && d.text.isNotBlank()) {
                    OutlinedButton(enabled = !ui.stopped && !reviewing, onClick = { vm.reviewDraft(d.id) }) {
                        Text("Review in VictorSMS")
                    }
                    Row {
                        TextButton(enabled = !reviewing, onClick = { vm.reviewDraft(d.id, statusOnly = true) }) { Text("Check review status") }
                        TextButton(onClick = { vm.dismissDraft(d.id) }) { Text("Dismiss") }
                    }
                } else {
                    TextButton(onClick = { vm.dismissDraft(d.id) }) { Text("Dismiss") }
                }
            }
        }
        item { Text("File access history", fontWeight = FontWeight.Bold) }
        if (ui.fileHistory.isEmpty()) item { Text("No file access has been tried yet.", style = MaterialTheme.typography.bodySmall) }
        items(ui.fileHistory.size) { i ->
            val h = ui.fileHistory[i]
            Text(
                "${h.outcome.replace('_', ' ')} · ${h.op.name.lowercase().replace('_', ' ')} · conversation ${h.conversation} · ${h.path}" + (if (h.outcome == "deny") " (${h.reason})" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item { Text("Apps you allowed", fontWeight = FontWeight.Bold) }
        item { Button(onClick = { adding = true }) { Text("Allow an app…") } }
        if (ui.grants.none { !it.revoked }) item { Text("No app is allowed.", style = MaterialTheme.typography.bodySmall) }
        items(ui.grants.filter { !it.revoked }, key = { it.id }) { g ->
            Column(Modifier.fillMaxWidth()) {
                Text(APPS.firstOrNull { it.first == g.caller }?.second ?: g.caller, fontWeight = FontWeight.Bold)
                Text(g.permissions.joinToString { it.name.lowercase().replace('_', ' ') }, style = MaterialTheme.typography.bodySmall)
                Text(
                    listOfNotNull(g.botId?.let { "bot $it" }, if (g.oneTime) "one use" else null, g.expiresAtMs?.let { "expires" }).joinToString(" · ").ifBlank { "no expiry" },
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { vm.revoke(g.id) }) { Text("Remove") }
            }
        }
    }
    if (sharing) {
        ShareDialog(onDismiss = { sharing = false }, onShare = { f ->
            vm.createShare(f)
            sharing = false
        })
    }
    if (adding) {
        AllowDialog(onDismiss = { adding = false }, onAllow = { app, perms, bot, hours, once ->
            vm.allow(app, perms, bot, hours, once)
            adding = false
        })
    }
}

@Composable
private fun AllowDialog(onDismiss: () -> Unit, onAllow: (String, Set<CommsPermission>, String, Long?, Boolean) -> Unit) {
    var app by remember { mutableStateOf(APPS.first().first) }
    var perms by remember { mutableStateOf(setOf(CommsPermission.READ_MESSAGES, CommsPermission.READ_IMPORTED)) }
    var bot by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf<Long?>(24) }
    var once by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allow an app") },
        text = {
            LazyColumn {
                item {
                    APPS.forEach { (pkg, label) ->
                        TextButton(onClick = { app = pkg }) { Text((if (app == pkg) "● " else "○ ") + label) }
                    }
                }
                item { OutlinedTextField(bot, { bot = it }, label = { Text("Only for this bot id (optional)") }, singleLine = true) }
                item { Text("It may:", fontWeight = FontWeight.Bold) }
                items(CommsPermission.entries.toList()) { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = p in perms, onCheckedChange = { on -> perms = if (on) perms + p else perms - p })
                        Text(p.name.lowercase().replace('_', ' '))
                    }
                }
                item {
                    Text("For how long:", fontWeight = FontWeight.Bold)
                    listOf<Pair<String, Long?>>("1 hour" to 1L, "1 day" to 24L, "1 week" to 168L, "Until I remove it" to null).forEach { (label, h) ->
                        TextButton(onClick = { hours = h }) { Text((if (hours == h) "● " else "○ ") + label) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(once, { once = it })
                        Text("One use only")
                    }
                    Text(
                        "Sending a text or placing a call still asks you each time. Only “enable auto reply” lets one bot reply without asking, and VictorSMS keeps its own limits.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = perms.isNotEmpty(), onClick = { onAllow(app, perms, bot, hours, once) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun shareScopeLabel(sc: ShareScope): String = when (sc) {
    is ShareScope.Folder -> "folder ${sc.path}" + if (sc.subfolders) " (with subfolders)" else ""
    is ShareScope.File -> "file ${sc.path}"
    is ShareScope.Collection -> "collection ${sc.id} (${sc.files.size} files)"
    is ShareScope.Tree -> "picked folder ${pickedName(sc.uri)}" + if (sc.subfolders) " (with subfolders)" else ""
    is ShareScope.Item -> "picked file ${pickedName(sc.uri)}"
}

/** The last part of a picked reference's document id, for display only ("primary:DCIM/Cats" shows as "Cats"). */
private fun pickedName(uri: String): String =
    ContentRefs.parse(uri)?.let { (it.docId ?: it.treeId)?.substringAfterLast('/')?.substringAfterLast(':') }?.takeIf { it.isNotBlank() } ?: "(chosen with the picker)"

private val KIND_LABELS = listOf(
    ShareKind.FOLDER to "This folder only",
    ShareKind.FOLDER_WITH_SUBFOLDERS to "This folder and its subfolders",
    ShareKind.FILE to "One file",
)

@Composable
private fun ShareDialog(onDismiss: () -> Unit, onShare: (ShareForm) -> Unit) {
    var conversation by remember { mutableStateOf("") }
    var bot by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ShareKind.FOLDER) }
    var path by remember { mutableStateOf("") }
    var pickedLabel by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    fun keepAccess(uri: android.net.Uri) {
        // Read access that survives a restart. Not every provider allows it; then the share simply fails to read later, closed.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            keepAccess(uri)
            kind = ShareKind.TREE
            path = uri.toString()
            pickedLabel = "Folder chosen with the Android folder picker"
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(::keepAccess)
            kind = ShareKind.PICKED
            path = uris.joinToString("\n") { it.toString() }
            pickedLabel = "${uris.size} file(s) chosen with the Android file picker"
        }
    }
    val picked = kind == ShareKind.TREE || kind == ShareKind.TREE_WITH_SUBFOLDERS || kind == ShareKind.PICKED
    var ops by remember { mutableStateOf(setOf(FileOp.SEARCH, FileOp.PREVIEW, FileOp.SEND)) }
    var hours by remember { mutableStateOf<Long?>(24) }
    var recipients by remember { mutableStateOf("") }
    var approval by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share files with one conversation") },
        text = {
            LazyColumn {
                item { Text("Nothing else is shared. Other conversations never inherit this.", style = MaterialTheme.typography.bodySmall) }
                item { OutlinedTextField(conversation, { conversation = it }, label = { Text("Conversation id") }, singleLine = true) }
                item { OutlinedTextField(bot, { bot = it }, label = { Text("Only for this bot id (optional)") }, singleLine = true) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Pick a folder…") }
                        OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("Pick files…") }
                    }
                    Text("The system picker lets this app read only what you choose. No storage permission is requested.", style = MaterialTheme.typography.bodySmall)
                }
                if (picked) {
                    item {
                        pickedLabel?.let { Text(it, fontWeight = FontWeight.Bold) }
                        if (kind != ShareKind.PICKED) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(kind == ShareKind.TREE_WITH_SUBFOLDERS, { on -> kind = if (on) ShareKind.TREE_WITH_SUBFOLDERS else ShareKind.TREE })
                                Text("Include subfolders")
                            }
                        }
                        TextButton(onClick = {
                            kind = ShareKind.FOLDER
                            path = ""
                            pickedLabel = null
                        }) { Text("Type a path instead") }
                    }
                } else {
                    item {
                        KIND_LABELS.filter { it.first == ShareKind.FOLDER || it.first == ShareKind.FOLDER_WITH_SUBFOLDERS || it.first == ShareKind.FILE }
                            .forEach { (k, label) -> TextButton(onClick = { kind = k }) { Text((if (kind == k) "● " else "○ ") + label) } }
                    }
                    item { OutlinedTextField(path, { path = it }, label = { Text("Full path, e.g. /storage/emulated/0/DCIM/Cats") }, singleLine = true) }
                }
                item { Text("The bot may:", fontWeight = FontWeight.Bold) }
                items(FileOp.entries.toList()) { op ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = op in ops, onCheckedChange = { on -> ops = if (on) ops + op else ops - op })
                        Text(op.name.lowercase().replace('_', ' '))
                    }
                }
                item { OutlinedTextField(recipients, { recipients = it }, label = { Text("Allowed recipients (blank = this conversation's contact only)") }) }
                item {
                    Text("For how long:", fontWeight = FontWeight.Bold)
                    listOf<Pair<String, Long?>>("1 hour" to 1L, "1 day" to 24L, "1 week" to 168L, "Until I remove it" to null).forEach { (label, h) ->
                        TextButton(onClick = { hours = h }) { Text((if (hours == h) "● " else "○ ") + label) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(approval, { approval = it })
                        Text("Ask me before each send")
                    }
                    Text("Upload, save and modify can change your files; they are never read-only.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = conversation.isNotBlank() && path.isNotBlank() && ops.isNotEmpty(),
                onClick = { onShare(ShareForm(conversation, bot, kind, path, ops, hours, recipients, approval)) },
            ) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
