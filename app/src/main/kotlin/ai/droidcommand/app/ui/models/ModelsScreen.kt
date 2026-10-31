package ai.droidcommand.app.ui.models

import ai.droidcommand.app.localmodels.OpenGgufDocument
import ai.droidcommand.app.localmodels.download.ModelDownloadState
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.BorderDark
import ai.droidcommand.app.ux.FriendlyErrors
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.llm.local.HuggingFaceGgufFile
import ai.droidcommand.llm.local.RamFit
import ai.droidcommand.llm.local.RamFitVerdict
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModelsScreen(viewModel: ModelsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val picker = rememberLauncherForActivityResult(OpenGgufDocument()) { uri ->
        uri?.let(viewModel::importUri)
    }
    val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onReturnedFromSettings()
    }
    val catalogBringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitleWithHelp("Models", ScreenHelpCatalog.MODELS)
        Text(
            "Chat uses the model you pick here, on this phone. No paid download, and no Hugging Face token is required.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CurrentModelCard(state)
        if (state.installed.isEmpty()) {
            ModelsEmptyState(
                importing = state.importing,
                onPick = { picker.launch(Unit) },
                onBrowse = { scope.launch { catalogBringIntoView.bringIntoView() } },
            )
        }
        state.nativeProblem?.let {
            Text(FriendlyErrors.forUser(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        state.status?.let { Text(FriendlyErrors.forUser(it), style = MaterialTheme.typography.bodySmall) }

        SectionLabel("This device")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { picker.launch(Unit) },
                    enabled = !state.importing,
                    modifier = Modifier.fillMaxWidth().testTag("models_pick_gguf"),
                ) {
                    Text(if (state.importing) "Copying…" else "Pick a .gguf from storage")
                }
                Text(
                    "Opens the system file picker (any type; only .gguf files are kept). The app takes a persistable read grant and copies the file into its own storage, or opens it from shared storage when a copy will not fit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.allFilesAccess) {
                    OutlinedButton(onClick = { viewModel.refresh(scan = true) }, modifier = Modifier.fillMaxWidth()) { Text("Scan Download and models folders") }
                } else {
                    OutlinedButton(onClick = viewModel::showAllFilesExplanation, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow all-files access (optional)")
                    }
                }
                state.scanMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                state.scanned.forEach { file ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(file.fileName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull(file.quant, "${file.sizeBytes / MEGABYTE} MB").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { viewModel.importScanned(file.path) }, enabled = !state.importing) { Text("Use") }
                    }
                }
                if (state.installed.isEmpty() && state.scanned.isEmpty()) {
                    Text("No models on this phone yet. Use the buttons above, or the free list below.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.installed.forEach { model ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.label + if (model.id == state.activeId) " (active)" else "", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (model.externalPath != null) "In shared storage" else "Copied into app storage",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (model.id != state.activeId) {
                            OutlinedButton(onClick = { viewModel.select(model.id) }) { Text("Use") }
                        }
                        TextButton(onClick = { viewModel.askDelete(model.id) }) { Text("Remove") }
                    }
                }
            }
        }

        SectionLabel(
            "Browse catalog",
            modifier = Modifier
                .bringIntoViewRequester(catalogBringIntoView)
                .testTag("models_catalog"),
        )
        ChatModelCatalogSection(state, viewModel)

        SectionLabel("Search Hugging Face")
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("GGUF repo search") },
                )
                Button(onClick = viewModel::search, enabled = !state.searching && state.searchQuery.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.searching) "Searching…" else "Search")
                }
                state.searchError?.let { Text(FriendlyErrors.forUser(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                state.searchHits.forEach { hit ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(hit.id, style = MaterialTheme.typography.bodyMedium)
                            Text("${hit.license} · ${hit.downloads} downloads", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { viewModel.openRepo(hit.id) }) { Text("Files") }
                    }
                }
                if (state.hubRepo != null) {
                    Text("Files in ${state.hubRepo}", style = MaterialTheme.typography.titleSmall)
                }
                state.hubFiles.forEach { row ->
                    HubFileCard(row, viewModel)
                }
            }
        }
    }

    if (state.explainAllFiles) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAllFilesExplanation,
            title = { Text("All-files access") },
            text = {
                Text(
                    "Optional. The app can then see .gguf files you already keep in Download, Download/models, Documents/models, or /sdcard/models, and use them without copying. " +
                        "It is off by default. The system Settings screen is the only place this permission can be granted. Import from the file picker still works without it.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.dismissAllFilesExplanation()
                    allFilesLauncher.launch(viewModel.allFilesSettingsIntent())
                }) { Text("Open system settings") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissAllFilesExplanation) { Text("Not now") } },
        )
    }
    state.confirmDeleteId?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("Remove this model?") },
            text = { Text("A copy in app storage is deleted. A file that was used from shared storage is left on disk.") },
            confirmButton = { Button(onClick = viewModel::confirmDelete) { Text("Remove") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChatModelCatalogSection(state: ModelsUiState, viewModel: ModelsViewModel) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Free models you can download. Size is shown so you can see what fits this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.catalog.forEach { row ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.entry.metadata.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${row.entry.quant} · ${((row.entry.metadata.sizeBytes ?: 0L) / MEGABYTE)} MB · ${row.entry.license} · RAM ${RamFit.label(row.fit.verdict)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(row.fit.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DownloadControls(
                        state = row.download,
                        installed = row.installed,
                        tooBig = row.fit.verdict == RamFitVerdict.TOO_BIG,
                        onDownload = { viewModel.downloadCatalog(row.entry.metadata.id) },
                        onCancel = { viewModel.cancelDownload(row.entry.metadata.id) },
                        onUse = { viewModel.select(row.entry.metadata.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HubFileCard(row: HubFileRow, viewModel: ModelsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(row.file.fileName, style = MaterialTheme.typography.bodyMedium)
        Text(
            listOfNotNull(row.file.quant, "${row.file.sizeBytes / MEGABYTE} MB", row.file.sha256?.let { "checksum ${it.take(8)}…" } ?: "no checksum", "RAM ${RamFit.label(row.fit.verdict)}").joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DownloadControls(
            state = row.download,
            installed = row.installed,
            tooBig = row.fit.verdict == RamFitVerdict.TOO_BIG,
            onDownload = { viewModel.downloadHub(row.file) },
            onCancel = { viewModel.cancelDownload(hubCancelId(row.file)) },
            onUse = { row.installedId?.let(viewModel::select) },
        )
    }
}

@Composable
private fun DownloadControls(
    state: ModelDownloadState,
    installed: Boolean,
    tooBig: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
) {
    when {
        installed -> OutlinedButton(onClick = onUse) { Text("Use in chat") }
        tooBig -> Text("Too big for this device's RAM.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        state is ModelDownloadState.Running || state is ModelDownloadState.Queued -> {
            val bytes = (state as? ModelDownloadState.Running)?.bytesSoFar
                ?: (state as? ModelDownloadState.Queued)?.bytesSoFar
                ?: 0L
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("${bytes / MEGABYTE} MB so far", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        state is ModelDownloadState.Failed -> {
            Text(FriendlyErrors.forUser(state.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = onDownload) { Text("Retry") }
        }
        else -> Button(onClick = onDownload) { Text("Download") }
    }
}

@Composable
private fun CurrentModelCard(state: ModelsUiState) {
    val current = state.installed.firstOrNull { it.id == state.activeId }
    DroidCard(
        modifier = Modifier.testTag("models_current"),
        borderColor = if (current != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else BorderDark,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Current model", style = MaterialTheme.typography.titleSmall)
            if (current != null) {
                Text(current.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (current.externalPath != null) "On this phone (shared storage)" else "On this phone (app storage)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("None selected", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pick a file or download one below. Chat cannot answer on-device until you do — unless you add a cloud key in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelsEmptyState(
    importing: Boolean,
    onPick: () -> Unit,
    onBrowse: () -> Unit,
) {
    DroidCard(
        modifier = Modifier.testTag("models_empty"),
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No model yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pick a .gguf you already have, or browse the free catalog on this screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onPick,
                enabled = !importing,
                modifier = Modifier.fillMaxWidth().testTag("models_empty_pick"),
            ) { Text("Pick a .gguf from storage") }
            OutlinedButton(
                onClick = onBrowse,
                modifier = Modifier.fillMaxWidth().testTag("models_empty_browse"),
            ) { Text("Browse catalog") }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B1220)
@Composable
private fun ModelsEmptyPreview() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModelsEmptyState(importing = false, onPick = {}, onBrowse = {})
    }
}

private const val MEGABYTE = 1024L * 1024L

private fun hubCancelId(file: HuggingFaceGgufFile): String =
    "hf-" + (file.sha256 ?: file.fileName).take(16)
