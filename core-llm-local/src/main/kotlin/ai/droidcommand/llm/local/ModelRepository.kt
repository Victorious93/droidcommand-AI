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
}

sealed class ModelInstallResult {
    data class Installed(val path: String) : ModelInstallResult()

    data class Failed(val reason: String) : ModelInstallResult()
}

/**
 * Registry of models under [modelsDir] with SHA-256 integrity checking.
 * [verify] is the only way to obtain a loadable path: a model whose digest
 * does not match is never reported [ModelCheck.Verified] (Phase 2: "every
 * downloaded model SHA-256-checked before load"). [install] downloads through
 * a [ModelDownloader] into a `.part` file and moves it into place only after
 * its size and SHA-256 match the registered metadata.
 */
class ModelRepository(private val modelsDir: File) {
    private val models = LinkedHashMap<String, ModelMetadata>()

    fun register(metadata: ModelMetadata) {
        require(metadata.id !in models) { "Model '${metadata.id}' already registered" }
        models[metadata.id] = metadata
    }

    fun list(): List<ModelMetadata> = models.values.toList()

    fun get(id: String): ModelMetadata? = models[id]

    fun verify(id: String): ModelCheck {
        val meta = models[id] ?: throw NoSuchElementException("Unknown model '$id'")
        val file = File(modelsDir, meta.fileName)
        if (!file.isFile) return ModelCheck.Missing(file.path)
        val actual = file.inputStream().use(::sha256Hex)
        return if (actual == meta.sha256) ModelCheck.Verified(file.path) else ModelCheck.Mismatch(file.path, actual)
    }

    /**
     * Downloads [id] from its registered [ModelMetadata.url] unless it already verifies. Never throws for an
     * expected failure (network, disk, cancellation, bad digest): the result says why, any `.part` file is
     * deleted, and a previously verified file is never replaced by an unverified one. Blocking and disk/network
     * heavy: call off the main thread. [onProgress] receives bytes downloaded so far in this call.
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
        try {
            var done = 0L
            downloader.download(url, part, needed ?: DEFAULT_MAX_BYTES, { n ->
                done += n
                onProgress(done)
            }, cancelled)
            if (needed != null && part.length() != needed) {
                return ModelInstallResult.Failed("Downloaded ${part.length()} bytes, expected $needed; file discarded")
            }
            val actual = part.inputStream().use(::sha256Hex)
            if (actual != meta.sha256) return ModelInstallResult.Failed("SHA-256 mismatch (got $actual); file discarded")
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return ModelInstallResult.Installed(target.path)
        } catch (e: IOException) {
            return ModelInstallResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            part.delete()
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
