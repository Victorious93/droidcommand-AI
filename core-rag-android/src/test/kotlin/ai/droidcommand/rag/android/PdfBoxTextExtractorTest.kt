package ai.droidcommand.rag.android

import ai.droidcommand.rag.DocumentReadException
import ai.droidcommand.rag.DocumentReader
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Parses a small, valid PDF built byte-by-byte here (so the fixture is reviewable and needs no PDF writer
 * library). Covers text extraction, multiple pages, the DocumentReader hookup, and failure modes. Real-world
 * PDFs (fonts, columns, scans) are not covered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfBoxTextExtractorTest {
    private val extractor get() = PdfBoxTextExtractor(ApplicationProvider.getApplicationContext())

    /** One Helvetica line per page; offsets in the xref table are computed, not guessed. */
    private fun pdf(vararg pageLines: String): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(n: Int, body: String) {
            offsets += out.size()
            w("$n 0 obj\n$body\nendobj\n")
        }
        val n = pageLines.size
        // Objects: 1 catalog, 2 pages, 3 font, then per page: page object, content stream.
        w("%PDF-1.4\n")
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [${(0 until n).joinToString(" ") { "${4 + it * 2} 0 R" }}] /Count $n >>")
        obj(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        pageLines.forEachIndexed { i, line ->
            val content = "BT /F1 12 Tf 72 700 Td (${line.replace("(", "\\(").replace(")", "\\)")}) Tj ET"
            obj(4 + i * 2, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents ${5 + i * 2} 0 R /Resources << /Font << /F1 3 0 R >> >> >>")
            obj(5 + i * 2, "<< /Length ${content.length} >>\nstream\n$content\nendstream")
        }
        val xref = out.size()
        w("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { w("%010d 00000 n \n".format(it)) }
        w("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    @Test
    fun `extracts text from every page in order`() {
        val text = extractor.extract(pdf("The capital of France is Paris.", "Second page mentions Lyon."))
        val paris = text.indexOf("The capital of France is Paris.")
        val lyon = text.indexOf("Second page mentions Lyon.")
        assertTrue(paris >= 0 && lyon > paris, "got: $text")
    }

    @Test
    fun `works through DocumentReader by content sniffing`() {
        val reader = DocumentReader(extractor)
        assertTrue("Paris" in reader.read(pdf("Paris is nice")))
        assertEquals("plain", reader.read("plain".toByteArray()))
    }

    @Test
    fun `a pdf with a blank page is reported as having no text`() {
        val e = assertFailsWith<DocumentReadException> { DocumentReader(extractor).read(pdf(" ")) }
        assertTrue("no extractable text" in e.message!!)
    }

    @Test
    fun `garbage after a pdf header becomes a clean DocumentReadException`() {
        val e = assertFailsWith<DocumentReadException> { extractor.extract("%PDF-1.4\nthis is not a pdf".toByteArray()) }
        assertTrue("could not be read" in e.message!!, e.message)
    }
}
