package ai.droidcommand.rag

/** A document the user has attached. [docId] is also the label shown to the model next to each excerpt. */
data class DocumentInfo(val docId: String, val name: String, val chunkCount: Int, val addedAtMillis: Long)

/**
 * The list of attached documents, kept alongside the vector index. [delete] must remove the document's
 * chunks as well as its listing entry, so the two cannot disagree after a successful call.
 */
interface DocumentLibrary {
    fun list(): List<DocumentInfo>

    fun save(info: DocumentInfo)

    fun delete(docId: String)
}

sealed class AttachResult {
    data class Attached(val info: DocumentInfo) : AttachResult()

    /** [message] is safe to show the user. Nothing was indexed or listed. */
    data class Failed(val message: String) : AttachResult()
}

/**
 * Reads, embeds, indexes and lists attached documents — the logic behind an "attach document" button, kept
 * free of Android types. Blocks (embedding is slow): call it off the main thread.
 *
 * A document's id is its sanitised file name, so attaching a file with the same name replaces the earlier
 * one rather than adding a duplicate. The name ends up in the prompt next to each excerpt, so control
 * characters and newlines are stripped and it is length-capped — a hostile file name must not be able to
 * inject a line into the prompt.
 *
 * A failed attach leaves no half-indexed document behind: if indexing or listing throws, the document is
 * deleted again. (A replaced earlier document of the same name is not restored.)
 */
class DocumentAttacher(
    private val reader: DocumentReader,
    private val retriever: DocumentRetriever,
    private val library: DocumentLibrary,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun attach(fileName: String, bytes: ByteArray): AttachResult = attach(fileName) { reader.read(bytes) }

    /** Reads [input] with the reader's size bound (and closes it), so an oversized or endless stream is refused early. */
    fun attach(fileName: String, input: java.io.InputStream): AttachResult = attach(fileName) { reader.read(input) }

    private fun attach(fileName: String, readText: () -> String): AttachResult {
        val text = try {
            readText()
        } catch (e: DocumentReadException) {
            return AttachResult.Failed(e.message ?: "Could not read the file.")
        } catch (e: java.io.IOException) {
            return AttachResult.Failed("The file could not be read: ${e.message ?: "I/O error"}")
        }
        val docId = sanitizeName(fileName)
        return try {
            val chunks = retriever.index(docId, text)
            if (chunks == 0) {
                // index() already dropped any earlier document of this name; drop its listing too.
                library.delete(docId)
                AttachResult.Failed("The file has no text to index.")
            } else {
                val info = DocumentInfo(docId, docId, chunks, clock())
                library.save(info)
                AttachResult.Attached(info)
            }
        } catch (e: Exception) {
            runCatching { library.delete(docId) }
            AttachResult.Failed("Could not index the document: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun list(): List<DocumentInfo> = library.list()

    fun remove(docId: String) = library.delete(docId)

    companion object {
        const val MAX_NAME_CHARS = 80

        fun sanitizeName(raw: String): String {
            val cleaned = raw.map { if (it.isISOControl() || it == ' ' || it == ' ') ' ' else it }
                .joinToString("")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_NAME_CHARS)
                .trim()
            return cleaned.ifEmpty { "document" }
        }
    }
}
