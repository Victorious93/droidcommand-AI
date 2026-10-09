package ai.droidcommand.app.ui.chat

import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import ai.droidcommand.app.ui.theme.AccentCyan
import ai.droidcommand.app.ui.theme.AccentGreenButton
import ai.droidcommand.app.ui.theme.AccentNeonGreen
import ai.droidcommand.app.ui.theme.AccentPurple
import ai.droidcommand.app.ui.theme.BorderDark
import ai.droidcommand.app.ui.theme.CardDark
import ai.droidcommand.app.ui.theme.OnAccentDark
import ai.droidcommand.app.ui.theme.TextPrimary
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.app.ui.theme.UserBubbleDark
import ai.droidcommand.llm.factory.CloudProviderCatalog
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Chat in OpenDroid's layout: neon header with a status line, purple right-aligned user bubbles,
 * bordered left-aligned reply cards with a cyan role label, and a pill input with a round send
 * button. Provider/model/memory controls live in a panel that opens from the header subtitle so
 * the default view stays as clean as OpenDroid's.
 *
 * Never built or run on a device — written without an Android SDK. All chat
 * logic is unchanged and lives in the JVM-tested [ai.droidcommand.llm.factory.ChatSession]; the
 * model field is still free text because model ids change and no list here was verified.
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var optionsOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val rows = state.lines + if (state.streaming.isNotEmpty()) listOf(ChatLine(false, state.streaming)) else emptyList()

    LaunchedEffect(rows.size, state.streaming) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header(
            subtitle = if (state.sending) "Thinking…" else "${state.provider.label} · ${state.model}",
            active = !state.sending,
            optionsOpen = optionsOpen,
            onToggleOptions = { optionsOpen = !optionsOpen },
            onNewChat = viewModel::newChat,
            newChatEnabled = !state.sending,
        )

        if (optionsOpen) {
            OptionsPanel(state, viewModel)
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                Text(
                    "Ask anything. Turn on Memory in the options above to let it recall past chats.",
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

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }

        state.notice?.let {
            Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }

        InputBar(
            webSearchEnabled = state.webSearchEnabled,
            onToggleWebSearch = { viewModel.setWebSearchEnabled(!state.webSearchEnabled) },
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

@Composable
private fun Header(
    subtitle: String,
    active: Boolean,
    optionsOpen: Boolean,
    onToggleOptions: () -> Unit,
    onNewChat: () -> Unit,
    newChatEnabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ScreenTitle("DroidCommand")
            Text(
                text = subtitle + if (optionsOpen) "  ▴" else "  ▾",
                color = if (active) AccentNeonGreen else AccentCyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.clickable(onClick = onToggleOptions).padding(vertical = 4.dp),
            )
        }
        TextButton(onClick = onNewChat, enabled = newChatEnabled) {
            Text("New chat", color = TextSecondary)
        }
    }
}

@Composable
private fun OptionsPanel(state: ChatUiState, viewModel: ChatViewModel) {
    var menuOpen by remember { mutableStateOf(false) }
    DroidCard(modifier = Modifier.padding(bottom = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { menuOpen = true }) { Text(state.provider.label) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    CloudProviderCatalog.all.forEach { spec ->
                        DropdownMenuItem(text = { Text(spec.label) }, onClick = {
                            viewModel.selectProvider(spec)
                            menuOpen = false
                        })
                    }
                }
            }
            OutlinedTextField(
                value = state.model,
                onValueChange = viewModel::setModel,
                label = { Text("Model (suggested default — edit if it is outdated)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = state.memoryEnabled, onCheckedChange = viewModel::setMemoryEnabled)
                Text("Memory (applies next time you open Chat)", style = MaterialTheme.typography.bodySmall)
            }
            if (state.memoryEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::rememberChat, enabled = !state.sending) { Text("Remember this chat") }
                    state.memoryStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(line: ChatLine) {
    if (line.fromUser) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = UserBubbleDark,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 6.dp),
                border = BorderStroke(1.dp, AccentPurple),
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
    webSearchEnabled: Boolean,
    onToggleWebSearch: () -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
) {
    // Toolbar row above the input: the web-search toggle. When on, each message's text is sent to the
    // configured search provider (Brave/SerpAPI) before the model call — hence the explicit label.
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        FilterChip(
            selected = webSearchEnabled,
            onClick = onToggleWebSearch,
            label = { Text(if (webSearchEnabled) "Web search: on" else "Web search: off") },
        )
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
                focusedBorderColor = AccentGreenButton,
                unfocusedBorderColor = BorderDark,
                cursorColor = AccentNeonGreen,
            ),
        )
        IconButton(
            onClick = onSend,
            enabled = canSend,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = AccentGreenButton,
                contentColor = OnAccentDark,
                disabledContainerColor = CardDark,
                disabledContentColor = TextSecondary,
            ),
            modifier = Modifier.padding(start = 2.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}
