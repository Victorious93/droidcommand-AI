package ai.droidcommand.voice

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

/**
 * One file of a voice/model. [sha256] is the expected lowercase-hex digest of the final file.
 * When [archiveEntry] is set the file is not downloaded by itself: [url] is the `.tar.bz2` it is extracted from
 * (the same URL for every file of that model) and [archiveEntry] is its exact path inside the archive.
 */
data class VoiceFile(val name: String, val url: String, val sha256: String, val archiveEntry: String? = null) {
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
data class VoiceModel(
    val id: String,
    val displayName: String,
    val files: List<VoiceFile>,
    val license: String,
    /** Expected SHA-256 of the whole archive; required exactly when the files carry an [VoiceFile.archiveEntry]. */
    val archiveSha256: String? = null,
) {
    init {
        val fromArchive = files.filter { it.archiveEntry != null }
        require(fromArchive.isEmpty() || fromArchive.size == files.size) { "files must be all archive entries or none" }
        if (fromArchive.isNotEmpty()) {
            require(files.map { it.url }.toSet().size == 1) { "archive entries must share one archive url" }
            require(archiveSha256 != null && archiveSha256.matches(SHA256_HEX)) { "archiveSha256 must be 64 lowercase hex characters" }
        } else {
            require(archiveSha256 == null) { "archiveSha256 given for a model with no archive entries" }
        }
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
            val failure = if (model.archiveSha256 != null) installFromArchive(model, dir, onProgress, cancelled) else installFiles(model, dir, onProgress, cancelled)
            if (failure != null) return InstallResult.Failed(failure)
            return when (val s = status(id)) {
                is VoiceModelStatus.Verified -> InstallResult.Installed(s.dir)
                else -> InstallResult.Failed("Model not verified after download: $s")
            }
        } catch (e: IOException) {
            return InstallResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun isVerified(file: File, f: VoiceFile) = file.isFile && file.inputStream().use(::sha256Hex) == f.sha256

    private fun installFiles(model: VoiceModel, dir: File, onProgress: (Long) -> Unit, cancelled: () -> Boolean): String? {
        var done = 0L
        for (f in model.files) {
            val target = File(dir, f.name)
            if (isVerified(target, f)) continue
            if (cancelled()) return "Cancelled"
            val part = File(dir, f.name + ".part")
            try {
                downloader.download(f.url, part, { n -> done += n; onProgress(done) }, cancelled)
                val actual = part.inputStream().use(::sha256Hex)
                if (actual != f.sha256) return "${f.name}: SHA-256 mismatch (got $actual); file discarded"
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                part.delete()
            }
        }
        return null
    }

    /**
     * Downloads the model's `.tar.bz2`, requires its pinned SHA-256 to match *before opening it*, then extracts
     * only the exact entries named by the model into fixed bare file names (so an entry path can never choose
     * where a file lands), each capped at [MAX_ENTRY_BYTES] and checked against its own digest before being
     * moved into place. Anything else in the archive is ignored.
     */
    private fun installFromArchive(model: VoiceModel, dir: File, onProgress: (Long) -> Unit, cancelled: () -> Boolean): String? {
        if (model.files.all { isVerified(File(dir, it.name), it) }) return null
        if (cancelled()) return "Cancelled"
        val archive = File(dir, "archive.part")
        val extracted = mutableListOf<File>()
        try {
            var done = 0L
            downloader.download(model.files.first().url, archive, { n -> done += n; onProgress(done) }, cancelled)
            val actual = archive.inputStream().use(::sha256Hex)
            if (actual != model.archiveSha256) return "archive: SHA-256 mismatch (got $actual); file discarded"

            val wanted = model.files.associateBy { it.archiveEntry!!.removePrefix("./") }
            val seen = mutableSetOf<String>()
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (!entry.isFile) continue
                    val f = wanted[entry.name.removePrefix("./")] ?: continue
                    if (cancelled()) return "Cancelled"
                    val part = File(dir, f.name + ".part").also { extracted += it }
                    copyCapped(tar, part)
                    val got = part.inputStream().use(::sha256Hex)
                    if (got != f.sha256) return "${f.name}: SHA-256 mismatch after extraction (got $got); file discarded"
                    Files.move(part.toPath(), File(dir, f.name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    seen += f.name
                }
            }
            val missing = model.files.map { it.name } - seen
            return if (missing.isEmpty()) null else "archive is missing expected entries: ${missing.joinToString()}"
        } finally {
            archive.delete()
            extracted.forEach { it.delete() }
        }
    }

    private fun copyCapped(input: InputStream, dest: File) {
        dest.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_ENTRY_BYTES) throw IOException("Archive entry exceeds $MAX_ENTRY_BYTES bytes")
                out.write(buf, 0, n)
            }
        }
    }

    /** Deletes the model's directory. Returns false if it could not be fully removed. */
    fun delete(id: String): Boolean {
        val model = models[id] ?: throw NoSuchElementException("Unknown voice model '$id'")
        val dir = File(root, model.id)
        return !dir.exists() || dir.deleteRecursively()
    }

    companion object {
        private const val MAX_ENTRY_BYTES = 512L shl 20

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
