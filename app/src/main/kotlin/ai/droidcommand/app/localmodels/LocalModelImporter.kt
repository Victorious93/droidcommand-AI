package ai.droidcommand.app.localmodels

import ai.droidcommand.llm.local.GgufFileName
import ai.droidcommand.llm.local.ModelMetadata
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

sealed class ImportOutcome {
    data class Imported(val model: ImportedModel) : ImportOutcome()

    /** The same bytes are already imported under [id]. */
    data class Duplicate(val id: String) : ImportOutcome()

    /** The file is not one this import accepts. Nothing was kept. */
    data class Rejected(val reason: String) : ImportOutcome()

    /** Copying failed, or the storage check refused. Any partial copy was deleted. */
    data class Failed(val reason: String) : ImportOutcome()
}

/**
 * Copies a user-chosen file into [dir] while hashing it, and reports the metadata to register.
 *
 * Accepts only a `.gguf` name whose first four bytes are the GGUF magic. That is a sanity check, not a security check:
 * llama.cpp parses the rest of the file natively, so a hostile file can still reach that parser. The recorded digest
 * means "unchanged since import", not "from a trusted source".
 */
class LocalModelImporter(
    private val dir: File,
    private val usableBytes: (File) -> Long = { it.usableSpace },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
) {
    /** [expectedBytes], when known, drives the free-space check. [existingIds] are the ids already imported. */
    fun importFrom(
        originalName: String,
        input: InputStream,
        expectedBytes: Long?,
        existingIds: Set<String>,
        onProgress: (Long) -> Unit = {},
    ): ImportOutcome {
        val looksLikeGguf = GgufFileName.looksLikeGguf(originalName)
        if (!dir.isDirectory && !dir.mkdirs()) return ImportOutcome.Failed("Could not create ${dir.path}.")
        if (expectedBytes != null && usableBytes(dir) < expectedBytes + FREE_SPACE_MARGIN_BYTES) {
            return ImportOutcome.Failed("Not enough free storage: about ${expectedBytes / MEGABYTE} MB needed.")
        }
        val temp = try {
            File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, dir)
        } catch (e: IOException) {
            return ImportOutcome.Failed("Could not create a file for the import: ${e.message ?: "unknown error"}.")
        }
        var kept = false
        try {
            val header = ByteArray(GGUF_MAGIC.size)
            if (readFully(input, header) != header.size || !header.contentEquals(GGUF_MAGIC)) {
                // A content:// picker often hands a display name without ".gguf". Only complain about the
                // name when the bytes are not a GGUF either — otherwise a real model would be rejected.
                return ImportOutcome.Rejected(
                    if (looksLikeGguf) {
                        "This is not a GGUF model file: the GGUF header is missing."
                    } else {
                        "Pick a file whose name ends in .gguf."
                    },
                )
            }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(header)
            var size = header.size.toLong()
            temp.outputStream().use { out ->
                out.write(header)
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    size += n
                    onProgress(size)
                }
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val id = ID_PREFIX + sha256.take(ID_HASH_CHARS)
            if (id in existingIds) return ImportOutcome.Duplicate(id)
            val target = File(dir, id + GGUF_EXTENSION)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            kept = true
            val metadata = ModelMetadata(
                id = id,
                displayName = displayName(originalName),
                fileName = target.name,
                sha256 = sha256,
                contextTokens = contextTokens,
                sizeBytes = size,
            )
            return ImportOutcome.Imported(ImportedModel(metadata, nowMillis()))
        } catch (e: IOException) {
            return ImportOutcome.Failed("Import failed: ${e.message ?: e.javaClass.simpleName}.")
        } finally {
            if (!kept) temp.delete()
        }
    }

    /**
     * Registers a `.gguf` that already lives on disk (a scan hit under all-files access) without copying it.
     * The GGUF magic and digest are still checked; the file is not moved.
     */
    fun importExisting(file: File, existingIds: Set<String>): ImportOutcome {
        if (!file.isFile) return ImportOutcome.Failed("The file is no longer there.")
        if (!GgufFileName.looksLikeGguf(file.name)) {
            return ImportOutcome.Rejected("Pick a file whose name ends in .gguf.")
        }
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(GGUF_MAGIC.size)
                if (readFully(input, header) != header.size || !header.contentEquals(GGUF_MAGIC)) {
                    return ImportOutcome.Rejected("This is not a GGUF model file: the GGUF header is missing.")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(header)
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var size = header.size.toLong()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    size += n
                }
                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                val id = ID_PREFIX + sha256.take(ID_HASH_CHARS)
                if (id in existingIds) return ImportOutcome.Duplicate(id)
                val metadata = ModelMetadata(
                    id = id,
                    displayName = displayName(file.name),
                    fileName = file.name,
                    sha256 = sha256,
                    contextTokens = contextTokens,
                    sizeBytes = size,
                )
                ImportOutcome.Imported(ImportedModel(metadata, nowMillis(), externalPath = file.canonicalPath))
            }
        } catch (e: IOException) {
            ImportOutcome.Failed("Import failed: ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    /**
     * Deletes the `import-*.part` files an interrupted import left behind. An import that is cut off by the process being killed
     * or crashing never reaches its cleanup, and each such file can be as large as a model while no list or screen shows it.
     * Returns how many were removed. Call it before any import starts in this process: it cannot tell a leftover from a copy
     * that is still running.
     */
    fun discardAbandonedImports(): Int =
        dir.listFiles { file -> file.isFile && file.name.startsWith(TEMP_PREFIX) && file.name.endsWith(TEMP_SUFFIX) }
            .orEmpty()
            .count { it.delete() }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun displayName(originalName: String): String {
        val resolved = GgufFileName.resolve(originalName) ?: originalName
        return GgufFileName.displayLabel(resolved)
    }

    companion object {
        const val DEFAULT_CONTEXT_TOKENS = 2048
        private const val TEMP_PREFIX = "import-"
        private const val TEMP_SUFFIX = ".part"
        private const val GGUF_EXTENSION = ".gguf"
        private val GGUF_MAGIC = "GGUF".toByteArray(Charsets.US_ASCII)
        private const val ID_PREFIX = "user-"
        private const val ID_HASH_CHARS = 16
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MEGABYTE = 1024L * 1024L
        private const val FREE_SPACE_MARGIN_BYTES = 256L * MEGABYTE
    }
}
