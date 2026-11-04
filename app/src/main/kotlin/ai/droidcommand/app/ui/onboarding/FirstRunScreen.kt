package ai.droidcommand.app.ui.onboarding

import ai.droidcommand.app.localmodels.OpenGgufDocument
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ux.ScreenHelpCatalog
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun FirstRunScreen(
    viewModel: FirstRunViewModel = hiltViewModel(),
    onFinished: (openModels: Boolean) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.finished, state.openModels) {
        if (state.finished) onFinished(state.openModels)
    }
    if (state.finished) return
    val picker = rememberLauncherForActivityResult(OpenGgufDocument()) { uri ->
        uri?.let(viewModel::importUri)
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onPermissionResult(Manifest.permission.RECORD_AUDIO, granted)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onPermissionResult(Manifest.permission.POST_NOTIFICATIONS, granted)
    }
    val allFiles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onReturnedFromSettings()
    }
    FirstRunContent(
        state = state,
        onNext = viewModel::next,
        onBack = viewModel::back,
        onSkip = viewModel::skip,
        onPickModel = { picker.launch(Unit) },
        onBrowseCatalog = viewModel::browseCatalog,
        onAskMic = { mic.launch(Manifest.permission.RECORD_AUDIO) },
        onAskNotifications = {
            if (Build.VERSION.SDK_INT >= 33) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onAskAllFiles = { allFiles.launch(viewModel.allFilesSettingsIntent()) },
    )
}

@Composable
fun FirstRunContent(
    state: FirstRunUiState,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onPickModel: () -> Unit,
    onBrowseCatalog: () -> Unit,
    onAskMic: () -> Unit,
    onAskNotifications: () -> Unit,
    onAskAllFiles: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("first_run"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitleWithHelp("Welcome", ScreenHelpCatalog.FIRST_RUN)
        Text(
            "Step ${state.step.ordinal + 1} of ${FirstRunStep.entries.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state.step) {
            FirstRunStep.WELCOME -> WelcomeStep()
            FirstRunStep.MODEL -> ModelStep(state, onPickModel, onBrowseCatalog)
            FirstRunStep.FLEET -> FleetStep()
            FirstRunStep.PERMISSIONS -> PermissionsStep(state, onAskMic, onAskNotifications, onAskAllFiles)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (state.step != FirstRunStep.WELCOME) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).testTag("first_run_back")) {
                    Text("Back")
                }
            }
            Button(
                onClick = onNext,
                modifier = Modifier.weight(1f).testTag("first_run_next"),
            ) {
                Text(if (state.step == FirstRunStep.PERMISSIONS) "Finish" else "Next")
            }
        }
        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth().testTag("first_run_skip"),
        ) { Text("Skip for now") }
    }
}

@Composable
private fun WelcomeStep() {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This app does two things", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. AI Chat — ask questions. Answers can stay on this phone if you pick a model, or use a cloud key you add yourself.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "2. Fleet — see your other Victorious apps (RootForge, Termux, Flash, and the rest) and connect the ones you trust.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Nothing talks to another app until you pair it. Remote control stays off. Anything that can change the phone still asks you first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelStep(
    state: FirstRunUiState,
    onPickModel: () -> Unit,
    onBrowseCatalog: () -> Unit,
) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pick a model so Chat can answer", style = MaterialTheme.typography.titleMedium)
            Text(
                if (state.hasModel) {
                    "A model is already on this phone. You can pick another one, or continue."
                } else {
                    "A model is the on-phone brain. You can pick a file you already have, or look at the free catalog. You can skip and add one later."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onPickModel,
                enabled = !state.importing,
                modifier = Modifier.fillMaxWidth().testTag("first_run_pick_gguf"),
            ) { Text(if (state.importing) "Copying…" else "Pick a .gguf from storage") }
            OutlinedButton(
                onClick = onBrowseCatalog,
                modifier = Modifier.fillMaxWidth().testTag("first_run_browse_catalog"),
            ) { Text("Browse catalog") }
            state.modelStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FleetStep() {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pair your other apps", style = MaterialTheme.typography.titleMedium)
            Text(
                "Fleet is the list of your other Victorious apps. After setup you will see one card per app: Connected, Installed (tap to pair), Not installed, or Blocked with a fix.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Pairing only means you trust that install. It does not turn on remote control and does not run a command.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionsStep(
    state: FirstRunUiState,
    onAskMic: () -> Unit,
    onAskNotifications: () -> Unit,
    onAskAllFiles: () -> Unit,
) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Optional permissions", style = MaterialTheme.typography.titleMedium)
            Text(
                "None of these are required. Chat and Fleet work without them.",
                style = MaterialTheme.typography.bodyMedium,
            )
            PermissionRow(
                title = "Microphone",
                why = "So you can talk instead of type. Off until you tap Allow.",
                granted = state.micGranted,
                onAsk = onAskMic,
                tag = "first_run_mic",
            )
            PermissionRow(
                title = "Notifications",
                why = "So you can see when a download or voice listen is running. Off until you tap Allow.",
                granted = state.notificationsGranted,
                onAsk = onAskNotifications,
                tag = "first_run_notifications",
            )
            PermissionRow(
                title = "All files (optional)",
                why = "So the app can find models you already downloaded. You can always pick a single file instead. This opens system Settings — it is off by default.",
                granted = state.allFilesGranted,
                onAsk = onAskAllFiles,
                tag = "first_run_all_files",
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    why: String,
    granted: Boolean,
    onAsk: () -> Unit,
    tag: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (granted) {
            Text("Allowed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        } else {
            OutlinedButton(onClick = onAsk, modifier = Modifier.fillMaxWidth().testTag(tag)) {
                Text("Allow")
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B1220)
@Composable
private fun FirstRunWelcomePreview() {
    FirstRunContent(
        state = FirstRunUiState(),
        onNext = {},
        onBack = {},
        onSkip = {},
        onPickModel = {},
        onBrowseCatalog = {},
        onAskMic = {},
        onAskNotifications = {},
        onAskAllFiles = {},
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B1220)
@Composable
private fun FirstRunModelPreview() {
    FirstRunContent(
        state = FirstRunUiState(step = FirstRunStep.MODEL),
        onNext = {},
        onBack = {},
        onSkip = {},
        onPickModel = {},
        onBrowseCatalog = {},
        onAskMic = {},
        onAskNotifications = {},
        onAskAllFiles = {},
    )
}
