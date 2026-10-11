package ai.droidcommand.llm.local

/**
 * An embedding model plus the asymmetric-retrieval prefixes it was trained with (see [LocalEmbedder]).
 * [license] is the model card's declared license; it gates shipping, and is recorded here so adding an entry is a
 * deliberate, license-checked act.
 */
data class EmbeddingModelSpec(
    val metadata: ModelMetadata,
    val documentPrefix: String,
    val queryPrefix: String,
    val license: String,
)

object EmbeddingModelCatalog {
    /**
     * `nomic-ai/nomic-embed-text-v1.5-GGUF`, Q4_K_M, 768 dimensions, trained at 2048 context. URL pinned to the
     * Hub revision below; size and SHA-256 are the Hub's LFS metadata for that file (checked 2026-10-11) and match
     * the file the 2026-10-09o audit entry ran. The size is ~84 MB, not the ~270 MB the roadmap guessed.
     */
    val NOMIC_EMBED_TEXT_V1_5_Q4_K_M = EmbeddingModelSpec(
        metadata = ModelMetadata(
            id = "nomic-embed-text-v1.5-q4_k_m",
            displayName = "Nomic Embed Text v1.5 (Q4_K_M)",
            fileName = "nomic-embed-text-v1.5.Q4_K_M.gguf",
            sha256 = "d4e388894e09cf3816e8b0896d81d265b55e7a9fff9ab03fe8bf4ef5e11295ac",
            contextTokens = 2048,
            sizeBytes = 84_106_624L,
            url = "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/" +
                "0188c9bf409793f810680a5a431e7b899c46104c/nomic-embed-text-v1.5.Q4_K_M.gguf",
        ),
        documentPrefix = "search_document: ",
        queryPrefix = "search_query: ",
        license = "Apache-2.0",
    )
}
