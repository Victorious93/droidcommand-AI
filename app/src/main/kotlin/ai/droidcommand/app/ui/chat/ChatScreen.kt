package ai.droidcommand.app.ui.chat

import ai.droidcommand.app.localmodels.OpenGgufDocument
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.components.rememberDraft
import ai.droidcommand.app.ui.theme.DroidIconButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import ai.droidcommand.app.ux.FriendlyErrors
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.llm.factory.CloudProviderCatalog
import ai.droidcommand.llm.pchost.PcHostModels
import ai.droidcommand.llm.pchost.PcHostToolNotices
import ai.droidcommand.llm.pchost.PcModelLocation
import ai.droidcommand.llm.routing.InferencePolicy
import ai.droidcommand.voice.SpeakMode
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel

package ai.droidcommand.app.ui.chat


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
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel(), onOpenModels: () -> Unit = {}, onOpenPcHosts: () -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.pickUpBot() }
    DisposableEffect(Unit) {
        viewModel.startWakeWordIfEnabled()
        onDispose { viewModel.stopWakeWord() }
    }
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

        // Options and Library open in bottom sheets that scroll on their own. Inline they sat above the message list
        // with no scrolling, so on a phone the lower half (policy, memory, brain notes) ran under the navigation bar.
        if (optionsOpen) {
            ChatSheet(title = "Chat options", onDismiss = { optionsOpen = false }) {
                OptionsPanel(state, viewModel, onImportModel = { modelLauncher.launch(Unit) }, onOpenModels = onOpenModels, onOpenPcHosts = onOpenPcHosts)
            }
        }
        if (libraryOpen) {
            ChatSheet(title = "Library", onDismiss = { libraryOpen = false }) {
                ChatLibrary(state, viewModel)
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                EmptyChat(
                    message = FriendlyErrors.emptyChat(state.activeLocalModelId != null || state.activePcHost != null || state.cloudKeyReady),
                    onSuggestion = { input = it },
                    onOpenOptions = { optionsOpen = true },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(rows) { line -> MessageBubble(line) }
            }
        }

        if (state.activeLocalModelId == null && state.activePcHost == null && !state.cloudKeyReady) {
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
            Text("Wake word on: the microphone is listening for the wake phrase.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }
        if (state.voice.listening && state.voice.partial.isNotEmpty()) {
            Text(state.voice.partial, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }

        state.activeBotName?.let { botName ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text("Helper bot: $botName", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                DroidTextButton(onClick = viewModel::stopBot) { Text("Stop bot") }
            }
            state.botToolStatus?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
            }
        }

        state.notice?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }

        // Under an inference policy: which device or endpoint and model answered, and why an earlier one did not.
        state.routeSummary?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp).testTag("chat_route_summary"),
            )
        }

        state.documentStatus?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }

        if (state.confirmMeteredDownload) {
            AlertDialog(
                onDismissRequest = viewModel::dismissMeteredEmbedderDownload,
                title = { Text("Use mobile data?") },
                text = { Text("You are on a metered connection. The embedding model is about ${state.embedderDownloadMegabytes} MB. If it is interrupted, the next attempt resumes where it stopped.") },
                confirmButton = { DroidTextButton(onClick = viewModel::confirmMeteredEmbedderDownload) { Text("Download") } },
                dismissButton = { DroidTextButton(onClick = viewModel::dismissMeteredEmbedderDownload) { Text("Not now") } },
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
            sending = state.sending,
            onSend = {
                viewModel.send(input)
                input = ""
            },
            onStop = viewModel::stopSending,
        )
    }
}

