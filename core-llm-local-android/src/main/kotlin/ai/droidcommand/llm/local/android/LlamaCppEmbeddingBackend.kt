package ai.droidcommand.llm.local.android

import ai.droidcommand.llm.local.EmbeddingBackend
import ai.droidcommand.llm.local.InferenceException

/**
 * [EmbeddingBackend] backed by the llama.cpp JNI shim ([llama_jni.cpp]'s `DcaEmbeddingContext`
 * entrypoints), loaded as a model entirely separate from [LlamaCppBackend]'s chat context — the
 * roadmap calls for a dedicated embedding model (e.g. nomic-embed-text) loaded alongside, not
 * instead of, the chat model. CPU only; pooling is left to the model's own trained pooling type on
 * the native side (see the shim's own comment), so a mean-pooled model (nomic) and a CLS-pooled one
 * (BGE/E5) each embed the way they were trained, and every call still returns one fixed-size vector.
 *
 * [load], [embed] and [unload] are [Synchronized] on this instance: [unload] frees the native
 * context, so an [embed] racing an [unload] would read a freed pointer (use-after-free). Serializing
 * here also makes the native handle's zero/non-zero state safe to publish without a separate
 * `@Volatile`. Embeddings are computed one at a time regardless — `LocalEmbedder.embed` already
 * serializes its callers — so this adds correctness, not contention, over the previous design.
 */
class LlamaCppEmbeddingBackend : EmbeddingBackend {
    private var handle: Long = 0L

    @Synchronized
    override fun load(modelPath: String) {
        check(handle == 0L) { "load() called on an already-loaded backend; call unload() first" }
        val h = nativeLoad(modelPath, DEFAULT_CONTEXT_TOKENS)
        if (h == 0L) {
            // nativeLoad throws InferenceException on failure before returning 0, so reaching here
            // with a zero handle and no pending exception would itself be a native bug.
            throw InferenceException("llama.cpp returned no embedding context and raised no exception")
        }
        handle = h
    }

    @Synchronized
    override fun embed(text: String): FloatArray {
        val h = handle
        check(h != 0L) { "embed() called before load()" }
        return nativeEmbed(h, text)
    }

    @Synchronized
    override fun unload() {
        val h = handle
        if (h == 0L) return
        handle = 0L
        nativeUnload(h)
    }

    private external fun nativeLoad(modelPath: String, contextTokens: Int): Long

    private external fun nativeEmbed(handle: Long, text: String): FloatArray

    private external fun nativeUnload(handle: Long)

    companion object {
        // Embedding texts are chunks (core-rag's TextChunker defaults to 512 words / ~64 overlap);
        // this comfortably covers that plus the model's own added special tokens.
        private const val DEFAULT_CONTEXT_TOKENS = 2048

        init {
            System.loadLibrary("dca_llama_jni")
        }
    }
}
