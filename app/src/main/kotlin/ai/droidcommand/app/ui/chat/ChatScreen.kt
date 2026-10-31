package ai.droidcommand.app.ui.chat

import ai.droidcommand.app.localmodels.OpenGgufDocument
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.rememberDraft
import ai.droidcommand.app.ui.theme.AccentBlue
import ai.droidcommand.app.ui.theme.AccentBlueBright
import ai.droidcommand.app.ui.theme.AccentCyan
import ai.droidcommand.app.ui.theme.AccentIndigo
import ai.droidcommand.app.ui.theme.BorderDark
import ai.droidcommand.app.ui.theme.CardDark
import ai.droidcommand.app.ui.theme.OnAccent
import ai.droidcommand.app.ui.theme.TextPrimary
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.app.ui.theme.UserBubbleDark
import ai.droidcommand.app.ux.FriendlyErrors
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.llm.factory.CloudProviderCatalog
import ai.droidcommand.voice.SpeakMode
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Chat in OpenDroid's layout: blue header with a status line, indigo right-aligned user bubbles,
 * bordered left-aligned reply cards with a cyan role label, and a pill input with a round send
 * button. Provider/model/memory controls live in a panel that opens from the header subtitle so
 * the default view stays as clean as OpenDroid's.
 *
 * Never built or run on a device — written without an Android SDK. All chat
 * logic is unchanged and lives in the JVM-tested [ai.droidcommand.llm.factory.ChatSession]; the
 * model field is still free text because model ids change and no list here was verified.
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel(), onOpenModels: () -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    // Saved across rotation, folding and theme changes: with plain remember a half-typed message vanished while the
    // ViewModel (and the conversation) survived.
    var input by rememberDraft()
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    var libraryOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val rows = state.lines + if (state.streaming.isNotEmpty()) listOf(ChatLine(false, state.streaming)) else emptyList()

    val context = LocalContext.current
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.toggleMic(granted)
    }
    // Android 13+: ask for POST_NOTIFICATIONS when the wake word is about to start, so its notification can show.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }
    LaunchedEffect(state.askNotificationPermission) {
        if (state.askNotificationPermission) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    // Storage Access Framework picker: the user picks one file, the app gets read access to just that file.
    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::attachDocument)
    }
    // Model picker: ACTION_OPEN_DOCUMENT, any MIME, persistable read. Name/magic filtering happens after the pick.
    val modelLauncher = rememberLauncherForActivityResult(OpenGgufDocument()) { uri ->
        uri?.let(viewModel::importLocalModel)
    }
    val onMic = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.toggleMic(true)
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // Advanced settings can change while Chat is off screen; pick them up when it is shown again.
    LaunchedEffect(Unit) { viewModel.reloadToolbar() }
    LaunchedEffect(state.pendingInput) {
        state.pendingInput?.let {
            input = if (input.isBlank()) it else input.trimEnd() + " " + it
            viewModel.consumePendingInput()
        }
    }

    LaunchedEffect(rows.size, state.streaming) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header(
            subtitle = if (state.sending) "Thinking…" else subtitleFor(state),
            active = !state.sending,
            optionsOpen = optionsOpen,
            onToggleOptions = { optionsOpen = !optionsOpen },
            onNewChat = viewModel::newChat,
            newChatEnabled = !state.sending,
            libraryOpen = libraryOpen,
            onToggleLibrary = { libraryOpen = !libraryOpen },
        )

        if (optionsOpen) {
            OptionsPanel(state, viewModel, onImportModel = { modelLauncher.launch(Unit) }, onOpenModels = onOpenModels)
        }
        if (libraryOpen) {
            ChatLibrary(state, viewModel)
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                Text(
                    FriendlyErrors.emptyChat(state.activeLocalModelId != null || state.cloudKeyReady),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rows) { line -> MessageBubble(line) }
            }
        }

        if (state.activeLocalModelId == null && !state.cloudKeyReady) {
            Text(
                FriendlyErrors.missingChatSetup(state.provider.label),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        state.error?.let {
            Text(FriendlyErrors.forUser(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }

        state.voice.error?.let {
            Text(FriendlyErrors.forUser(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable(onClick = viewModel::dismissVoiceError).padding(vertical = 4.dp))
        }
        state.wakeWord.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }
        if (state.wakeWord.listeningForWakeWord) {
            Text("Wake word on: the microphone is listening for the wake phrase.", color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }
        if (state.voice.listening && state.voice.partial.isNotEmpty()) {
            Text(state.voice.partial, color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }

        state.notice?.let {
            Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }

        state.documentStatus?.let {
            Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }

        if (state.confirmMeteredDownload) {
            AlertDialog(
                onDismissRequest = viewModel::dismissMeteredEmbedderDownload,
                title = { Text("Use mobile data?") },
                text = { Text("You are on a metered connection. The embedding model is about ${state.embedderDownloadMegabytes} MB. If it is interrupted, the next attempt resumes where it stopped.") },
                confirmButton = { TextButton(onClick = viewModel::confirmMeteredEmbedderDownload) { Text("Download") } },
                dismissButton = { TextButton(onClick = viewModel::dismissMeteredEmbedderDownload) { Text("Not now") } },
            )
        }

        InputBar(
            visibleToggles = state.toolbar,
            documents = state.documents,
            documentsAvailable = state.documentsAvailable,
            attachingDocument = state.attachingDocument,
            embedderDownloadBytes = state.embedderDownloadBytes,
            embedderDownloadMegabytes = state.embedderDownloadMegabytes,
            onDownloadEmbedder = viewModel::downloadEmbedder,
            onCancelEmbedderDownload = viewModel::cancelEmbedderDownload,
            onAttachDocument = { documentLauncher.launch(arrayOf("application/pdf", "text/*", "application/octet-stream")) },
            onRemoveDocument = viewModel::removeDocument,
            webSearchEnabled = state.webSearchEnabled,
            onToggleWebSearch = { viewModel.setWebSearchEnabled(!state.webSearchEnabled) },
            speakMode = state.speakMode,
            onCycleSpeakMode = viewModel::cycleSpeakMode,
            speaking = state.voice.speaking,
            canSpeak = state.lines.any { !it.fromUser },
            onSpeakLast = viewModel::speakLastReply,
            listening = state.voice.listening,
            micEnabled = state.micAvailable && !state.sending,
            onMic = onMic,
            value = input,
            onValueChange = { input = it },
            canSend = !state.sending && input.isNotBlank(),
            onSend = {
                viewModel.send(input)
                input = ""
            },
        )
    }
}

/** Header line: the on-device model while one is active, otherwise the cloud provider and its model. */
private fun subtitleFor(state: ChatUiState): String {
    if (state.activeLocalModelId == null) return "${state.provider.label} · ${state.model}"
    return "On device · " + (state.localModels.firstOrNull { it.id == state.activeLocalModelId }?.label ?: "model")
}

@Composable
private fun Header(
    subtitle: String,
    active: Boolean,
    optionsOpen: Boolean,
    onToggleOptions: () -> Unit,
    onNewChat: () -> Unit,
    newChatEnabled: Boolean,
    libraryOpen: Boolean,
    onToggleLibrary: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ScreenTitleWithHelp("AI Chat", ScreenHelpCatalog.CHAT)
            Text(
                text = subtitle + if (optionsOpen) "  ▴" else "  ▾",
                color = if (active) AccentBlueBright else AccentCyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.clickable(onClick = onToggleOptions).padding(vertical = 4.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            TextButton(onClick = onToggleLibrary) {
                Text(if (libraryOpen) "Hide library" else "Library", color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = onNewChat, enabled = newChatEnabled) {
                Text("New chat", color = TextSecondary)
            }
        }
    }
}

@Composable
private fun OptionsPanel(state: ChatUiState, viewModel: ChatViewModel, onImportModel: () -> Unit, onOpenModels: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val activeLocal = state.localModels.firstOrNull { it.id == state.activeLocalModelId }
    DroidCard(modifier = Modifier.padding(bottom = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text(if (state.activeLocalModelId != null) "On device: ${activeLocal?.label ?: "model"}" else state.provider.label)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    CloudProviderCatalog.all.forEach { spec ->
                        DropdownMenuItem(text = { Text(spec.label) }, onClick = {
                            viewModel.selectProvider(spec)
                            menuOpen = false
                        })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("On-device models", color = TextSecondary) }, onClick = {}, enabled = false)
                    state.localModels.forEach { model ->
                        DropdownMenuItem(text = { Text("On device: ${model.label}") }, onClick = {
                            viewModel.selectLocalModel(model.id)
                            menuOpen = false
                        })
                    }
                    DropdownMenuItem(
                        text = { Text(if (state.localImporting) "Importing…" else "Import a .gguf file…") },
                        enabled = !state.localImporting,
                        onClick = {
                            menuOpen = false
                            onImportModel()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("On-device catalog…") },
                        onClick = {
                            menuOpen = false
                            onOpenModels()
                        },
                    )
                }
            }
            if (state.activeLocalModelId == null) {
                OutlinedTextField(
                    value = state.model,
                    onValueChange = viewModel::setModel,
                    label = { Text("Model (suggested default — edit if it is outdated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    "Replies run on this device. A later cloud turn sends the whole conversation, including these replies, to that provider.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                OutlinedButton(onClick = viewModel::askDeleteLocalModel, enabled = !state.sending) { Text("Delete this model file") }
            }
            state.localStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = state.memoryEnabled, onCheckedChange = viewModel::setMemoryEnabled)
                Text("Memory (applies next time you open Chat)", style = MaterialTheme.typography.bodySmall)
            }
            if (state.memoryEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = state.autoRemember, onCheckedChange = viewModel::setAutoRemember)
                    Text("Auto-remember every 6 replies (one extra request each; applies next time you open Chat)", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::rememberChat, enabled = !state.sending) { Text("Remember this chat") }
                    state.memoryStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Text(
                if (state.brainEnabled) {
                    "Brain notes are on. Matching notes are added to the next reply. Saving still asks first."
                } else {
                    "Brain notes are off. Turn them on under Advanced → Brain notes."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            if (state.brainEnabled) {
                var factTitle by remember { mutableStateOf("") }
                var factBody by remember { mutableStateOf("") }
                OutlinedButton(onClick = viewModel::askSaveChatToBrain, enabled = !state.sending) { Text("Save this chat to Brain") }
                OutlinedTextField(
                    value = factTitle,
                    onValueChange = { factTitle = it },
                    label = { Text("Fact title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = factBody,
                    onValueChange = { factBody = it },
                    label = { Text("Fact to save") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                OutlinedButton(
                    onClick = {
                        viewModel.askSaveFactToBrain(factTitle, factBody)
                    },
                    enabled = !state.sending && factBody.isNotBlank(),
                ) { Text("Save this fact to Brain") }
                state.brainStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (state.pendingBrainSave != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissSaveToBrain,
            title = { Text("Save this note?") },
            text = {
                Text(
                    "This writes ${state.pendingBrainSave.relativePath} in your Brain vault. " +
                        "Nothing is saved until you confirm.",
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmSaveToBrain) { Text("Save") } },
            dismissButton = { TextButton(onClick = viewModel::dismissSaveToBrain) { Text("Cancel") } },
        )
    }
    if (state.confirmDeleteLocalModel) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteLocalModel,
            title = { Text("Delete this model file?") },
            text = { Text("The file is removed from this device. You can import it again from the original file.") },
            confirmButton = { TextButton(onClick = viewModel::confirmDeleteLocalModel) { Text("Delete") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDeleteLocalModel) { Text("Keep") } },
        )
    }
}

@Composable
private fun MessageBubble(line: ChatLine) {
    if (line.fromUser) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = UserBubbleDark,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 6.dp),
                border = BorderStroke(1.dp, AccentIndigo),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(line.text, color = TextPrimary, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
            }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(
                color = CardDark,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 6.dp, bottomEnd = 24.dp),
                border = BorderStroke(1.dp, BorderDark),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ASSISTANT", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(line.text, color = TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun InputBar(
    visibleToggles: Set<ToolbarToggle>,
    documents: List<ai.droidcommand.rag.DocumentInfo>,
    documentsAvailable: Boolean,
    attachingDocument: Boolean,
    embedderDownloadBytes: Long?,
    embedderDownloadMegabytes: Long,
    onDownloadEmbedder: () -> Unit,
    onCancelEmbedderDownload: () -> Unit,
    onAttachDocument: () -> Unit,
    onRemoveDocument: (String) -> Unit,
    webSearchEnabled: Boolean,
    onToggleWebSearch: () -> Unit,
    speakMode: SpeakMode,
    onCycleSpeakMode: () -> Unit,
    speaking: Boolean,
    canSpeak: Boolean,
    onSpeakLast: () -> Unit,
    listening: Boolean,
    micEnabled: Boolean,
    onMic: () -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
) {
    // Document Q&A: attached files' relevant passages are added to each message while they stay attached.
    // Indexing happens on this device; the passages go to the chat provider only inside later messages.
    if (ToolbarToggle.ATTACH in visibleToggles) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onAttachDocument, enabled = documentsAvailable && !attachingDocument) {
                Text(if (attachingDocument) "Indexing…" else "Attach document")
            }
            if (!documentsAvailable) {
                if (embedderDownloadBytes == null) {
                    OutlinedButton(onClick = onDownloadEmbedder) { Text("Download embedding model ($embedderDownloadMegabytes MB)") }
                } else {
                    OutlinedButton(onClick = onCancelEmbedderDownload) { Text("Cancel (${embedderDownloadBytes / BYTES_PER_MEGABYTE} / $embedderDownloadMegabytes MB)") }
                }
            }
        }
    }
    if (documents.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            items(documents, key = { it.docId }) { doc ->
                InputChip(
                    selected = false,
                    onClick = { onRemoveDocument(doc.docId) },
                    label = { Text(doc.name, maxLines = 1) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove ${doc.name}") },
                )
            }
        }
    }
    // Toolbar row above the input: the web-search toggle. When on, each message's text is sent to the
    // configured search provider (Brave/SerpAPI) before the model call — hence the explicit label.
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ToolbarToggle.WEB_SEARCH in visibleToggles) {
            FilterChip(
                selected = webSearchEnabled,
                onClick = onToggleWebSearch,
                label = { Text(if (webSearchEnabled) "Web search: on" else "Web search: off") },
            )
        }
        if (ToolbarToggle.SPEAK in visibleToggles) {
            FilterChip(
                selected = speakMode != SpeakMode.OFF,
                onClick = onCycleSpeakMode,
                label = { Text("Speak: " + speakMode.name.lowercase()) },
            )
        }
        if (ToolbarToggle.SPEAK in visibleToggles && (speakMode == SpeakMode.TAP || speaking)) {
            OutlinedButton(onClick = onSpeakLast, enabled = canSpeak || speaking) { Text(if (speaking) "Stop" else "Speak reply") }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(28.dp),
            maxLines = 4,
            placeholder = { Text("Ask DroidCommand AI…", color = TextSecondary) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CardDark,
                unfocusedContainerColor = CardDark,
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = BorderDark,
                cursorColor = AccentBlueBright,
            ),
        )
        if (ToolbarToggle.MICROPHONE in visibleToggles) {
            IconButton(onClick = onMic, enabled = micEnabled) {
                Icon(
                    if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (listening) "Stop dictation" else "Dictate",
                    tint = if (listening) AccentBlueBright else TextSecondary,
                )
            }
        }
        IconButton(
            onClick = onSend,
            enabled = canSend,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = AccentBlue,
                contentColor = OnAccent,
                disabledContainerColor = CardDark,
                disabledContentColor = TextSecondary,
            ),
            modifier = Modifier.padding(start = 2.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

private const val BYTES_PER_MEGABYTE = 1024L * 1024L
