package ai.droidcommand.app.localmodels

import ai.droidcommand.llm.local.GgufCompatibility
import ai.droidcommand.llm.local.GgufFileName
import ai.droidcommand.llm.local.GgufHeader
import ai.droidcommand.llm.local.GgufHeaderResult
import ai.droidcommand.llm.local.GgufMetadata
import ai.droidcommand.llm.local.GgufMetadataReader
import ai.droidcommand.llm.local.GgufMetadataResult
import ai.droidcommand.llm.local.ModelMetadata
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

sealed class ImportOutcome {
    /** [note] is a heads-up about a model that was accepted (for example: it has no chat template). */
    data class Imported(val model: ImportedModel, val note: String? = null) : ImportOutcome()

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
 * Accepts only a file that starts with a GGUF header this app's llama.cpp reads (v2 or v3, little-endian) and whose own
 * metadata does not rule it out as a chat model ([GgufCompatibility]: a shard of a split model, a LoRA adapter, a vision
 * projector, an embedding model). That is a sanity check, not a security check: llama.cpp parses the rest of the file
 * natively, so a hostile file can still reach that parser. The recorded digest means "unchanged since import", not "from
 * a trusted source".
 *
 * A copy that ends early is never registered: when the picker reported a size and fewer bytes arrived (the source went
 * away, an MTP transfer was still running), or the file ends inside its own metadata, the partial copy is deleted and
 * the outcome says the import was interrupted. The context window recorded for the model is [contextTokens], or the
 * model's trained window when that is smaller.
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
        cancelled: () -> Boolean = { false },
    ): ImportOutcome {
        val looksLikeGguf = GgufFileName.looksLikeGguf(originalName)
        if (!dir.isDirectory && !dir.mkdirs()) return ImportOutcome.Failed("Could not create ${dir.path}.")
        val expected = expectedBytes?.takeIf { it > 0L }
        if (expected != null) {
            val free = usableBytes(dir)
            if (free < expected + FREE_SPACE_MARGIN_BYTES) return ImportOutcome.Failed(notEnoughStorage(expected, free))
        }
        val temp = try {
            File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, dir)
        } catch (e: IOException) {
            return ImportOutcome.Failed("Could not create a file for the import: ${e.message ?: "unknown error"}.")
        }
        var kept = false
        try {
            val head = ByteArray(GgufHeader.SIZE_BYTES)
            val headRead = readFully(input, head)
            val header = head.copyOf(headRead)
            if (headRead < GGUF_MAGIC.size || !header.copyOf(GGUF_MAGIC.size).contentEquals(GGUF_MAGIC)) {
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
            // Decided on the first 24 bytes, before gigabytes are copied: an old (v1), big-endian or damaged file.
            (GgufHeader.parse(header) as? GgufHeaderResult.Invalid)?.let { return ImportOutcome.Rejected(unsupported(it.reason)) }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(header)
            var size = header.size.toLong()
            temp.outputStream().use { out ->
                out.write(header)
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    if (cancelled()) return ImportOutcome.Failed("Import cancelled. Nothing was kept.")
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    size += n
                    onProgress(size)
                }
            }
            if (expected != null && size < expected) return ImportOutcome.Failed(interrupted(size, expected))
            val gguf = when (val read = GgufMetadataReader.read(temp)) {
                is GgufMetadataResult.Invalid -> return ImportOutcome.Rejected(unsupported(read.reason))
                is GgufMetadataResult.Valid -> read.metadata
            }
            GgufCompatibility.problem(gguf)?.let { return ImportOutcome.Rejected(it) }
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
                contextTokens = GgufCompatibility.contextTokens(contextTokens, gguf),
                sizeBytes = size,
            )
            return ImportOutcome.Imported(ImportedModel(metadata, nowMillis()), GgufCompatibility.warning(gguf))
        } catch (e: IOException) {
            return ImportOutcome.Failed(copyFailure(e))
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
            val gguf: GgufMetadata = when (val read = GgufMetadataReader.read(file)) {
                is GgufMetadataResult.Invalid -> return ImportOutcome.Rejected(unsupported(read.reason))
                is GgufMetadataResult.Valid -> read.metadata
            }
            GgufCompatibility.problem(gguf)?.let { return ImportOutcome.Rejected(it) }
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
                    contextTokens = GgufCompatibility.contextTokens(contextTokens, gguf),
                    sizeBytes = size,
                )
                ImportOutcome.Imported(ImportedModel(metadata, nowMillis(), externalPath = file.canonicalPath), GgufCompatibility.warning(gguf))
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

    private fun unsupported(reason: String) = "This file cannot be used: $reason"

    private fun notEnoughStorage(neededBytes: Long, freeBytes: Long) =
        "$NOT_ENOUGH_STORAGE: the model is about ${neededBytes / MEGABYTE} MB and ${freeBytes / MEGABYTE} MB is free " +
            "(${FREE_SPACE_MARGIN_BYTES / MEGABYTE} MB is kept in reserve). Free some space, or pick a smaller quantization."

    private fun interrupted(gotBytes: Long, expectedBytes: Long) =
        "The import was interrupted: only ${gotBytes / MEGABYTE} of ${expectedBytes / MEGABYTE} MB arrived. Nothing was kept. " +
            "If the file is still being copied from your PC, wait for that to finish, then pick it again."

    /** Android reports a full disk as an `IOException` whose text names `ENOSPC` / "No space left on device". */
    private fun copyFailure(e: IOException): String {
        val text = e.message.orEmpty()
        return if (text.contains("ENOSPC") || text.contains("No space left", ignoreCase = true)) {
            "$NOT_ENOUGH_STORAGE: the device filled up during the copy. Nothing was kept. Free some space and try again."
        } else {
            "The import was interrupted (${text.ifBlank { e.javaClass.simpleName }}). Nothing was kept. Pick the file again."
        }
    }

    private fun displayName(originalName: String): String {
        val resolved = GgufFileName.resolve(originalName) ?: originalName
        return GgufFileName.displayLabel(resolved)
    }

    companion object {
        const val DEFAULT_CONTEXT_TOKENS = 2048

        /** Every storage refusal starts with this, so a caller can offer to use the file in place instead of copying it. */
        const val NOT_ENOUGH_STORAGE = "Not enough free storage"
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
