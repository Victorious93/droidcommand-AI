package ai.droidcommand.llm.local

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** A downloadable/installed GGUF model. [sha256] is the expected lowercase-hex digest. */
data class ModelMetadata(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sha256: String,
    val contextTokens: Int,
    val sizeBytes: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(sha256.matches(SHA256_HEX)) { "sha256 must be 64 lowercase hex characters" }
        require(contextTokens > 0) { "contextTokens must be positive" }
        require(fileName.isNotBlank() && fileName == File(fileName).name) { "fileName must be a bare file name" }
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

/**
 * Registry of models under [modelsDir] with SHA-256 integrity checking.
 * [verify] is the only way to obtain a loadable path: a model whose digest
 * does not match is never reported [ModelCheck.Verified] (Phase 2: "every
 * downloaded model SHA-256-checked before load"). Downloading is not
 * implemented here.
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

    companion object {
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