/** Header line: the policy when one is set; otherwise the on-device model while one is active, or the cloud provider and its model. */
private fun subtitleFor(state: ChatUiState): String {
    if (state.inferencePolicy.routed) {
        val unconfirmed = state.inferencePolicy == InferencePolicy.CLOUD_FALLBACK && !state.cloudFallbackConfirmed
        return "Policy · " + (if (unconfirmed) InferencePolicy.PC_THEN_DEVICE.label else state.inferencePolicy.label)
    }
    state.activePcHost?.let { kind ->
        val saved = state.pcHosts[kind]
        val cloud = if (saved?.location == PcModelLocation.CLOUD_BACKED) " (cloud-backed)" else ""
        return "On your PC · ${kind.label} · ${saved?.model?.ifBlank { null } ?: "no model"}$cloud"
    }
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
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScreenTitleWithHelp("AI Chat", ScreenHelpCatalog.CHAT)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // One tap target for "which model / where do replies come from": the status pill itself opens the options sheet.
            Surface(
                onClick = onToggleOptions,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                modifier = Modifier.weight(1f).testTag("chat_status_pill"),
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Tune, contentDescription = if (optionsOpen) "Close chat options" else "Open chat options", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(
                        text = subtitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            DroidIconButton(onClick = onToggleLibrary) {
                Icon(Icons.Filled.Bookmark, contentDescription = if (libraryOpen) "Hide library" else "Library", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DroidIconButton(onClick = onNewChat, enabled = newChatEnabled) {
                Icon(Icons.Filled.Add, contentDescription = "New chat", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A modal bottom sheet whose body scrolls and clears the keyboard and navigation bar, so no control can end up unreachable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

private val SUGGESTIONS = listOf(
    "Explain something simply",
    "Help me write an email",
    "Summarise this for me",
    "Plan my day",
)

/** The empty conversation: say what to do next (and what is missing), and offer starters that only fill the input. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChat(message: String, onSuggestion: (String) -> Unit, onOpenOptions: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SUGGESTIONS.forEach { text ->
                SuggestionChip(onClick = { onSuggestion(text) }, label = { Text(text) })
            }
        }
        DroidTextButton(onClick = onOpenOptions) { Text("Choose model or where replies come from") }
    }
}

/**
 * "Where replies come from": the owner's inference policy. It applies to Chat, helper bots, voice requests, Plan, the
 * assist pop-ups, web search and remembering. Cloud fallback asks for confirmation before it is saved.
 */
@Composable
private fun InferencePolicyPicker(state: ChatUiState, viewModel: ChatViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("inference_policy_picker")) {
        SectionLabel("Where replies come from")
        InferencePolicy.entries.forEach { policy ->
            val selected = state.inferencePolicy == policy
            Surface(
                onClick = { viewModel.setInferencePolicy(policy) },
                enabled = !state.sending,
                shape = RoundedCornerShape(14.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth().testTag("inference_policy_${policy.id}"),
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = if (selected) "Selected" else "Not selected",
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(policy.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        // Only the chosen policy shows its explanation; five paragraphs at once was the wall of text.
                        if (selected) {
                            Text(policy.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (state.inferencePolicy.routed) {
            val device = state.localModels.firstOrNull { it.id == state.activeLocalModelId }?.label
            Text(
                "Applies to Chat, helper bots, voice requests, Plan, the assist pop-ups, web search and remembering. " +
                    "This device uses the model picked in Models" + (device?.let { " ($it)" } ?: "") +
                    "; the PC uses the server and model saved in Settings, PC-hosted models. " +
                    "A slash command that names a cloud provider is refused unless cloud fallback is on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OptionsPanel(
    state: ChatUiState,
    viewModel: ChatViewModel,
    onImportModel: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPcHosts: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val activeLocal = state.localModels.firstOrNull { it.id == state.activeLocalModelId }
    Column {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Model")
            Box(modifier = Modifier.fillMaxWidth()) {
                DroidSecondaryButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            state.activePcHost != null -> "On your PC: ${state.activePcHost.label}"
                            state.activeLocalModelId != null -> "On device: ${activeLocal?.label ?: "model"}"
                            else -> state.provider.label
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    CloudProviderCatalog.all.forEach { spec ->
                        DropdownMenuItem(text = { Text(spec.label) }, onClick = {
                            viewModel.selectProvider(spec)
                            menuOpen = false
                        })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("On-device models", color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = {}, enabled = false)
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
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Models on your PC", color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = {}, enabled = false)
                    state.pcHosts.filterValues { it.model.isNotBlank() }.forEach { (kind, saved) ->
                        DropdownMenuItem(text = { Text("On your PC: ${kind.label} · ${saved.model}") }, onClick = {
                            viewModel.selectPcHost(kind)
                            menuOpen = false
                        })
                    }
                    DropdownMenuItem(
                        text = { Text("Set up a PC server…") },
                        onClick = {
                            menuOpen = false
                            onOpenPcHosts()
                        },
                    )
                }
            }
            if (state.activePcHost != null) {
                val saved = state.pcHosts[state.activePcHost]
                Text(
                    "Messages are sent to your PC at ${saved?.address ?: "an address that is not saved yet"}, not to a cloud provider. " +
                        "Model: ${saved?.model?.ifBlank { null } ?: "none"} — ${saved?.let { PcHostModels.locationLabel(it.location, it.upstream) } ?: "not set up"}. " +
                        "${saved?.takeIf { it.model.isNotBlank() }?.let { PcHostToolNotices.status(it.toolSupport, it.toolsForced) + ". " }.orEmpty()}" +
                        "This needs the phone to reach the PC (same Wi-Fi or VPN), not internet access. " +
                        "A later cloud turn sends the whole conversation, including these replies, to that provider.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DroidSecondaryButton(onClick = onOpenPcHosts) { Text("PC-hosted models…") }
            } else if (state.activeLocalModelId == null) {
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DroidSecondaryButton(onClick = viewModel::askDeleteLocalModel, enabled = !state.sending) { Text("Delete this model file") }
            }
            state.localStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            InferencePolicyPicker(state, viewModel)
            HorizontalDivider()
            SectionLabel("Memory")
            if (state.localImporting) {
                DroidSecondaryButton(onClick = viewModel::cancelLocalImport) { Text("Cancel import") }
            }
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
                    DroidSecondaryButton(onClick = viewModel::rememberChat, enabled = !state.sending) { Text("Remember this chat") }
                    state.memoryStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            HorizontalDivider()
            SectionLabel("Brain notes")
            Text(
                if (state.brainEnabled) {
                    "Brain notes are on. Matching notes are added to the next reply. Saving still asks first."
                } else {
                    "Brain notes are off. Turn them on with AI can read and write these notes on the Brain screen."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.brainEnabled) {
                var factTitle by remember { mutableStateOf("") }
                var factBody by remember { mutableStateOf("") }
                DroidSecondaryButton(onClick = viewModel::askSaveChatToBrain, enabled = !state.sending) { Text("Save this chat to Brain") }
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
                DroidSecondaryButton(
                    onClick = {
                        viewModel.askSaveFactToBrain(factTitle, factBody)
                    },
                    enabled = !state.sending && factBody.isNotBlank(),
                ) { Text("Save this fact to Brain") }
                state.brainStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (state.confirmCloudFallback) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCloudFallback,
            title = { Text("Allow cloud fallback?") },
            text = {
                Text(
                    "When neither your PC nor this device can answer, the whole conversation, with any attached excerpts and " +
                        "notes added to a message, will be sent to ${state.provider.label} (the cloud provider picked here). " +
                        "Web search, if you switch it on, also becomes possible. Every reply says where it came from.",
                )
            },
            confirmButton = {
                DroidTextButton(onClick = viewModel::confirmCloudFallback, modifier = Modifier.testTag("cloud_fallback_confirm")) { Text("Yes, allow cloud") }
            },
            dismissButton = {
                DroidTextButton(onClick = viewModel::dismissCloudFallback, modifier = Modifier.testTag("cloud_fallback_cancel")) { Text("No") }
            },
        )
    }
    if (state.pendingRiskySend != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRiskySend,
            title = { Text("Send this request?") },
            text = {
                Text(
                    (state.pendingRiskyReason ?: "This sounds like it could change or delete something.") +
                        "\n\n\"" + state.pendingRiskySend + "\"",
                )
            },
            confirmButton = { DroidTextButton(onClick = viewModel::confirmRiskySend, modifier = Modifier.testTag("jarvis_risky_confirm")) { Text("Yes, send") } },
            dismissButton = { DroidTextButton(onClick = viewModel::dismissRiskySend, modifier = Modifier.testTag("jarvis_risky_cancel")) { Text("No") } },
            modifier = Modifier.testTag("jarvis_risky_dialog"),
        )
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
            confirmButton = { DroidTextButton(onClick = viewModel::confirmSaveToBrain) { Text("Save") } },
            dismissButton = { DroidTextButton(onClick = viewModel::dismissSaveToBrain) { Text("Cancel") } },
        )
    }
    if (state.confirmDeleteLocalModel) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteLocalModel,
            title = { Text("Delete this model file?") },
            text = { Text("The file is removed from this device. You can import it again from the original file.") },
            confirmButton = { DroidTextButton(onClick = viewModel::confirmDeleteLocalModel) { Text("Delete") } },
            dismissButton = { DroidTextButton(onClick = viewModel::dismissDeleteLocalModel) { Text("Keep") } },
        )
    }
}

@Composable
private fun MessageBubble(line: ChatLine) {
    val scheme = MaterialTheme.colorScheme
    if (line.fromUser) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = scheme.primaryContainer,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                SelectionContainer {
                    Text(line.text, color = scheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(
                color = scheme.surfaceVariant,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 6.dp, bottomEnd = 22.dp),
                border = BorderStroke(1.dp, scheme.outline),
                modifier = Modifier.widthIn(max = 330.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ASSISTANT", color = scheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    // Long-press selects text, so a reply can be copied (there was no way to copy before).
                    SelectionContainer { Text(line.text, color = scheme.onSurface) }
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
    sending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    // Document Q&A: attached files' relevant passages are added to each message while they stay attached.
    // Indexing happens on this device; the passages go to the chat provider only inside later messages.
    if (ToolbarToggle.ATTACH in visibleToggles) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DroidSecondaryButton(onClick = onAttachDocument, enabled = documentsAvailable && !attachingDocument) {
                Text(if (attachingDocument) "Indexing…" else "Attach document")
            }
            if (!documentsAvailable) {
                if (embedderDownloadBytes == null) {
                    DroidSecondaryButton(onClick = onDownloadEmbedder) { Text("Download embedding model ($embedderDownloadMegabytes MB)") }
                } else {
                    DroidSecondaryButton(onClick = onCancelEmbedderDownload) { Text("Cancel (${embedderDownloadBytes / BYTES_PER_MEGABYTE} / $embedderDownloadMegabytes MB)") }
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
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            DroidSecondaryButton(onClick = onSpeakLast, enabled = canSpeak || speaking) { Text(if (speaking) "Stop" else "Speak reply") }
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
            placeholder = { Text("Ask DroidCommand AI…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
        if (ToolbarToggle.MICROPHONE in visibleToggles) {
            DroidIconButton(onClick = onMic, enabled = micEnabled) {
                Icon(
                    if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (listening) "Stop dictation" else "Dictate",
                    tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (sending) {
            DroidIconButton(
                onClick = onStop,
                enabled = true,
                filled = true,
                modifier = Modifier.padding(start = 2.dp).testTag("chat_stop"),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop")
            }
        } else {
            DroidIconButton(
                onClick = onSend,
                enabled = canSend,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.padding(start = 2.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

private const val BYTES_PER_MEGABYTE = 1024L * 1024L
