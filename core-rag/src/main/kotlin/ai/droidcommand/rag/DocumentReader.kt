package ai.droidcommand.rag

/**
 * Extracts the text of a PDF. The only real implementation lives in `core-rag-android` (Apache PDFBox's
 * Android port); this module stays pure JVM and tests against fakes. Implementations throw
 * [DocumentReadException] for a file they cannot parse (corrupt, encrypted) rather than returning
 * partial or empty text silently.
 */
fun interface PdfTextExtractor {
    fun extract(bytes: ByteArray): String
}

/**
 * Turns an attached file's bytes into text to index, choosing the reader from the content, not the file
 * name: a PDF is recognised by its `%PDF-` header, anything else goes through the strict
 * [PlainTextDocument] reader. Content sniffing means a PDF renamed `.txt` is still read as a PDF and a text
 * file named `.pdf` is not fed to the PDF parser.
 *
 * Limits are enforced before and after extraction: [maxPdfBytes] bounds what is handed to the parser, and
 * [maxTextChars] bounds the text that comes back (a small PDF can decompress to a very large text). A PDF
 * with no extractable text (typically a scan — there is no OCR) is an error, not an empty document, so the
 * user is told rather than left with an attachment that silently answers nothing.
 */
class DocumentReader(
    private val pdf: PdfTextExtractor? = null,
    private val maxPdfBytes: Int = DEFAULT_MAX_PDF_BYTES,
    private val maxTextChars: Int = DEFAULT_MAX_TEXT_CHARS,
) {
    /**
     * Reads [input] (closing it) and then [read]s the bytes. Reads at most [maxPdfBytes] + 1 bytes, so a
     * huge or endless stream (a mis-picked file) cannot exhaust memory before the size check runs.
     */
    fun read(input: java.io.InputStream): String {
        val limit = maxPdfBytes
        val bytes = input.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() <= limit) {
                val n = stream.read(buf, 0, minOf(buf.size, limit + 1 - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        if (bytes.size > limit) throw DocumentReadException("File is larger than ${sizeLabel(limit)}.")
        return read(bytes)
    }

    fun read(bytes: ByteArray): String {
        if (!isPdf(bytes)) return PlainTextDocument.read(bytes)
        val extractor = pdf ?: throw DocumentReadException("PDF files are not supported on this device.")
        if (bytes.size > maxPdfBytes) throw DocumentReadException("PDF is larger than ${sizeLabel(maxPdfBytes)}.")
        val text = extractor.extract(bytes)
        if (text.isBlank()) throw DocumentReadException("This PDF has no extractable text (it may be a scan; there is no OCR).")
        if (text.length > maxTextChars) throw DocumentReadException("The PDF's text is too long to index (over $maxTextChars characters).")
        return text
    }

    companion object {
        const val DEFAULT_MAX_PDF_BYTES = 20 * 1024 * 1024
        const val DEFAULT_MAX_TEXT_CHARS = 2 * 1024 * 1024

        // Per the PDF spec the header may follow up to 1024 bytes of junk; real readers accept that, so do we.
        private val MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

        private fun sizeLabel(bytes: Int) = if (bytes >= 1024 * 1024) "${bytes / (1024 * 1024)} MB" else "${maxOf(bytes / 1024, 1)} KB"

        fun isPdf(bytes: ByteArray): Boolean {
            val limit = minOf(bytes.size, 1024) - MAGIC.size
            for (start in 0..limit) {
                if (MAGIC.indices.all { bytes[start + it] == MAGIC[it] }) return true
            }
            return false
        }
    }
}
