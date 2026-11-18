package ai.droidcommand.app.ui.models

import ai.droidcommand.llm.local.RamFitVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsPresentationTest {
    private data class Row(val name: String, val verdict: RamFitVerdict, val size: Long)

    private fun order(vararg rows: Row) =
        ModelsPresentation.orderedByFit(rows.toList(), { it.verdict }, { it.size }).map { it.name }

    @Test
    fun `sizes read in MB below one GiB and GB above`() {
        assertEquals("0 MB", ModelsPresentation.sizeLabel(0))
        assertEquals("468 MB", ModelsPresentation.sizeLabel(468L * 1024 * 1024))
        assertEquals("1023 MB", ModelsPresentation.sizeLabel(1023L * 1024 * 1024))
        assertEquals("1.0 GB", ModelsPresentation.sizeLabel(1024L * 1024 * 1024))
        assertEquals("2.5 GB", ModelsPresentation.sizeLabel((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `a negative size is shown as zero rather than a negative number`() {
        assertEquals("0 MB", ModelsPresentation.sizeLabel(-5))
    }

    @Test
    fun `counts are short and never round up into four digits`() {
        assertEquals("0", ModelsPresentation.countLabel(0))
        assertEquals("950", ModelsPresentation.countLabel(950))
        assertEquals("1K", ModelsPresentation.countLabel(1_000))
        assertEquals("1.2K", ModelsPresentation.countLabel(1_234))
        assertEquals("12K", ModelsPresentation.countLabel(12_345))
        assertEquals("999K", ModelsPresentation.countLabel(999_499))
        assertEquals("1M", ModelsPresentation.countLabel(999_500))
        assertEquals("3.4M", ModelsPresentation.countLabel(3_400_000))
        assertEquals("2.1B", ModelsPresentation.countLabel(2_100_000_000))
    }

    @Test
    fun `models that fit come first, then tight, then too big, smallest first in each group`() {
        val result = order(
            Row("huge", RamFitVerdict.TOO_BIG, 9_000),
            Row("tight-small", RamFitVerdict.TIGHT, 1_000),
            Row("fits-big", RamFitVerdict.FITS, 800),
            Row("fits-small", RamFitVerdict.FITS, 300),
            Row("tight-big", RamFitVerdict.TIGHT, 2_000),
        )
        assertEquals(listOf("fits-small", "fits-big", "tight-small", "tight-big", "huge"), result)
    }

    @Test
    fun `ordering keeps equal rows in their original order`() {
        val result = order(
            Row("a", RamFitVerdict.FITS, 100),
            Row("b", RamFitVerdict.FITS, 100),
            Row("c", RamFitVerdict.FITS, 100),
        )
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `every fit verdict has distinct words and a tone that is not the same as another verdict`() {
        val verdicts = RamFitVerdict.entries
        assertEquals(verdicts.size, verdicts.map { ModelsPresentation.fitText(it) }.toSet().size)
        assertEquals(verdicts.size, verdicts.map { ModelsPresentation.fitTone(it) }.toSet().size)
        assertEquals("Too big", ModelsPresentation.fitText(RamFitVerdict.TOO_BIG))
    }

    @Test
    fun `suggested searches never include a family the free-license search would refuse`() {
        val lowered = ModelsPresentation.SEARCH_SUGGESTIONS.map { it.lowercase() }
        assertFalse(lowered.any { it == "llama" || it == "gemma" })
    }

    @Test
    fun `a repo id splits into owner and name`() {
        assertEquals("Qwen" to "Qwen2.5-0.5B-Instruct-GGUF", ModelsPresentation.splitRepoId("Qwen/Qwen2.5-0.5B-Instruct-GGUF"))
    }

    @Test
    fun `an id without an owner, or with an empty half, is kept whole`() {
        assertEquals(null to "justaname", ModelsPresentation.splitRepoId("justaname"))
        assertEquals(null to "/name", ModelsPresentation.splitRepoId("/name"))
        assertEquals(null to "owner/", ModelsPresentation.splitRepoId("owner/"))
    }

    @Test
    fun `only the Q4_K_M quant is tagged as a common pick`() {
        assertTrue(ModelsPresentation.isCommonPick("Q4_K_M"))
        assertTrue(ModelsPresentation.isCommonPick("q4_k_m"))
        assertFalse(ModelsPresentation.isCommonPick("Q8_0"))
        assertFalse(ModelsPresentation.isCommonPick(null))
    }

    @Test
    fun `a blank query is not searchable and a real one is trimmed`() {
        assertNull(ModelsPresentation.searchable(""))
        assertNull(ModelsPresentation.searchable("   "))
        assertEquals("qwen 0.5b", ModelsPresentation.searchable("  qwen 0.5b "))
    }
}
