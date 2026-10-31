package ai.droidcommand.app.ui.models

import ai.droidcommand.app.localmodels.ImportOutcome
import ai.droidcommand.app.localmodels.ImportedModel
import ai.droidcommand.app.localmodels.LocalModelRuntime
import ai.droidcommand.app.localmodels.download.EnqueueResult
import ai.droidcommand.app.localmodels.download.ModelDownloadScheduler
import ai.droidcommand.app.localmodels.download.ModelDownloadState
import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.llm.local.ChatModelCatalog
import ai.droidcommand.llm.local.ChatModelCatalogEntry
import ai.droidcommand.llm.local.GgufFileName
import ai.droidcommand.llm.local.HttpsHuggingFaceHttp
import ai.droidcommand.llm.local.HuggingFaceFilesResult
import ai.droidcommand.llm.local.HuggingFaceGgufClient
import ai.droidcommand.llm.local.HuggingFaceGgufFile
import ai.droidcommand.llm.local.HuggingFaceRepoHit
import ai.droidcommand.llm.local.HuggingFaceSearchResult
import ai.droidcommand.llm.local.RamFit
import ai.droidcommand.llm.local.RamFitAssessment
import ai.droidcommand.llm.local.RamFitVerdict
import ai.droidcommand.llm.local.ScannedGguf
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.system.Os
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class CatalogRow(
    val entry: ChatModelCatalogEntry,
    val fit: RamFitAssessment,
    val installed: Boolean,
    val download: ModelDownloadState,
)

data class HubFileRow(
    val file: HuggingFaceGgufFile,
    val fit: RamFitAssessment,
    val installed: Boolean,
    val installedId: String? = null,
    val download: ModelDownloadState = ModelDownloadState.Idle,
)

