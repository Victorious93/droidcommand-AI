package ai.droidcommand.rag.android

import ai.droidcommand.rag.DocumentReadException
import ai.droidcommand.rag.PdfTextExtractor
import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * [PdfTextExtractor] backed by pdfbox-android (Apache-2.0). Text only: no OCR, so a scanned PDF yields no
 * text (the caller turns that into a message). Encrypted PDFs are refused, not attempted with an empty
 * password. Parsing runs with PDFBox's default temp-file-free memory mode on the already-size-limited
 * bytes the caller hands in.
 *
 * Any parser failure becomes a [DocumentReadException]; PDFBox's own messages can echo file content, so
 * only a generic message is surfaced.
 */
class PdfBoxTextExtractor(context: Context) : PdfTextExtractor {
    init {
        // Loads PDFBox's bundled font/glyph resources from the AAR's assets. Safe to call repeatedly.
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    override fun extract(bytes: ByteArray): String {
        val doc = try {
            PDDocument.load(bytes)
        } catch (e: Exception) {
            throw DocumentReadException("This PDF could not be read (it may be corrupt or password-protected).")
        }
        doc.use {
            if (it.isEncrypted) throw DocumentReadException("This PDF is encrypted; remove the password first.")
            return try {
                PDFTextStripper().getText(it)
            } catch (e: Exception) {
                throw DocumentReadException("Text could not be extracted from this PDF.")
            }
        }
    }
}
