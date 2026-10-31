package ai.droidcommand.llm.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GgufFileNameTest {
    @Test
    fun `a plain or mixed-case gguf name is accepted`() {
        assertTrue(GgufFileName.looksLikeGguf("tinyllama-1.1b.Q4_K_M.gguf"))
        assertTrue(GgufFileName.looksLikeGguf("MODEL.GGUF"))
        assertTrue(GgufFileName.looksLikeGguf("  model.Gguf  "))
    }

    @Test
    fun `a content-URI last segment without the extension is not itself a gguf name`() {
        assertFalse(GgufFileName.looksLikeGguf("msf:123"))
        assertFalse(GgufFileName.looksLikeGguf("document:45"))
        assertFalse(GgufFileName.looksLikeGguf(null))
        assertFalse(GgufFileName.looksLikeGguf(""))
        assertFalse(GgufFileName.looksLikeGguf("notes.txt"))
    }

    @Test
    fun `a percent-encoded path or Downloads document path still resolves`() {
        assertTrue(GgufFileName.looksLikeGguf("primary%3ADownload%2Fmodel.gguf"))
        assertTrue(GgufFileName.looksLikeGguf("/storage/emulated/0/Download/models/phi.gguf"))
        assertEquals(
            "phi.gguf",
            GgufFileName.resolve("msf:123", "primary:Download/phi.gguf"),
        )
        assertEquals(
            "tiny.Q4_K_M.gguf",
            GgufFileName.resolve("content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Ftiny.Q4_K_M.gguf"),
        )
    }

    @Test
    fun `resolve prefers the first candidate that looks like gguf and strips path junk`() {
        assertEquals("model.gguf", GgufFileName.resolve("model.gguf", "other.bin"))
        assertNull(GgufFileName.resolve("msf:123", "document:9", null, ""))
        assertEquals("On-device model", GgufFileName.displayLabel(".gguf"))
        assertEquals("Tiny Llama", GgufFileName.displayLabel("Tiny Llama.gguf"))
    }
}
