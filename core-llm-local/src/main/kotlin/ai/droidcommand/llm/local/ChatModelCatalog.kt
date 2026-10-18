package ai.droidcommand.llm.local

import java.net.URI

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
        val uri = runCatching { URI(url) }.getOrNull() ?: return "download URL is not a valid URI"
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

/**
 * The on-device chat models the app offers to download.
 *
 * **This ships empty, on purpose.** An entry needs a real URL pinned to a commit, the file's exact size and its
 * SHA-256, copied from the publisher (for Hugging Face, the file's LFS metadata at that commit), and a checked
 * license; none of those can be invented here. Add one only after confirming all of them, then keep the license in the
 * KDoc as [EmbeddingModelCatalog] does. [ModelDownloadPolicy.problem] rejects an incomplete entry, and the
 * downloader refuses it before any network request. Template (placeholders, not a real model):
 *
 * ```
 * ModelMetadata(
 *     id = "example-chat-q4_k_m",
 *     displayName = "Example Chat 1B (Q4_K_M)",
 *     fileName = "example-chat-1b.Q4_K_M.gguf",
 *     sha256 = "<64 lowercase hex characters from the publisher>",
 *     contextTokens = 4096,
 *     sizeBytes = <exact byte size from the publisher>,
 *     url = "https://huggingface.co/<owner>/<repo>/resolve/<40-hex commit>/example-chat-1b.Q4_K_M.gguf",
 * )
 * ```
 */
object ChatModelCatalog {
    val entries: List<ModelMetadata> = emptyList()

    fun find(id: String): ModelMetadata? = entries.firstOrNull { it.id == id }
}
