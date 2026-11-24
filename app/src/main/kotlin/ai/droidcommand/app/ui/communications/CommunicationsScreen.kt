package ai.droidcommand.app.ui.communications

import ai.droidcommand.communications.CommsPermission
import ai.droidcommand.communications.ShareScope
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
    var adding by remember { mutableStateOf(false) }
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
}
