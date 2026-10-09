package ai.droidcommand.app.documents

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
import javax.inject.Inject
import javax.inject.Singleton

/** Supplies the on-device embedding model's [Embedder], or null while no embedding model is installed. */
fun interface EmbedderProvider {
    fun get(): Embedder?
}

/**
 * Wires Document Q&A for the chat screen: PDF/text reading, the Room-backed index, and the attacher.
 *
 * **No embedder yet, on purpose.** The only real embedder is `core-llm-local-android.LlamaCppEmbeddingBackend`,
 * and that module has minSdk 28 while this app has minSdk 26, so the app cannot depend on it until the
 * distribution decision recorded in the audit (dynamic feature module vs. raising minSdk) is made. Until
 * then [embedderAvailable] is false, the chat screen's Attach button is disabled with a stated reason, and
 * nothing is ever indexed with a fake embedding. Supplying a real [EmbedderProvider] is the only change
 * needed here to turn the feature on.
 *
 * The Room database and the PDF parser are opened on first use (not at construction), because this object is
 * created on the main thread and Room refuses main-thread queries.
 */
@Singleton
class DocumentRuntime @Inject constructor(@ApplicationContext private val context: Context) {
    @Volatile
    var embedderProvider: EmbedderProvider = EmbedderProvider { null }

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
        const val EMBEDDER_MISSING = "Document Q&A needs the on-device embedding model, which is not installed."
    }
}
