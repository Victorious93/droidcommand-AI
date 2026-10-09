package ai.droidcommand.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentReaderTest {
    private val pdfBytes = "%PDF-1.4\nfake".toByteArray()

    @Test
    fun `plain text goes through the strict text reader`() {
        assertEquals("hello", DocumentReader().read("hello".toByteArray()))
        assertFailsWith<DocumentReadException> { DocumentReader().read(byteArrayOf(1, 0, 2)) }
    }

    @Test
    fun `a pdf is detected by content and extracted`() {
        val reader = DocumentReader(PdfTextExtractor { "extracted text" })
        assertEquals("extracted text", reader.read(pdfBytes))
    }

    @Test
    fun `the extractor never sees non-pdf bytes`() {
        val reader = DocumentReader(PdfTextExtractor { error("must not be called") })
        assertEquals("just text", reader.read("just text".toByteArray()))
    }

    @Test
    fun `pdf without an extractor is refused with a clear message`() {
        val e = assertFailsWith<DocumentReadException> { DocumentReader().read(pdfBytes) }
        assertTrue("not supported" in e.message!!)
    }

    @Test
    fun `scanned pdf with no text is an error, not an empty document`() {
        val e = assertFailsWith<DocumentReadException> { DocumentReader(PdfTextExtractor { "  \n " }).read(pdfBytes) }
        assertTrue("no extractable text" in e.message!!)
    }

    @Test
    fun `size limits apply before and after extraction`() {
        var called = false
        val big = DocumentReader(PdfTextExtractor { called = true; "x" }, maxPdfBytes = 8)
        assertFailsWith<DocumentReadException> { big.read(pdfBytes) }
        assertFalse(called, "an oversize pdf must not reach the parser")
        val long = DocumentReader(PdfTextExtractor { "x".repeat(50) }, maxTextChars = 10)
        assertFailsWith<DocumentReadException> { long.read(pdfBytes) }
    }

    @Test
    fun `extractor failures propagate as DocumentReadException`() {
        val reader = DocumentReader(PdfTextExtractor { throw DocumentReadException("encrypted") })
        assertEquals("encrypted", assertFailsWith<DocumentReadException> { reader.read(pdfBytes) }.message)
    }

    @Test
    fun `header after leading junk is still a pdf but a deep mention is not`() {
        assertTrue(DocumentReader.isPdf("junk\n%PDF-1.7".toByteArray()))
        assertFalse(DocumentReader.isPdf((" ".repeat(2000) + "%PDF-").toByteArray()))
        assertFalse(DocumentReader.isPdf(ByteArray(0)))
        assertFalse(DocumentReader.isPdf("%PD".toByteArray()))
    }

    @Test
    fun `stream reading is bounded and closes the stream`() {
        var closed = false
        val endless = object : java.io.InputStream() {
            override fun read() = 'a'.code
            override fun close() {
                closed = true
            }
        }
        val e = assertFailsWith<DocumentReadException> { DocumentReader(maxPdfBytes = 1000).read(endless) }
        assertTrue("larger" in e.message!!)
        assertTrue(closed)
        assertEquals("hi", DocumentReader().read(java.io.ByteArrayInputStream("hi".toByteArray())))
    }
}
