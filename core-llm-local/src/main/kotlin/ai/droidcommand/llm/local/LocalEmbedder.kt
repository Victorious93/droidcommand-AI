package ai.droidcommand.llm.local

import ai.droidcommand.rag.Embedder
import kotlin.math.sqrt

/**
 * Backend seam for a GGUF embedding model (e.g. nomic-embed-text), loaded separately from the chat model.
 * No JNI implementation exists yet — only fakes — so this is a contract, not a working feature.
 */
interface EmbeddingBackend {
    /** Loads the embedding GGUF at [modelPath]. Throws [InferenceException] on failure. */
    fun load(modelPath: String)

    /** Returns the raw embedding of [text]. Throws [InferenceException] on failure. */
    fun embed(text: String): FloatArray

    fun unload()
}

/**
 * Adapts an [EmbeddingBackend] to `core-rag`'s [Embedder]. Vectors are L2-normalised (so cosine and dot
 * product agree and model scale differences don't matter). Fails loudly rather than returning bad data:
 * an empty or non-finite vector, or a dimension that changes between calls, throws [InferenceException],
 * because silently indexing garbage would make retrieval quietly wrong.
 */
class LocalEmbedder(private val backend: EmbeddingBackend) : Embedder {
    private var dimension: Int? = null

    @Synchronized
    override fun embed(texts: List<String>): List<FloatArray> = texts.map { normalise(backend.embed(it)) }

    private fun normalise(raw: FloatArray): FloatArray {
        if (raw.isEmpty()) throw InferenceException("Embedding model returned an empty vector.")
        if (raw.any { !it.isFinite() }) throw InferenceException("Embedding model returned a non-finite value.")
        val expected = dimension ?: raw.size.also { dimension = it }
        if (raw.size != expected) throw InferenceException("Embedding dimension changed from $expected to ${raw.size}.")
        var sum = 0.0
        for (x in raw) sum += x.toDouble() * x
        val norm = sqrt(sum).toFloat()
        // A zero vector carries no direction; leave it so the vector store ignores it.
        return if (norm == 0f) raw else FloatArray(raw.size) { raw[it] / norm }
    }
}
