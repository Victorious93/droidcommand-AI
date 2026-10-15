package ai.droidcommand.app.localmodels

import ai.droidcommand.llm.factory.LocalProviderResources
import ai.droidcommand.llm.local.InferenceBackend
import ai.droidcommand.llm.local.InferenceException
import ai.droidcommand.llm.local.ModelRepository
import ai.droidcommand.llm.local.android.LlamaCppBackend
import android.content.Context
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
class LocalModelRuntime @Inject constructor(@ApplicationContext context: Context) {
    private val dir = File(context.filesDir, DIRECTORY)
    private val registry = LocalModelRegistry(dir)
    private val importer = LocalModelImporter(dir)
    private val lock = Any()
    private var models: List<ImportedModel>? = null

    val repository = ModelRepository(dir)

    /** Passed to `ChatSession`. A device without the native library fails the turn with a message instead of crashing. */
    val resources = LocalProviderResources(repository, backendFactory = { newBackend() })

    /** The imported models in import order. The first call loads the registry and registers each model with [repository]. */
    fun list(): List<ImportedModel> = synchronized(lock) { current() }

    /** Copies [input] into storage and registers it. Nothing is registered unless the outcome is [ImportOutcome.Imported]. */
    fun importModel(name: String, input: InputStream, expectedBytes: Long?, onProgress: (Long) -> Unit): ImportOutcome {
        val known = list().map { it.id }.toSet()
        val outcome = importer.importFrom(name, input, expectedBytes, known, onProgress)
        if (outcome is ImportOutcome.Imported) {
            synchronized(lock) {
                val updated = current() + outcome.model
                if (repository.get(outcome.model.id) == null) repository.register(outcome.model.metadata)
                registry.save(updated)
                models = updated
            }
        }
        return outcome
    }

    /** Forgets [id] and deletes its file. Callers unload any loaded copy first. */
    fun remove(id: String) {
        synchronized(lock) {
            val all = current()
            val target = all.firstOrNull { it.id == id } ?: return
            val kept = all - target
            registry.save(kept)
            models = kept
            File(dir, target.metadata.fileName).delete()
        }
    }

    private fun current(): List<ImportedModel> {
        models?.let { return it }
        // The first look at the directory in this process, and every import calls list() first, so nothing can be mid-copy
        // yet: whatever `.part` file exists is from an earlier run that was killed.
        importer.discardAbandonedImports()
        val loaded = registry.load()
        loaded.forEach { if (repository.get(it.id) == null) repository.register(it.metadata) }
        models = loaded
        return loaded
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
