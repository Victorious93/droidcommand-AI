package ai.droidcommand.llm.local

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A downloadable/installed GGUF model. [sha256] is the expected lowercase-hex digest. */
data class ModelMetadata(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sha256: String,
    val contextTokens: Int,
    val sizeBytes: Long? = null,
    /** Where [ModelRepository.install] fetches the file from. Must be https; null means the file can only be placed by hand. */
    val url: String? = null,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(sha256.matches(SHA256_HEX)) { "sha256 must be 64 lowercase hex characters" }
        require(contextTokens > 0) { "contextTokens must be positive" }
        require(fileName.isNotBlank() && fileName == File(fileName).name) { "fileName must be a bare file name" }
        require(url == null || url.startsWith("https://")) { "url must be https" }
        require(sizeBytes == null || sizeBytes > 0) { "sizeBytes must be positive" }
    }

    private companion object {
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}

sealed class ModelCheck {
    data class Verified(val path: String) : ModelCheck()
    data class Missing(val path: String) : ModelCheck()
    data class Mismatch(val path: String, val actual: String) : ModelCheck()

    /** The file is present but is not the described model for a reason other than its digest: wrong size, not GGUF, unreadable. */
    data class Rejected(val path: String, val failure: IntegrityFailure, val detail: String) : ModelCheck()
}

sealed class ModelInstallResult {
    data class Installed(val path: String) : ModelInstallResult()

    /**
     * [retryable] is true only for a network/disk interruption that kept the `.part` prefix and was not a
     * cancellation, so calling [ModelRepository.install] again resumes it. Size/digest failures, cancellation and
     * precondition failures are not retryable.
     */
    data class Failed(val reason: String, val retryable: Boolean = false) : ModelInstallResult()
}

/**
 * Registry of models under [modelsDir] with SHA-256 integrity checking.
 * [verify] is the only way to obtain a loadable path: a model whose digest
 * does not match is never reported [ModelCheck.Verified] (Phase 2: "every
 * downloaded model SHA-256-checked before load"). [install] downloads through
 * a [ModelDownloader] into a `.part` file and moves it into place only after
 * its size and SHA-256 match the registered metadata, and (with [requireGguf]) its GGUF header is valid.
 *
 * [requireGguf] is off by default only so tests can use stand-in bytes; every production repository that holds
 * llama.cpp model files turns it on.
 */
class ModelRepository(private val modelsDir: File, requireGguf: Boolean = false) {
    private val integrity = ModelArtifactIntegrity(requireGguf = requireGguf)
    private val models = LinkedHashMap<String, ModelMetadata>()
    private val locations = LinkedHashMap<String, File>()

    fun register(metadata: ModelMetadata, file: File? = null) {
        require(metadata.id !in models) { "Model '${metadata.id}' already registered" }
        models[metadata.id] = metadata
        if (file != null) locations[metadata.id] = file
    }

    fun list(): List<ModelMetadata> = models.values.toList()

    fun get(id: String): ModelMetadata? = models[id]

    /** Where [verify] / load look for this id: an explicit path (user storage) or `[modelsDir]/fileName`. */
    fun fileFor(id: String): File? {
        val meta = models[id] ?: return null
        return locations[id] ?: File(modelsDir, meta.fileName)
    }

    fun verify(id: String): ModelCheck {
        val meta = models[id] ?: throw NoSuchElementException("Unknown model '$id'")
        val file = fileFor(id) ?: return ModelCheck.Missing(File(modelsDir, meta.fileName).path)
        if (!file.isFile) return ModelCheck.Missing(file.path)
        return when (val result = integrity.verify(file, meta.sizeBytes, meta.sha256)) {
            IntegrityResult.Valid -> ModelCheck.Verified(file.path)
            is IntegrityResult.Invalid -> when (result.failure) {
                IntegrityFailure.HASH_MISMATCH -> ModelCheck.Mismatch(file.path, result.actualSha256.orEmpty())
                IntegrityFailure.MISSING_FILE -> ModelCheck.Missing(file.path)
                else -> ModelCheck.Rejected(file.path, result.failure, result.detail)
            }
        }
    }

    /**
     * Downloads [id] from its registered [ModelMetadata.url] unless it already verifies. Never throws for an
     * expected failure (network, disk, cancellation, bad digest): the result says why, and a previously verified
     * file is never replaced by an unverified one. A download interrupted by a network error or cancellation keeps
     * its `.part` file and the next call resumes it; a wrong size or digest deletes it. Blocking and disk/network
     * heavy: call off the main thread. [onProgress] receives total bytes on disk so far, including a resumed prefix.
     */
    @Synchronized
    fun install(
        id: String,
        downloader: ModelDownloader,
        onProgress: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): ModelInstallResult {
        val meta = models[id] ?: return ModelInstallResult.Failed("Unknown model '$id'")
        val url = meta.url ?: return ModelInstallResult.Failed("Model '$id' has no download URL")
        val target = File(modelsDir, meta.fileName)
        if (verify(id) is ModelCheck.Verified) return ModelInstallResult.Installed(target.path)
        if (!modelsDir.isDirectory && !modelsDir.mkdirs()) return ModelInstallResult.Failed("Could not create ${modelsDir.path}")
        val needed = meta.sizeBytes
        if (needed != null && modelsDir.usableSpace < needed + FREE_SPACE_MARGIN_BYTES) {
            return ModelInstallResult.Failed("Not enough free storage: ${needed / MEGABYTE} MB needed")
        }
        if (cancelled()) return ModelInstallResult.Failed("Cancelled")
        val part = File(modelsDir, meta.fileName + ".part")
        // A `.part` file left by an interrupted call is resumed, unless it is already larger than the expected size.
        if (needed != null && part.length() > needed) part.delete()
        var keepPart = false
        try {
            var done = if (part.isFile) part.length() else 0L
            if (needed == null || done < needed) {
                keepPart = true // network/disk/cancel failures keep the prefix for the next call
                downloader.download(url, part, needed ?: DEFAULT_MAX_BYTES, { n ->
                    done += n
                    onProgress(done)
                }, cancelled)
            }
            keepPart = false // from here the file is complete and judged: a bad one is discarded
            when (val result = integrity.verify(part, needed, meta.sha256)) {
                IntegrityResult.Valid -> Unit
                is IntegrityResult.Invalid -> return ModelInstallResult.Failed("${result.detail}; file discarded")
            }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return ModelInstallResult.Installed(target.path)
        } catch (e: IOException) {
            return ModelInstallResult.Failed(e.message ?: e.javaClass.simpleName, retryable = keepPart && !cancelled())
        } finally {
            if (!keepPart) part.delete()
        }
    }

    companion object {
        private const val MEGABYTE = 1024L * 1024L
        private const val FREE_SPACE_MARGIN_BYTES = 64L * MEGABYTE
        private const val DEFAULT_MAX_BYTES = 8L * 1024L * MEGABYTE

        fun sha256Hex(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
