package ai.droidcommand.app.ui.models

import ai.droidcommand.app.localmodels.OpenGgufDocument
import ai.droidcommand.app.localmodels.download.ModelDownloadState
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.DroidEmptyState
import ai.droidcommand.app.ui.components.DroidGroup
import ai.droidcommand.app.ui.components.DroidListRow
import ai.droidcommand.app.ui.components.DroidNote
import ai.droidcommand.app.ui.components.DroidRowDivider
import ai.droidcommand.app.ui.components.DroidTabs
import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.app.ui.components.ScreenTitleWithHelp
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.components.StatusPill
import ai.droidcommand.app.ui.theme.BorderDark
import ai.droidcommand.app.ui.theme.DroidIconButton
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import ai.droidcommand.app.ux.FriendlyErrors
import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.llm.local.HuggingFaceRepoHit
import ai.droidcommand.llm.local.RamFitAssessment
import ai.droidcommand.llm.local.RamFitVerdict
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Where a model comes from, as three tabs instead of one long scroll: what is already on this phone, the curated
 * catalog, and a Hugging Face search. The title, the current model and the tabs stay put; only the list scrolls.
 */
@Composable
fun ModelsScreen(viewModel: ModelsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(ModelsTab.PHONE) }
    var hideTooBig by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val picker = rememberLauncherForActivityResult(OpenGgufDocument()) { uri ->
        uri?.let(viewModel::importUri)
    }
    val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onReturnedFromSettings()
    }
    // Each tab starts at its top, instead of inheriting the scroll position of the one before it.
    LaunchedEffect(tab) { scroll.scrollTo(0) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)) {
            ScreenTitleWithHelp("Models", ScreenHelpCatalog.MODELS)
            CurrentModelCard(state)
            DroidTabs(
                labels = ModelsTab.entries.map { it.label },
                selectedIndex = tab.ordinal,
                onSelect = { tab = ModelsTab.entries[it] },
            )
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.nativeProblem?.let { DroidNote(FriendlyErrors.forUser(it), tone = PillTone.Error) }
            state.status?.let { DroidNote(FriendlyErrors.forUser(it), onDismiss = viewModel::dismissStatus) }
            when (tab) {
                ModelsTab.PHONE -> PhoneTab(state, viewModel, onPick = { picker.launch(Unit) }, onBrowse = { tab = ModelsTab.CATALOG })
                ModelsTab.CATALOG -> CatalogTab(state, viewModel, hideTooBig = hideTooBig, onHideTooBig = { hideTooBig = it })
                ModelsTab.SEARCH -> SearchTab(state, viewModel)
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
                DroidPrimaryButton(onClick = {
                    viewModel.dismissAllFilesExplanation()
                    allFilesLauncher.launch(viewModel.allFilesSettingsIntent())
                }) { Text("Open system settings") }
            },
            dismissButton = { DroidTextButton(onClick = viewModel::dismissAllFilesExplanation) { Text("Not now") } },
        )
    }
    state.confirmDeleteId?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("Remove this model?") },
            text = { Text("A copy in app storage is deleted. A file that was used from shared storage is left on disk.") },
            confirmButton = { DroidPrimaryButton(onClick = viewModel::confirmDelete) { Text("Remove") } },
            dismissButton = { DroidTextButton(onClick = viewModel::dismissDelete) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CurrentModelCard(state: ModelsUiState) {
    val current = state.installed.firstOrNull { it.id == state.activeId }
    DroidCard(
        modifier = Modifier.testTag("models_current"),
        borderColor = if (current != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else BorderDark,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Current model", style = MaterialTheme.typography.titleSmall)
                Text(current?.label ?: "No model selected", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        current == null -> "Chat can't answer on this phone until you add one, unless a cloud key is set in Settings."
                        current.externalPath != null -> "Opened from shared storage"
                        else -> "Stored in the app"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (current != null) StatusPill("Active", PillTone.Success) else StatusPill("None", PillTone.Warning)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// On this phone

@Composable
private fun PhoneTab(state: ModelsUiState, viewModel: ModelsViewModel, onPick: () -> Unit, onBrowse: () -> Unit) {
    if (state.importing) {
        DroidCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Copying the model onto this phone…", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                DroidSecondaryButton(onClick = viewModel::cancelImport, modifier = Modifier.testTag("models_cancel_import")) { Text("Cancel import") }
            }
        }
    }
    if (state.installed.isEmpty()) {
        DroidCard(modifier = Modifier.testTag("models_empty"), borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) {
            DroidEmptyState(
                icon = Icons.Filled.Storage,
                title = "No model on this phone yet",
                body = "Use a .gguf file you already have, or download a free one from the catalog.",
            ) {
                DroidPrimaryButton(
                    onClick = onPick,
                    enabled = !state.importing,
                    modifier = Modifier.fillMaxWidth().testTag("models_empty_pick"),
                ) { Text(if (state.importing) "Copying…" else "Pick a .gguf file") }
                DroidSecondaryButton(
                    onClick = onBrowse,
                    modifier = Modifier.fillMaxWidth().testTag("models_empty_browse"),
                ) { Text("Browse the catalog") }
            }
        }
    } else {
        SectionLabel("On this phone")
        DroidGroup {
            state.installed.forEachIndexed { index, model ->
                if (index > 0) DroidRowDivider()
                val active = model.id == state.activeId
                DroidListRow(
                    title = model.label,
                    subtitle = if (model.externalPath != null) "In shared storage" else "In app storage",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (active) {
                                StatusPill("Active", PillTone.Success)
                            } else {
                                DroidSecondaryButton(onClick = { viewModel.select(model.id) }) { Text("Use") }
                            }
                            DroidIconButton(onClick = { viewModel.askDelete(model.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove ${model.label}")
                            }
                        }
                    },
                )
            }
        }
    }

    SectionLabel(if (state.installed.isEmpty()) "Other ways to find one" else "Add a model")
    DroidGroup {
        // The empty state above already carries the Pick button, so it is not offered a second time here.
        if (state.installed.isNotEmpty()) {
            DroidListRow(
                title = if (state.importing) "Copying…" else "Pick a .gguf file",
                subtitle = "Copied into the app, or opened where it is if there is no room to copy.",
                icon = Icons.Filled.FileOpen,
                onClick = if (state.importing) null else onPick,
                modifier = Modifier.testTag("models_pick_gguf"),
            )
            DroidRowDivider()
        }
        if (state.allFilesAccess) {
            DroidListRow(
                title = "Scan Download and models folders",
                subtitle = state.scanMessage ?: "Looks for .gguf files you already keep on this phone.",
                icon = Icons.Filled.FolderOpen,
                onClick = { viewModel.refresh(scan = true) },
            )
        } else {
            DroidListRow(
                title = "Find models already on this phone",
                subtitle = "Optional. Needs all-files access so the app can see Download and models folders.",
                icon = Icons.Filled.FolderOpen,
                onClick = viewModel::showAllFilesExplanation,
            )
        }
    }
    if (state.scanned.isNotEmpty()) {
        SectionLabel("Found in shared storage")
        DroidGroup {
            state.scanned.forEachIndexed { index, file ->
                if (index > 0) DroidRowDivider()
                DroidListRow(
                    title = file.fileName,
                    subtitle = listOfNotNull(file.quant, ModelsPresentation.sizeLabel(file.sizeBytes)).joinToString(" · "),
                    trailing = {
                        DroidSecondaryButton(onClick = { viewModel.importScanned(file.path) }, enabled = !state.importing) { Text("Use") }
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Catalog

@Composable
private fun CatalogTab(state: ModelsUiState, viewModel: ModelsViewModel, hideTooBig: Boolean, onHideTooBig: (Boolean) -> Unit) {
    val total = state.ramTotalBytes
    val free = state.ramFreeBytes
    Column(modifier = Modifier.testTag("models_catalog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DroidNote(
            if (total != null && free != null) {
                "This phone has ${ModelsPresentation.sizeLabel(total)} of RAM, about ${ModelsPresentation.sizeLabel(free)} free right now. " +
                    "A model marked tight may be closed by Android while it runs."
            } else {
                "Free models. The size shown is the download; running one needs more RAM than that."
            },
        )
        FilterChip(
            selected = hideTooBig,
            onClick = { onHideTooBig(!hideTooBig) },
            label = { Text("Hide ones that are too big") },
        )
        val rows = ModelsPresentation.orderedByFit(
            rows = state.catalog.filter { !hideTooBig || it.fit.verdict != RamFitVerdict.TOO_BIG },
            verdict = { it.fit.verdict },
            sizeBytes = { it.entry.metadata.sizeBytes ?: 0L },
        )
        if (rows.isEmpty()) {
            DroidEmptyState(
                icon = Icons.Filled.Storage,
                title = if (state.catalog.isEmpty()) "Loading the catalog…" else "Nothing here fits this phone",
                body = if (state.catalog.isEmpty()) "One moment." else "Turn off the filter to see every model, or search Hugging Face for a smaller one.",
            )
        }
        rows.forEach { row ->
            val size = row.entry.metadata.sizeBytes ?: 0L
            ModelCard(
                title = row.entry.metadata.displayName,
                details = listOf(row.entry.quant, ModelsPresentation.sizeLabel(size), row.entry.license).joinToString(" · "),
                fit = row.fit,
                tags = emptyList(),
                installed = row.installed,
                active = state.activeId == row.entry.metadata.id,
                download = row.download,
                totalBytes = size.takeIf { it > 0L },
                blockedReason = null,
                onDownload = { viewModel.downloadCatalog(row.entry.metadata.id) },
                onStop = { viewModel.stopDownload(row.entry.metadata.id) },
                onCancel = { viewModel.cancelDownload(row.entry.metadata.id) },
                onUse = { viewModel.select(row.entry.metadata.id) },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Search

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchTab(state: ModelsUiState, viewModel: ModelsViewModel) {
    if (state.hubRepo != null) {
        FilesView(state, viewModel, repo = state.hubRepo)
        return
    }
    val focus = LocalFocusManager.current
    val canSearch = !state.searching && ModelsPresentation.searchable(state.searchQuery) != null
    OutlinedTextField(
        value = state.searchQuery,
        onValueChange = viewModel::setSearchQuery,
        modifier = Modifier.fillMaxWidth().testTag("models_search_field"),
        singleLine = true,
        label = { Text("Search Hugging Face") },
        placeholder = { Text("for example: qwen") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (state.searchQuery.isNotEmpty()) {
                IconButton(onClick = viewModel::clearSearch) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { runSearch(focus, viewModel, canSearch) }),
    )
    DroidPrimaryButton(
        onClick = { runSearch(focus, viewModel, canSearch) },
        enabled = canSearch,
        modifier = Modifier.fillMaxWidth().testTag("models_search_button"),
    ) { Text(if (state.searching) "Searching…" else "Search") }

    val error = state.searchError
    val searched = state.searchedQuery
    when {
        state.searching -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Looking for GGUF models…", style = MaterialTheme.typography.bodyMedium)
        }
        error != null -> DroidNote(FriendlyErrors.forUser(error), tone = PillTone.Error)
        searched != null && state.searchHits.isEmpty() -> DroidEmptyState(
            icon = Icons.Filled.Search,
            title = "No match for “$searched”",
            body = "Only models under a free license are listed, and Llama and Gemma are not. Try a shorter or different name.",
        )
        state.searchHits.isNotEmpty() -> {
            SectionLabel("${state.searchHits.size} result${if (state.searchHits.size == 1) "" else "s"} for “${searched ?: state.searchQuery.trim()}”")
            state.searchHits.forEach { hit -> RepoCard(hit, onOpen = { viewModel.openRepo(hit.id) }) }
        }
        else -> {
            DroidNote(
                "Only models under a free license (Apache-2.0, MIT and similar) are listed, and a file needs a published checksum so the app can verify it. " +
                    "Llama and Gemma use other licenses, so they will not appear here.",
            )
            SectionLabel("Try")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelsPresentation.SEARCH_SUGGESTIONS.forEach { term ->
                    SuggestionChip(
                        onClick = {
                            focus.clearFocus()
                            viewModel.searchFor(term)
                        },
                        label = { Text(term) },
                    )
                }
            }
        }
    }
}

private fun runSearch(focus: FocusManager, viewModel: ModelsViewModel, canSearch: Boolean) {
    focus.clearFocus()
    if (canSearch) viewModel.search()
}

@Composable
private fun RepoCard(hit: HuggingFaceRepoHit, onOpen: () -> Unit) {
    val (owner, name) = ModelsPresentation.splitRepoId(hit.id)
    DroidCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                owner?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    hit.license?.let { StatusPill(it) }
                    StatusPill("${ModelsPresentation.countLabel(hit.downloads)} downloads")
                }
            }
            Text("Files", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun FilesView(state: ModelsUiState, viewModel: ModelsViewModel, repo: String) {
    val (owner, name) = ModelsPresentation.splitRepoId(repo)
    DroidTextButton(onClick = viewModel::closeRepo) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Back to results")
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        owner?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    val error = state.searchError
    when {
        error != null -> DroidNote(FriendlyErrors.forUser(error), tone = PillTone.Error)
        state.searching && state.hubFiles.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Loading the file list…", style = MaterialTheme.typography.bodyMedium)
        }
        state.hubFiles.isEmpty() -> DroidEmptyState(
            icon = Icons.Filled.Storage,
            title = "No GGUF files here",
            body = "This repo does not publish a file the app can run. Go back and pick another.",
        )
        else -> {
            DroidNote("Each file is the same model at a different size and quality. Pick one that fits this phone.")
            ModelsPresentation.orderedByFit(state.hubFiles, verdict = { it.fit.verdict }, sizeBytes = { it.file.sizeBytes }).forEach { row ->
                val tags = buildList {
                    if (ModelsPresentation.isCommonPick(row.file.quant)) add("Common pick" to PillTone.Neutral)
                    if (row.file.sha256 == null) add("No checksum" to PillTone.Warning)
                }
                ModelCard(
                    title = row.file.fileName,
                    details = listOfNotNull(row.file.quant, ModelsPresentation.sizeLabel(row.file.sizeBytes)).joinToString(" · "),
                    fit = row.fit,
                    tags = tags,
                    installed = row.installed,
                    active = row.installedId != null && row.installedId == state.activeId,
                    download = row.download,
                    totalBytes = row.file.sizeBytes.takeIf { it > 0L },
                    blockedReason = if (row.file.sha256 == null) {
                        "Hugging Face does not publish a checksum for this file, so the app cannot verify a download. Import it from storage instead."
                    } else {
                        null
                    },
                    onDownload = { viewModel.downloadHub(row.file) },
                    onStop = { viewModel.stopDownload(hubCancelId(row.file)) },
                    onCancel = { viewModel.cancelDownload(hubCancelId(row.file)) },
                    onUse = { row.installedId?.let(viewModel::select) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// One model, wherever it came from

/**
 * Name and size on the left, whether it fits this phone on the right, then the one action that makes sense now.
 * [blockedReason] is why a download is not offered at all, when that is the case.
 */
@Composable
private fun ModelCard(
    title: String,
    details: String,
    fit: RamFitAssessment,
    tags: List<Pair<String, PillTone>>,
    installed: Boolean,
    active: Boolean,
    download: ModelDownloadState,
    totalBytes: Long?,
    blockedReason: String?,
    onDownload: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
) {
    DroidCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(ModelsPresentation.fitText(fit.verdict), ModelsPresentation.fitTone(fit.verdict))
            }
            if (tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { (text, tone) -> StatusPill(text, tone) } }
            }
            if (fit.verdict != RamFitVerdict.FITS) {
                Text(
                    fit.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fit.verdict == RamFitVerdict.TOO_BIG) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DownloadControls(
                state = download,
                installed = installed,
                active = active,
                tooBig = fit.verdict == RamFitVerdict.TOO_BIG,
                blockedReason = blockedReason,
                totalBytes = totalBytes,
                onDownload = onDownload,
                onStop = onStop,
                onCancel = onCancel,
                onUse = onUse,
            )
        }
    }
}

@Composable
private fun DownloadControls(
    state: ModelDownloadState,
    installed: Boolean,
    active: Boolean,
    tooBig: Boolean,
    blockedReason: String?,
    totalBytes: Long?,
    onDownload: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
) {
    when {
        installed -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill("On this phone", PillTone.Success)
            if (active) {
                Text("Chat uses this one", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                DroidSecondaryButton(onClick = onUse) { Text("Use in chat") }
            }
        }
        // The pill and the reason above already say it; offering a button that can only refuse would be noise.
        tooBig -> Unit
        state is ModelDownloadState.Running || state is ModelDownloadState.Queued -> {
            val bytes = (state as? ModelDownloadState.Running)?.bytesSoFar
                ?: (state as? ModelDownloadState.Queued)?.bytesSoFar
                ?: 0L
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (totalBytes != null) {
                    LinearProgressIndicator(progress = { (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ModelsPresentation.sizeLabel(bytes) + (totalBytes?.let { " of ${ModelsPresentation.sizeLabel(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DroidTextButton(onClick = onStop, modifier = Modifier.testTag("model_download_stop")) { Text("Stop") }
                        DroidTextButton(onClick = onCancel, modifier = Modifier.testTag("model_download_cancel")) { Text("Cancel") }
                    }
                }
            }
        }
        // Stopped: what was downloaded is still on the phone. Download continues from it; Cancel deletes it.
        state is ModelDownloadState.Stopped -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (totalBytes != null) {
                LinearProgressIndicator(progress = { (state.bytesSoFar.toFloat() / totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                "Stopped at " + ModelsPresentation.sizeLabel(state.bytesSoFar) + (totalBytes?.let { " of ${ModelsPresentation.sizeLabel(it)}" } ?: "") +
                    ". Resume continues from here; Cancel deletes what was downloaded.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                DroidTextButton(onClick = onCancel, modifier = Modifier.testTag("model_download_cancel")) { Text("Cancel") }
                DroidPrimaryButton(onClick = onDownload, modifier = Modifier.testTag("model_download_resume")) { Text("Resume") }
            }
        }
        state is ModelDownloadState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(FriendlyErrors.forUser(state.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DroidPrimaryButton(onClick = onDownload) { Text("Retry") }
            }
        }
        blockedReason != null -> Text(blockedReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DroidPrimaryButton(onClick = onDownload) { Text("Download") }
        }
    }
}

private fun hubCancelId(file: ai.droidcommand.llm.local.HuggingFaceGgufFile): String =
    "hf-" + (file.sha256 ?: file.fileName).take(16)