data class ModelsUiState(
    val installed: List<ImportedModel> = emptyList(),
    val activeId: String? = null,
    val catalog: List<CatalogRow> = emptyList(),
    val scanned: List<ScannedGguf> = emptyList(),
    val scanMessage: String? = null,
    val nativeProblem: String? = null,
    val status: String? = null,
    val importing: Boolean = false,
    val searching: Boolean = false,
    val searchQuery: String = "",
    val searchHits: List<HuggingFaceRepoHit> = emptyList(),
    val searchError: String? = null,
    val hubFiles: List<HubFileRow> = emptyList(),
    val hubRepo: String? = null,
    val allFilesAccess: Boolean = false,
    val explainAllFiles: Boolean = false,
    val confirmDeleteId: String? = null,
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: LocalModelRuntime,
    private val downloads: ModelDownloadScheduler,
    private val vault: EncryptedSecretsVault,
) : ViewModel() {
    private val prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
    private val hub = HuggingFaceGgufClient(HttpsHuggingFaceHttp()) {
        vault.getSecret(HuggingFaceGgufClient.SECRET_ID)?.takeIf { it.isNotBlank() }
    }
    private val mutableState = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = mutableState.asStateFlow()
    private val downloadJobs = mutableMapOf<String, Job>()

    init {
        refresh(scan = true)
        ChatModelCatalog.entries.forEach { watchDownload(it.id) }
    }

    fun refresh(scan: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val installed = runtime.list()
            val active = prefs.getString(KEY_LOCAL_MODEL, null)?.takeIf { id -> installed.any { it.id == id } }
            val snap = runtime.resources.memory?.snapshot()
            val previousDownloads = mutableState.value.catalog.associate { it.entry.metadata.id to it.download }
            val catalog = ChatModelCatalog.all.map { entry ->
                val size = entry.metadata.sizeBytes ?: 0L
                CatalogRow(
                    entry = entry,
                    fit = ram(size, entry.metadata.contextTokens, snap),
                    installed = installed.any { it.id == entry.metadata.id },
                    download = previousDownloads[entry.metadata.id] ?: ModelDownloadState.Idle,
                )
            }
            val native = runtime.nativeLibraryProblem()
            val allFiles = hasAllFilesAccess()
            val scanned = if (scan && allFiles) runtime.scanStorage() else emptyList()
            mutableState.update {
                it.copy(
                    installed = installed,
                    activeId = active,
                    catalog = catalog,
                    scanned = scanned,
                    scanMessage = scanStatus(allFiles, scanned.size),
                    nativeProblem = native,
                    allFilesAccess = allFiles,
                )
            }
            catalog.forEach { watchDownload(it.entry.metadata.id) }
        }
    }

    fun setSearchQuery(query: String) = mutableState.update { it.copy(searchQuery = query) }

    fun search() {
        val q = mutableState.value.searchQuery
        mutableState.update { it.copy(searching = true, searchError = null, hubFiles = emptyList(), hubRepo = null) }
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = hub.search(q)) {
                is HuggingFaceSearchResult.Repos -> mutableState.update {
                    it.copy(searching = false, searchHits = result.hits, searchError = if (result.hits.isEmpty()) "No free GGUF repos matched." else null)
                }
                is HuggingFaceSearchResult.Failed -> mutableState.update {
                    it.copy(searching = false, searchHits = emptyList(), searchError = result.reason)
                }
            }
        }
    }

    fun openRepo(repoId: String) {
        mutableState.update { it.copy(searching = true, searchError = null, hubRepo = repoId, hubFiles = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            val snap = runtime.resources.memory?.snapshot()
            val installed = runtime.list()
            when (val result = hub.listGgufFiles(repoId)) {
                is HuggingFaceFilesResult.Files -> mutableState.update {
                    it.copy(
                        searching = false,
                        hubFiles = result.files.map { file ->
                            val match = installed.firstOrNull { model -> model.metadata.sha256 == file.sha256 }
                            HubFileRow(
                                file = file,
                                fit = ram(file.sizeBytes, DEFAULT_CONTEXT, snap),
                                installed = match != null,
                                installedId = match?.id,
                            )
                        },
                    )
                }
                is HuggingFaceFilesResult.Failed -> mutableState.update {
                    it.copy(searching = false, hubFiles = emptyList(), searchError = result.reason)
                }
            }
        }
    }

    fun downloadCatalog(id: String) {
        enqueue(ChatModelCatalog.find(id) ?: return)
    }

    fun downloadHub(file: HuggingFaceGgufFile) {
        val meta = file.toMetadata(
            id = hubId(file),
            displayName = GgufFileName.displayLabel(file.fileName),
            contextTokens = DEFAULT_CONTEXT,
        )
        if (meta == null) {
            mutableState.update { it.copy(status = "Hugging Face did not publish a SHA-256 for this file, so it cannot be downloaded here. Import it from storage instead.") }
            return
        }
        enqueue(meta)
    }

    fun cancelDownload(id: String) = downloads.cancel(id)

    fun importUri(uri: Uri) {
        if (mutableState.value.importing) return
        mutableState.update { it.copy(importing = true, status = "Importing…") }
        viewModelScope.launch(Dispatchers.IO) {
            takePersistable(uri)
            val name = GgufFileName.resolve(displayName(uri), uri.lastPathSegment, uri.toString())
                ?: (displayName(uri) ?: "model.gguf")
            val size = sizeOf(uri)
            val outcome = copyOrOpen(uri, name, size)
            applyImport(outcome)
        }
    }

    fun importScanned(path: String) {
        if (mutableState.value.importing) return
        mutableState.update { it.copy(importing = true, status = "Importing…") }
        viewModelScope.launch(Dispatchers.IO) {
            applyImport(runtime.importExisting(File(path)))
        }
    }

    fun select(id: String) {
        prefs.edit { putString(KEY_LOCAL_MODEL, id) }
        mutableState.update { it.copy(activeId = id, status = "Selected. Chat will run this model on the device.") }
    }

    fun askDelete(id: String) = mutableState.update { it.copy(confirmDeleteId = id) }

    fun dismissDelete() = mutableState.update { it.copy(confirmDeleteId = null) }

    fun confirmDelete() {
        val id = mutableState.value.confirmDeleteId ?: return
        mutableState.update { it.copy(confirmDeleteId = null, status = "Deleting…") }
        viewModelScope.launch(Dispatchers.IO) {
            runtime.remove(id)
            if (prefs.getString(KEY_LOCAL_MODEL, null) == id) prefs.edit { remove(KEY_LOCAL_MODEL) }
            refresh(scan = false)
            mutableState.update { it.copy(status = "Removed from the app list.") }
        }
    }

    fun showAllFilesExplanation() = mutableState.update { it.copy(explainAllFiles = true) }

    fun dismissAllFilesExplanation() = mutableState.update { it.copy(explainAllFiles = false) }

    fun allFilesSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }

    fun onReturnedFromSettings() = refresh(scan = true)

    private fun enqueue(metadata: ai.droidcommand.llm.local.ModelMetadata) {
        val snap = runtime.resources.memory?.snapshot()
        val size = metadata.sizeBytes
        if (size != null && snap != null) {
            val fit = RamFit.assess(size, metadata.contextTokens, snap.totalBytes, snap.availableBytes)
            if (fit.verdict == RamFitVerdict.TOO_BIG) {
                mutableState.update { it.copy(status = fit.reason) }
                return
            }
        }
        when (val result = downloads.enqueue(metadata)) {
            EnqueueResult.Enqueued -> {
                mutableState.update { it.copy(status = "Downloading ${metadata.displayName}…") }
                watchDownload(metadata.id)
            }
            is EnqueueResult.Refused -> mutableState.update { it.copy(status = result.reason) }
        }
    }

    private fun watchDownload(id: String) {
        if (downloadJobs.containsKey(id)) return
        downloadJobs[id] = viewModelScope.launch {
            downloads.observe(id).collect { state ->
                val finished = state is ModelDownloadState.Installed
                mutableState.update { ui ->
                    ui.copy(
                        catalog = ui.catalog.map { row ->
                            if (row.entry.metadata.id == id) {
                                row.copy(download = state, installed = finished || row.installed)
                            } else {
                                row
                            }
                        },
                        hubFiles = ui.hubFiles.map { row ->
                            if (hubId(row.file) == id) {
                                row.copy(download = state, installed = finished || row.installed, installedId = row.installedId ?: id.takeIf { finished })
                            } else {
                                row
                            }
                        },
                        status = when (state) {
                            ModelDownloadState.Installed -> "Downloaded and verified."
                            is ModelDownloadState.Failed -> state.reason
                            else -> ui.status
                        },
                    )
                }
                if (finished) refresh(scan = false)
            }
        }
    }

    private fun applyImport(outcome: ImportOutcome) {
        val models = runtime.list()
        when (outcome) {
            is ImportOutcome.Imported -> {
                prefs.edit { putString(KEY_LOCAL_MODEL, outcome.model.id) }
                val suffix = ramSuffix(outcome.model.metadata.sizeBytes, outcome.model.metadata.contextTokens)
                mutableState.update {
                    it.copy(importing = false, installed = models, activeId = outcome.model.id, status = "Imported ${outcome.model.label}. Chat now runs on this device.$suffix")
                }
            }
            is ImportOutcome.Duplicate -> {
                prefs.edit { putString(KEY_LOCAL_MODEL, outcome.id) }
                mutableState.update {
                    it.copy(importing = false, installed = models, activeId = outcome.id, status = "That file is already imported. Selected it.")
                }
            }
            is ImportOutcome.Rejected -> mutableState.update { it.copy(importing = false, status = outcome.reason) }
            is ImportOutcome.Failed -> mutableState.update { it.copy(importing = false, status = outcome.reason) }
        }
        refresh(scan = false)
    }

    private fun copyOrOpen(uri: Uri, name: String, size: Long?): ImportOutcome {
        val stream = context.contentResolver.openInputStream(uri)
            ?: return ImportOutcome.Failed("The file could not be opened.")
        val copied = stream.use { runtime.importModel(name, it, size) { } }
        if (copied !is ImportOutcome.Failed || !copied.reason.contains("Not enough free storage")) return copied
        val path = realPathFromFd(uri)
        if (path != null) return runtime.importExisting(File(path))
        return ImportOutcome.Failed(
            copied.reason + " Grant all-files access in Settings if the model should stay in shared storage instead of being copied.",
        )
    }

    private fun realPathFromFd(uri: Uri): String? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val linked = Os.readlink("/proc/self/fd/${pfd.fd}")
            linked.takeIf { it.startsWith("/") && File(it).canRead() }
        }
    }.getOrNull()

    private fun takePersistable(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers grant only a one-shot URI; the copy below still runs in this callback.
        }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun sizeOf(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private fun ramSuffix(sizeBytes: Long?, contextTokens: Int): String {
        val size = sizeBytes ?: return ""
        val snap = runtime.resources.memory?.snapshot() ?: return ""
        val fit = RamFit.assess(size, contextTokens, snap.totalBytes, snap.availableBytes)
        return if (fit.verdict == RamFitVerdict.TOO_BIG || fit.verdict == RamFitVerdict.TIGHT) " ${fit.reason}" else ""
    }

    private fun ram(size: Long, contextTokens: Int, snap: ai.droidcommand.llm.local.MemorySnapshot?): RamFitAssessment {
        val total = snap?.totalBytes ?: deviceTotal()
        val available = snap?.availableBytes ?: deviceAvailable()
        return RamFit.assess(size, contextTokens, total, available)
    }

    private fun deviceTotal(): Long {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem
    }

    private fun deviceAvailable(): Long {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.availMem
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE.let {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }

    private fun scanStatus(allFiles: Boolean, count: Int): String = when {
        allFiles && count == 0 -> "No .gguf files in Download, Download/models, Documents/models, or /sdcard/models."
        allFiles -> "Found $count .gguf file(s) in shared storage."
        else -> "Shared-storage scan needs all-files access (optional). The picker below works without it."
    }

    private companion object {
        const val KEY_LOCAL_MODEL = "local_model_id"
        const val DEFAULT_CONTEXT = 2048

        fun hubId(file: HuggingFaceGgufFile): String = "hf-" + (file.sha256 ?: file.fileName).take(16)
    }
}
