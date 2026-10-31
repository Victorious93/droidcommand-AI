package ai.droidcommand.llm.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatModelCatalogTest {
    @Test
    fun `every curated entry is downloadable, apache-2, and has publisher integrity fields`() {
        assertEquals(4, ChatModelCatalog.all.size)
        assertEquals(ChatModelCatalog.all.map { it.metadata }, ChatModelCatalog.entries)
        for (entry in ChatModelCatalog.all) {
            assertNull(ModelDownloadPolicy.problem(entry.metadata), entry.metadata.id)
            assertEquals("apache-2.0", entry.license)
            assertTrue(HuggingFaceLicense.isFree(entry.license), entry.metadata.id)
            assertTrue(entry.metadata.sha256.matches(Regex("^[0-9a-f]{64}$")), entry.metadata.id)
            assertTrue((entry.metadata.sizeBytes ?: 0L) > 0L, entry.metadata.id)
            assertTrue(entry.metadata.url!!.contains("/resolve/"), entry.metadata.id)
            assertTrue(entry.repoId.contains('/'))
            assertEquals(entry.quant, GgufQuant.fromFileName(entry.metadata.fileName))
        }
    }

    @Test
    fun `find is by id and unknown ids are null`() {
        assertEquals(ChatModelCatalog.SMOLLM2_360M_INSTRUCT_Q8_0.metadata, ChatModelCatalog.find("smollm2-360m-instruct-q8_0"))
        assertNull(ChatModelCatalog.find("not-a-model"))
        assertEquals(ChatModelCatalog.QWEN25_05B_INSTRUCT_Q4_K_M, ChatModelCatalog.findEntry("qwen2.5-0.5b-instruct-q4_k_m"))
    }

    @Test
    fun `ram-fit math ranks the curated files on a 4 GB device`() {
        val total = 4L * 1024 * 1024 * 1024
        val available = 2L * 1024 * 1024 * 1024
        val smol = ChatModelCatalog.SMOLLM2_360M_INSTRUCT_Q8_0.metadata
        val fit = RamFit.assess(smol.sizeBytes!!, smol.contextTokens, total, available)
        assertEquals(RamFitVerdict.FITS, fit.verdict)
        val huge = RamFit.assess(8L * 1024 * 1024 * 1024, 2048, total, available)
        assertEquals(RamFitVerdict.TOO_BIG, huge.verdict)
    }
}
