package ai.droidcommand.app.documents

import ai.droidcommand.llm.local.EmbeddingModelCatalog
import ai.droidcommand.llm.local.HttpsModelDownloader
import ai.droidcommand.llm.local.LocalEmbedder
import ai.droidcommand.llm.local.ModelCheck
import ai.droidcommand.llm.local.ModelInstallResult
import ai.droidcommand.llm.local.ModelRepository
import ai.droidcommand.llm.local.android.LlamaCppEmbeddingBackend
import ai.droidcommand.rag.Chunk
import ai.droidcommand.rag.DocumentAttacher
import ai.droidcommand.rag.DocumentInfo
import ai.droidcommand.rag.DocumentLibrary
import ai.droidcommand.rag.DocumentReader
import ai.droidcommand.rag.DocumentRetriever
import ai.droidcommand.rag.Embedder
import ai.droidcommand.rag.PdfTextExtractor
import ai.droidcommand.rag.ScoredChunk
import ai.droidcommand.rag.VectorStore
import ai.droidcommand.rag.android.PdfBoxTextExtractor
import ai.droidcommand.rag.android.RoomVectorStore
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Supplies the on-device embedding model's [Embedder], or null while no embedding model is installed. */
fun interface EmbedderProvider {
    fun get(): Embedder?
}

/**
 * Wires Document Q&A for the chat screen: PDF/text reading, the Room-backed index, the embedding model's download
 * and the attacher.
 *
 * **The embedder exists only once the embedding model is installed.** [installEmbedder] downloads the pinned
 * model (`EmbeddingModelCatalog`) and `core-llm-local.ModelRepository` accepts it only if its size and SHA-256
 * match; until then [embedderAvailable] is false, the chat screen's Attach button is disabled with a stated reason,
 * and nothing is ever indexed with a fake embedding. COMPILES; NEVER RUN on a device (no device exists in any
 * session so far), so the native load of the model below has not been exercised inside this app.
 *
 * The Room database and the PDF parser are opened on first use (not at construction), because this object is
 * created on the main thread and Room refuses main-thread queries. [embedderAvailable] and [installEmbedder]
 * hash/download an ~84 MB file: call them off the main thread.
 */
@Singleton
class DocumentRuntime @Inject constructor(@ApplicationContext private val context: Context) {
    private val spec = EmbeddingModelCatalog.NOMIC_EMBED_TEXT_V1_5_Q4_K_M
    private val models = ModelRepository(File(context.filesDir, "embedding-models")).also { it.register(spec.metadata) }
    private val embedderLock = Any()
    private var loadedEmbedder: Embedder? = null

    /** Why the installed model could not be loaded, if that was tried and failed; shown to the user. */
    @Volatile
    var embedderProblem: String? = null
        private set

    @Volatile
    var embedderProvider: EmbedderProvider =
        EmbedderProvider { installedEmbedder() }

    /** Download size for the Download button's label, from the pinned catalog entry. */
    val embedderDownloadMegabytes: Long = (spec.metadata.sizeBytes ?: 0L) / BYTES_PER_MEGABYTE

    /**
     * Downloads and verifies the embedding model. Blocking: run on Dispatchers.IO. [onProgress] receives bytes so
     * far (the total is [embedderDownloadMegabytes]). The only network request is to the model host; no user data is sent.
     */
    fun installEmbedder(onProgress: (Long) -> Unit, cancelled: () -> Boolean): ModelInstallResult {
        embedderProblem = null
        return models.install(spec.metadata.id, HttpsModelDownloader(), onProgress, cancelled)
    }

    private fun installedEmbedder(): Embedder? = synchronized(embedderLock) {
        loadedEmbedder?.let { return it }
        val check = models.verify(spec.metadata.id)
        if (check !is ModelCheck.Verified) {
            if (check is ModelCheck.Mismatch) embedderProblem = "The downloaded embedding model failed its integrity check; download it again."
            return null
        }
        val backend = LlamaCppEmbeddingBackend()
        return try {
            backend.load(check.path)
            LocalEmbedder(backend, spec.documentPrefix, spec.queryPrefix).also {
                loadedEmbedder = it
                embedderProblem = null
            }
        } catch (e: Exception) {
            embedderProblem = "The embedding model could not be loaded: ${e.message ?: e.javaClass.simpleName}"
            null
        } catch (e: UnsatisfiedLinkError) {
            embedderProblem = "This device cannot run the embedding model (native library unavailable)."
            null
        }
    }

    private val store: RoomVectorStore by lazy { RoomVectorStore.open(context) }
    private val pdf: PdfBoxTextExtractor by lazy { PdfBoxTextExtractor(context) }

    private val lazyStore = object : VectorStore, DocumentLibrary {
        override fun add(chunks: List<Chunk>, vectors: List<FloatArray>) = store.add(chunks, vectors)
        override fun search(query: FloatArray, topK: Int): List<ScoredChunk> = store.search(query, topK)
        override fun remove(docId: String) = store.remove(docId)
        override fun documentIds(): Set<String> = store.documentIds()
        override fun list(): List<DocumentInfo> = store.list()
        override fun save(info: DocumentInfo) = store.save(info)
        override fun delete(docId: String) = store.delete(docId)
    }

    private val embedder = object : Embedder {
        private fun current() = embedderProvider.get() ?: throw IllegalStateException(EMBEDDER_MISSING)
        override fun embed(texts: List<String>) = current().embed(texts)
        override fun embedQuery(text: String) = current().embedQuery(text)
    }

    /** Hand this to `ChatSession(documents = …)`. Returns no excerpts until something is attached. */
    val retriever = DocumentRetriever(embedder, lazyStore)

    val attacher = DocumentAttacher(DocumentReader(PdfTextExtractor { pdf.extract(it) }), retriever, lazyStore)

    fun embedderAvailable(): Boolean = embedderProvider.get() != null

    companion object {
        private const val BYTES_PER_MEGABYTE = 1024L * 1024L
        const val EMBEDDER_MISSING = "Document Q&A needs the on-device embedding model, which is not installed."
    }
}
