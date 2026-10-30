package ai.droidcommand.app.localmodels

import ai.droidcommand.llm.factory.LocalProviderResources
import ai.droidcommand.llm.local.DeviceMemory
import ai.droidcommand.llm.local.GgufStorageScanner
import ai.droidcommand.llm.local.InferenceBackend
import ai.droidcommand.llm.local.InferenceException
import ai.droidcommand.llm.local.ModelDownloadPolicy
import ai.droidcommand.llm.local.ModelDownloader
import ai.droidcommand.llm.local.ModelInstallResult
import ai.droidcommand.llm.local.ModelMetadata
import ai.droidcommand.llm.local.ModelRepository
import ai.droidcommand.llm.local.ScannedGguf
import ai.droidcommand.llm.local.android.LlamaCppBackend
import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The on-device (GGUF) models the owner imported: their files under `filesDir/local-models`, the persisted registry,
 * and the core `ModelRepository` that `ChatSession` checks each file against before loading it.
 *
 * `ModelRepository` has no unregister, so a removed model stays registered in memory until the process ends. That is
 * harmless: the UI lists only the registry, and importing the same bytes again yields the same id.
 *
 * Blocking work (file reads, digests, the copy): call these off the main thread.
 */
@Singleton
class LocalModelRuntime internal constructor(
    private val dir: File,
    private val memory: DeviceMemory? = null,
    private val externalStorage: () -> File? = { null },
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        dir = File(context.filesDir, DIRECTORY),
        memory = AndroidDeviceMemory(context),
        externalStorage = {
            runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        },
    )

    private val registry = LocalModelRegistry(dir)
    private val importer = LocalModelImporter(dir)
    private val lock = Any()
    private var models: List<ImportedModel>? = null

    /** GGUF header checking is on: a file that is not a GGUF is never reported loadable, whatever its digest. */
    val repository = ModelRepository(dir, requireGguf = true)

    /** Passed to `ChatSession`. A device without the native library fails the turn with a message instead of crashing. */
    val resources = LocalProviderResources(repository, backendFactory = { newBackend() }, memory = memory)

    /** The imported models in import order. The first call loads the registry and registers each model with [repository]. */
    fun list(): List<ImportedModel> = synchronized(lock) { current() }

    /** Copies [input] into storage and registers it. Nothing is registered unless the outcome is [ImportOutcome.Imported]. */
    fun importModel(name: String, input: InputStream, expectedBytes: Long?, onProgress: (Long) -> Unit): ImportOutcome {
        val known = list().map { it.id }.toSet()
        val outcome = importer.importFrom(name, input, expectedBytes, known, onProgress)
        if (outcome is ImportOutcome.Imported) {
            remember(outcome.model)
        }
        return outcome
    }

    /**
     * Uses a `.gguf` already on disk without copying it into [dir]. Intended for the all-files scan
     * (Download, Documents/models, /sdcard/models). The user's file is not deleted if they later remove
     * the model from the app list.
     */
    fun importExisting(file: File): ImportOutcome {
        val known = list().map { it.id }.toSet()
        val outcome = importer.importExisting(file, known)
        if (outcome is ImportOutcome.Imported) {
            remember(outcome.model, File(outcome.model.externalPath ?: file.path))
        }
        return outcome
    }

    /** `.gguf` files in the conventional folders, when those folders are readable. */
    fun scanStorage(): List<ScannedGguf> = GgufStorageScanner.scan(GgufStorageScanner.commonRoots(externalStorage()))

    /**
     * Downloads [metadata] into the same directory the imports use and registers it, so it appears in [list] like an
     * imported model. Blocking and network/disk heavy: call off the main thread (the WorkManager worker does).
     *
     * Refuses an entry [ModelDownloadPolicy] rejects (no pinned URL, no published size) before any request. The file is
     * registered only after `ModelRepository.install` has checked its size, GGUF header and SHA-256; a file that fails
     * is deleted and nothing is registered. A model already installed under this id is returned as installed without a
     * request. The download itself runs outside [lock], so [list] and imports stay responsive.
     */
    fun installDownloadable(
        metadata: ModelMetadata,
        downloader: ModelDownloader,
        onProgress: (Long) -> Unit,
        cancelled: () -> Boolean,
    ): ModelInstallResult {
        ModelDownloadPolicy.problem(metadata)?.let { return ModelInstallResult.Failed("Model '${metadata.id}' cannot be downloaded: $it") }
        // The directory also holds the registry and `import-*.part` files, which discardAbandonedImports deletes.
        if (metadata.fileName == LocalModelRegistry.FILE_NAME || metadata.fileName.startsWith("import-")) {
            return ModelInstallResult.Failed("Model '${metadata.id}' has a reserved file name")
        }
        val known = list().firstOrNull { it.id == metadata.id }
        if (known != null && (known.metadata.sha256 != metadata.sha256 || known.metadata.fileName != metadata.fileName)) {
            return ModelInstallResult.Failed("A different model is already installed under '${metadata.id}'")
        }
        // A throwaway repository, because the shared one has no unregister and keeps the URL-less record a restart reloads.
        val installer = ModelRepository(dir, requireGguf = true).also { it.register(metadata) }
        val result = installer.install(metadata.id, downloader, onProgress, cancelled)
        if (result is ModelInstallResult.Installed && known == null) {
            remember(ImportedModel(metadata.copy(url = null), System.currentTimeMillis()))
        }
        return result
    }

    /** Forgets [id] and deletes a copied file. An in-place (scanned) file is left on disk. */
    fun remove(id: String) {
        synchronized(lock) {
            val all = current()
            val target = all.firstOrNull { it.id == id } ?: return
            val kept = all - target
            registry.save(kept)
            models = kept
            if (target.externalPath == null) {
                File(dir, target.metadata.fileName).delete()
            }
        }
    }

    /**
     * Null when the JNI library loaded. Otherwise a message the UI can show instead of crashing
     * (`UnsatisfiedLinkError` / `NoClassDefFoundError` on a device missing `libdca_llama_jni.so`).
     */
    fun nativeLibraryProblem(): String? = try {
        LlamaCppBackend.probeCapabilities()
        null
    } catch (_: LinkageError) {
        "This device cannot run on-device models: the native library is unavailable."
    } catch (_: ExceptionInInitializerError) {
        // Companion `System.loadLibrary` wraps UnsatisfiedLinkError in this on first class init.
        "This device cannot run on-device models: the native library is unavailable."
    }

    private fun current(): List<ImportedModel> {
        models?.let { return it }
        // The first look at the directory in this process, and every import calls list() first, so nothing can be mid-copy
        // yet: whatever `.part` file exists is from an earlier run that was killed.
        importer.discardAbandonedImports()
        val loaded = registry.load().mapNotNull { model ->
            val external = model.externalPath?.let { File(it) }
            when {
                external != null && external.isFile -> {
                    if (repository.get(model.id) == null) repository.register(model.metadata, external)
                    model
                }
                external != null -> null
                File(dir, model.metadata.fileName).isFile -> {
                    if (repository.get(model.id) == null) repository.register(model.metadata)
                    model
                }
                else -> null
            }
        }
        models = loaded
        return loaded
    }

    private fun remember(model: ImportedModel, file: File? = null) {
        synchronized(lock) {
            val updated = current() + model
            if (repository.get(model.id) == null) {
                val location = file ?: model.externalPath?.let { File(it) }
                if (location != null) repository.register(model.metadata, location) else repository.register(model.metadata)
            }
            registry.save(updated)
            models = updated
        }
    }

    private fun newBackend(): InferenceBackend = try {
        LlamaCppBackend.create()
    } catch (e: LinkageError) {
        throw InferenceException("This device cannot run on-device models: the native library is unavailable.", e)
    }

    private companion object {
        const val DIRECTORY = "local-models"
    }
}

/** [DeviceMemory] backed by `ActivityManager.MemoryInfo` (total + available). */
class AndroidDeviceMemory(private val context: Context) : DeviceMemory {
    override fun snapshot(): ai.droidcommand.llm.local.MemorySnapshot {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return ai.droidcommand.llm.local.MemorySnapshot(totalBytes = info.totalMem, availableBytes = info.availMem)
    }
}
