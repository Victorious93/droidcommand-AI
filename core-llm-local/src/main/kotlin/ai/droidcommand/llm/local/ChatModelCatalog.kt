package ai.droidcommand.llm.local

/**
 * What a [ModelMetadata] must carry before the app may download it by itself. A model that fails this is still usable
 * if the owner imports the file by hand; it is just never fetched automatically.
 *
 * Adapted from OpenDroid's `ManagedModelArtifactMetadata.isComplete` (Apache-2.0, Copyright (c) 2026 OpenDroid
 * Contributors, https://github.com/Victorious93/opendroid): a record missing any publisher integrity field is not
 * downloadable, and the URL must name an immutable revision so the digest keeps meaning the same bytes.
 */
object ModelDownloadPolicy {
    private val COMMIT = Regex("^[0-9a-f]{40}$")

    /** Null when [metadata] may be downloaded; otherwise the reason it may not. */
    fun problem(metadata: ModelMetadata): String? {
        val url = metadata.url ?: return "no download URL"
        if (metadata.sizeBytes == null) return "no published size"
        if (!metadata.fileName.endsWith(".gguf", ignoreCase = true)) return "file name does not end in .gguf"
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return "download URL is not a valid URI"
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return "download URL is not https"
        if (uri.userInfo != null) return "download URL carries credentials"
        // Hugging Face Hub form: https://huggingface.co/<owner>/<repo>/resolve/<40-hex commit>/<file>. A branch name such
        // as "main" would let the file change under a digest that was recorded once.
        val segments = uri.path.orEmpty().split('/')
        val resolveAt = segments.indexOf("resolve")
        val revision = if (resolveAt >= 0) segments.getOrNull(resolveAt + 1) else null
        if (revision == null || !COMMIT.matches(revision)) return "download URL is not pinned to a 40-character commit (/resolve/<commit>/)"
        return null
    }

    fun isDownloadable(metadata: ModelMetadata): Boolean = problem(metadata) == null
}

/** A curated chat GGUF plus the Hub license it was checked against. */
data class ChatModelCatalogEntry(
    val metadata: ModelMetadata,
    val license: String,
    val repoId: String,
    val quant: String,
)

/**
 * On-device chat models the app offers to download from Hugging Face. Every entry is Apache-2.0,
 * pinned to a 40-hex Hub commit, and carries the publisher's LFS size and SHA-256 (copied from
 * `GET /api/models/{repo}/tree/{sha}` on 2026-10-30 — not invented). [ModelDownloadPolicy] still
 * refuses an incomplete entry before any request.
 *
 * Search of other Hub GGUF repos is live ([HuggingFaceGgufClient]); this list is only the
 * first-party, license-checked defaults. No paid source, no API key required.
 */
object ChatModelCatalog {
    /**
     * `HuggingFaceTB/SmolLM2-360M-Instruct-GGUF`, Q8_0. Apache-2.0. Hub revision
     * `593b5a2e04c8f3e4ee880263f93e0bd2901ad47f`; size and SHA-256 are that file's LFS metadata.
     */
    val SMOLLM2_360M_INSTRUCT_Q8_0 = ChatModelCatalogEntry(
        metadata = ModelMetadata(
            id = "smollm2-360m-instruct-q8_0",
            displayName = "SmolLM2 360M Instruct (Q8_0)",
            fileName = "smollm2-360m-instruct-q8_0.gguf",
            sha256 = "48ab3034d0dd401fbc721eb1df3217902fee7dab9078992d66431f09b7750201",
            contextTokens = 2048,
            sizeBytes = 386_404_992L,
            url = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/" +
                "593b5a2e04c8f3e4ee880263f93e0bd2901ad47f/smollm2-360m-instruct-q8_0.gguf",
        ),
        license = "apache-2.0",
        repoId = "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF",
        quant = "Q8_0",
    )

    /**
     * `Qwen/Qwen2.5-0.5B-Instruct-GGUF`, Q4_K_M. Apache-2.0. Hub revision
     * `9217f5db79a29953eb74d5343926648285ec7e67`; size and SHA-256 are that file's LFS metadata.
     */
    val QWEN25_05B_INSTRUCT_Q4_K_M = ChatModelCatalogEntry(
        metadata = ModelMetadata(
            id = "qwen2.5-0.5b-instruct-q4_k_m",
            displayName = "Qwen2.5 0.5B Instruct (Q4_K_M)",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            contextTokens = 4096,
            sizeBytes = 491_400_032L,
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/" +
                "9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        ),
        license = "apache-2.0",
        repoId = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
        quant = "Q4_K_M",
    )

    /**
     * `TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF`, Q4_K_M. Apache-2.0 (base TinyLlama). Hub revision
     * `52e7645ba7c309695bec7ac98f4f005b139cf465`; size and SHA-256 are that file's LFS metadata.
     */
    val TINYLLAMA_11B_CHAT_Q4_K_M = ChatModelCatalogEntry(
        metadata = ModelMetadata(
            id = "tinyllama-1.1b-chat-q4_k_m",
            displayName = "TinyLlama 1.1B Chat (Q4_K_M)",
            fileName = "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            sha256 = "9fecc3b3cd76bba89d504f29b616eedf7da85b96540e490ca5824d3f7d2776a0",
            contextTokens = 2048,
            sizeBytes = 668_788_096L,
            url = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/" +
                "52e7645ba7c309695bec7ac98f4f005b139cf465/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
        ),
        license = "apache-2.0",
        repoId = "TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF",
        quant = "Q4_K_M",
    )

    /**
     * `HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF`, Q4_K_M. Apache-2.0. Hub revision
     * `2d4a76a30b4af41ecd395c35725ac11688d4cfe4`; size and SHA-256 are that file's LFS metadata.
     */
    val SMOLLM2_17B_INSTRUCT_Q4_K_M = ChatModelCatalogEntry(
        metadata = ModelMetadata(
            id = "smollm2-1.7b-instruct-q4_k_m",
            displayName = "SmolLM2 1.7B Instruct (Q4_K_M)",
            fileName = "smollm2-1.7b-instruct-q4_k_m.gguf",
            sha256 = "decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33",
            contextTokens = 2048,
            sizeBytes = 1_055_609_536L,
            url = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/" +
                "2d4a76a30b4af41ecd395c35725ac11688d4cfe4/smollm2-1.7b-instruct-q4_k_m.gguf",
        ),
        license = "apache-2.0",
        repoId = "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF",
        quant = "Q4_K_M",
    )

    val all: List<ChatModelCatalogEntry> = listOf(
        SMOLLM2_360M_INSTRUCT_Q8_0,
        QWEN25_05B_INSTRUCT_Q4_K_M,
        TINYLLAMA_11B_CHAT_Q4_K_M,
        SMOLLM2_17B_INSTRUCT_Q4_K_M,
    )

    val entries: List<ModelMetadata> = all.map { it.metadata }

    fun find(id: String): ModelMetadata? = entries.firstOrNull { it.id == id }

    fun findEntry(id: String): ChatModelCatalogEntry? = all.firstOrNull { it.metadata.id == id }
}
