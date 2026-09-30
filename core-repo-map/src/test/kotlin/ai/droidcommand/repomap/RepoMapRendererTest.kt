package ai.droidcommand.repomap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RM-7: the rendered map never exceeds its budget, and says what it left out. */
class RepoMapRendererTest {
    private fun ranked(path: String, symbolCount: Int, rank: Double = 0.1) =
        FileRank(path, rank, (1..symbolCount).map { RankedSymbol(Definition("symbol$it", "fun", it * 10), 1.0 / it) })

    private fun corpus() = (1..20).map { ranked("src/File$it.kt", 30, 1.0 / it) }

    @Test
    fun `RM-7 output never exceeds the budget`() {
        val files = corpus()
        for (budget in (1..600) + listOf(800, 1000, 2500, 5000, 20_000, 100_000)) {
            val out = RepoMapRenderer.render(files, budget).text
            assertTrue(out.length <= budget, "budget $budget produced ${out.length} chars")
        }
    }

    @Test
    fun `RM-7 shown plus omitted accounts for every file with symbols`() {
        val files = corpus() + ranked("src/Empty.kt", 0)
        for (budget in listOf(50, 300, 1000, 4000, 100_000)) {
            val map = RepoMapRenderer.render(files, budget)
            assertEquals(20, map.filesShown + map.filesOmitted, "budget $budget")
        }
    }

    @Test
    fun `RM-7 a budget too small for one block renders nothing`() {
        val map = RepoMapRenderer.render(corpus(), 10)
        assertEquals("", map.text)
        assertEquals(0, map.filesShown)
        assertEquals(20, map.filesOmitted)
    }

    @Test
    fun `RM-7 a large budget shows everything without an omission note`() {
        val map = RepoMapRenderer.render(corpus(), 1_000_000, maxSymbolsPerFile = 30)
        assertEquals(20, map.filesShown)
        assertEquals(0, map.filesOmitted)
        assertFalse("not shown" in map.text)
    }

    @Test
    fun `RM-7 omitted files are announced when the note fits`() {
        val map = RepoMapRenderer.render(corpus(), 1500)
        assertTrue(map.filesOmitted > 0)
        assertTrue("${map.filesOmitted} more file(s) not shown" in map.text, map.text.takeLast(120))
    }

    @Test
    fun `RM-7 an incomplete map always says so, at every budget`() {
        val files = corpus()
        for (budget in 1..4000 step 3) {
            val map = RepoMapRenderer.render(files, budget)
            if (map.text.isNotEmpty() && map.filesOmitted > 0) {
                assertTrue("${map.filesOmitted} more file(s) not shown" in map.text, "budget $budget: ${map.text.takeLast(80)}")
            }
            assertTrue(map.text.length <= budget, "budget $budget")
        }
    }

    @Test
    fun `RM-7 most relevant files come first and keep their rank order`() {
        val text = RepoMapRenderer.render(listOf(ranked("first.kt", 2), ranked("second.kt", 2)), 10_000).text
        assertTrue(text.indexOf("first.kt") in 0 until text.indexOf("second.kt"))
    }

    @Test
    fun `RM-7 symbols within a file are listed in line order and capped with a count`() {
        val file = FileRank(
            "a.kt",
            1.0,
            listOf(
                RankedSymbol(Definition("late", "fun", 30), 9.0),
                RankedSymbol(Definition("early", "fun", 5), 1.0),
                RankedSymbol(Definition("middle", "fun", 15), 5.0),
                RankedSymbol(Definition("dropped", "fun", 1), 0.1),
            ),
        )
        val text = RepoMapRenderer.render(listOf(file), 10_000, maxSymbolsPerFile = 3).text
        assertEquals("a.kt:\n  5: fun early\n  15: fun middle\n  30: fun late\n  ... +1 more", text)
    }

    @Test
    fun `RM-7 a block that does not fit is retried with fewer symbols`() {
        val one = ranked("a.kt", 30)
        val full = RepoMapRenderer.render(listOf(one), 100_000).text
        val squeezed = RepoMapRenderer.render(listOf(one), full.length / 4)
        assertEquals(1, squeezed.filesShown)
        assertTrue(squeezed.text.length <= full.length / 4)
        assertTrue("more" in squeezed.text)
    }

    @Test
    fun `RM-7 focus files are marked in their header`() {
        val text = RepoMapRenderer.render(listOf(ranked("a.kt", 1), ranked("b.kt", 1)), 10_000, focusFiles = setOf("a.kt")).text
        assertTrue(text.startsWith("a.kt (focus):"), text)
        assertTrue("\nb.kt:" in text)
    }

    @Test
    fun `RM-7 files without symbols are not shown and not counted as omitted`() {
        val map = RepoMapRenderer.render(listOf(ranked("empty.kt", 0), ranked("full.kt", 1)), 10_000)
        assertEquals(1, map.filesShown)
        assertEquals(0, map.filesOmitted)
        assertFalse("empty.kt" in map.text)
    }

    @Test
    fun `RM-7 a non-positive budget is rejected`() {
        assertFailsWith<IllegalArgumentException> { RepoMapRenderer.render(corpus(), 0) }
        assertFailsWith<IllegalArgumentException> { RepoMapRenderer.render(corpus(), -5) }
    }
}
