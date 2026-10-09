package ai.droidcommand.voice

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

/** One file of a voice/model. [sha256] is the expected lowercase-hex digest of the final file. */
data class VoiceFile(val name: String, val url: String, val sha256: String) {
    init {
        require(name.isNotBlank() && name == File(name).name && name != "." && name != "..") { "name must be a bare file name" }
        require(url.startsWith("https://")) { "url must be https" }
        require(sha256.matches(SHA256_HEX)) { "sha256 must be 64 lowercase hex characters" }
    }
}

/**
 * A downloadable voice (TTS) or wake-word model. [license] is required and must be filled in by whoever adds
 * the entry: per `docs/VOICE_PHASE_SCOPE.md` each model's license is separate from the runtime's and gates
 * shipping. No catalog is bundled in this repository — adding an entry is a deliberate, license-checked act.
 */
data class VoiceModel(val id: String, val displayName: String, val files: List<VoiceFile>, val license: String) {
    init {
        require(id.isNotBlank() && id == File(id).name) { "id must be a bare name (used as a directory)" }
        require(files.isNotEmpty()) { "a model needs at least one file" }
        require(files.map { it.name }.toSet().size == files.size) { "duplicate file names" }
        require(license.isNotBlank()) { "license must be stated" }
    }
}

sealed class VoiceModelStatus {
    data object NotInstalled : VoiceModelStatus()

    /** Some files are present and verified, others missing. */
    data class Partial(val missing: List<String>) : VoiceModelStatus()

    /** Every file present and matching its digest; [dir] is safe to load from. */
    data class Verified(val dir: File) : VoiceModelStatus()

    /** A file exists but its digest differs (corrupt or tampered). It is never reported [Verified]. */
    data class Mismatch(val file: String, val actual: String) : VoiceModelStatus()
}

sealed class InstallResult {
    data class Installed(val dir: File) : InstallResult()

    data class Failed(val reason: String) : InstallResult()
}

/** Network seam: writes the body of [url] to [dest]. Throws [IOException] on failure or cancellation. */
fun interface FileDownloader {
    fun download(url: String, dest: File, onBytes: (Long) -> Unit, cancelled: () -> Boolean)
}

/**
 * Stores each model in `root/<id>/` and only ever reports files that matched their pinned SHA-256
 * ("every downloaded model SHA-256-checked before load", Phase 2/5 rule). Downloads go to a `.part` file and
 * are moved into place only after the digest matches, so a crash or mismatch leaves no loadable bad file.
 * Same pattern as `core-llm-local.ModelRepository`; this module does not depend on it.
 */
class VoiceModelRepository(private val root: File, private val downloader: FileDownloader) {
    private val models = LinkedHashMap<String, VoiceModel>()

    fun register(model: VoiceModel) {
        require(model.id !in models) { "Voice model '${model.id}' already registered" }
        models[model.id] = model
    }

    fun list(): List<VoiceModel> = models.values.toList()

    fun get(id: String): VoiceModel? = models[id]

    fun status(id: String): VoiceModelStatus {
        val model = models[id] ?: throw NoSuchElementException("Unknown voice model '$id'")
        val dir = File(root, model.id)
        val missing = mutableListOf<String>()
        for (f in model.files) {
            val file = File(dir, f.name)
            if (!file.isFile) {
                missing += f.name
                continue
            }
            val actual = file.inputStream().use(::sha256Hex)
            if (actual != f.sha256) return VoiceModelStatus.Mismatch(f.name, actual)
        }
        return when {
            missing.isEmpty() -> VoiceModelStatus.Verified(dir)
            missing.size == model.files.size -> VoiceModelStatus.NotInstalled
            else -> VoiceModelStatus.Partial(missing)
        }
    }

    /**
     * Downloads whatever is missing or corrupt. [onProgress] gets the bytes downloaded so far in this call
     * (no total: sizes are not known up front and are not guessed). Never throws; a failure leaves
     * previously verified files in place and no partial file under a real name.
     */
    fun install(id: String, onProgress: (Long) -> Unit = {}, cancelled: () -> Boolean = { false }): InstallResult {
        val model = models[id] ?: return InstallResult.Failed("Unknown voice model '$id'")
        val dir = File(root, model.id)
        try {
            Files.createDirectories(dir.toPath())
            var done = 0L
            for (f in model.files) {
                val target = File(dir, f.name)
                if (target.isFile && target.inputStream().use(::sha256Hex) == f.sha256) continue
                if (cancelled()) return InstallResult.Failed("Cancelled")
                val part = File(dir, f.name + ".part")
                try {
                    val onBytes = { n: Long ->
                        done += n
                        onProgress(done)
                    }
                    downloader.download(f.url, part, onBytes, cancelled)
                    val actual = part.inputStream().use(::sha256Hex)
                    if (actual != f.sha256) {
                        return InstallResult.Failed("${f.name}: SHA-256 mismatch (got $actual); file discarded")
                    }
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    part.delete()
                }
            }
            return when (val s = status(id)) {
                is VoiceModelStatus.Verified -> InstallResult.Installed(s.dir)
                else -> InstallResult.Failed("Model not verified after download: $s")
            }
        } catch (e: IOException) {
            return InstallResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Deletes the model's directory. Returns false if it could not be fully removed. */
    fun delete(id: String): Boolean {
        val model = models[id] ?: throw NoSuchElementException("Unknown voice model '$id'")
        val dir = File(root, model.id)
        return !dir.exists() || dir.deleteRecursively()
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

/**
 * [FileDownloader] over [HttpURLConnection], https only. Cross-protocol redirects (https→http) are not
 * followed by the JDK; a non-200 status or a body larger than [maxBytes] is a failure. NOT exercised against a
 * real server in tests (the repository logic is tested with a fake); treat as unverified until used.
 */
class HttpsFileDownloader(private val maxBytes: Long = 1L shl 30) : FileDownloader {
    override fun download(url: String, dest: File, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        require(url.startsWith("https://")) { "https only" }
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
            var total = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw IOException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("Download exceeds $maxBytes bytes")
                        out.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
