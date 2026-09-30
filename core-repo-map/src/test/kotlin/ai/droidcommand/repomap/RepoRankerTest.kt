package ai.droidcommand.repomap

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RM-5 (ranking properties) and RM-6 (task personalization), on synthetic indexes so each property is isolated. */
class RepoRankerTest {
    private fun file(path: String, defs: List<String> = emptyList(), refs: Map<String, Int> = emptyMap()) =
        IndexedFile(path, ParsedFile(defs.mapIndexed { i, name -> Definition(name, "fun", i + 1) }, refs))

    private fun index(vararg files: IndexedFile) = RepoIndex(files.toList(), 0, false)

    private fun List<FileRank>.rankOf(path: String) = first { it.path == path }.rank

    // ------------------------------------------------------------------ RM-5

    @Test
    fun `RM-5 an empty index ranks nothing`() {
        assertTrue(RepoRanker.rank(index()).isEmpty())
    }

    @Test
    fun `RM-5 the file everyone depends on ranks first`() {
        val ranked = RepoRanker.rank(
            index(
                file("core.kt", listOf("SharedCore")),
                file("a.kt", refs = mapOf("SharedCore" to 1)),
                file("b.kt", refs = mapOf("SharedCore" to 1)),
                file("c.kt", refs = mapOf("SharedCore" to 1)),
            ),
        )
        assertEquals("core.kt", ranked.first().path)
    }

    @Test
    fun `RM-5 ranking is deterministic regardless of the order files are supplied in`() {
        val files = listOf(
            file("core.kt", listOf("SharedCore")),
            file("a.kt", listOf("AlphaPart"), mapOf("SharedCore" to 2)),
            file("b.kt", listOf("BetaPart"), mapOf("SharedCore" to 1, "AlphaPart" to 3)),
            file("c.kt", refs = mapOf("BetaPart" to 1)),
        )
        val first = RepoRanker.rank(RepoIndex(files, 0, false))
        val second = RepoRanker.rank(RepoIndex(files.reversed(), 0, false))
        assertEquals(first.map { it.path }, second.map { it.path })
        assertEquals(first.map { it.rank }, second.map { it.rank })
    }

    @Test
    fun `RM-5 ranks form a probability distribution`() {
        val ranked = RepoRanker.rank(
            index(
                file("core.kt", listOf("SharedCore")),
                file("a.kt", refs = mapOf("SharedCore" to 1)),
                file("island.kt", listOf("LonelyThing")),
            ),
        )
        assertTrue(abs(ranked.sumOf { it.rank } - 1.0) < 1e-6, "sum was ${ranked.sumOf { it.rank }}")
    }

    @Test
    fun `RM-5 files with no edges tie and are ordered by path`() {
        val ranked = RepoRanker.rank(index(file("c.kt", listOf("Cc")), file("a.kt", listOf("Aa")), file("b.kt", listOf("Bb"))))
        assertEquals(listOf("a.kt", "b.kt", "c.kt"), ranked.map { it.path })
        assertTrue(ranked.all { abs(it.rank - ranked[0].rank) < 1e-12 })
    }

    @Test
    fun `RM-5 a file using its own definition gains nothing`() {
        val ranked = RepoRanker.rank(
            index(
                file("self.kt", listOf("SelfReferential"), mapOf("SelfReferential" to 50)),
                file("plain.kt", listOf("PlainThing")),
            ),
        )
        assertTrue(abs(ranked.rankOf("self.kt") - ranked.rankOf("plain.kt")) < 1e-12)
    }

    @Test
    fun `RM-5 a name declared in more than eight files is ignored as evidence`() {
        fun world(definers: Int): List<FileRank> {
            val defs = (1..definers).map { file("d$it.kt", listOf("CommonName")) }
            return RepoRanker.rank(RepoIndex(defs + file("user.kt", refs = mapOf("CommonName" to 5)), 0, false))
        }

        val eight = world(8)
        assertTrue(eight.rankOf("d1.kt") > eight.rankOf("user.kt"), "eight definers still count")

        val nine = world(9)
        assertTrue(abs(nine.rankOf("d1.kt") - nine.rankOf("user.kt")) < 1e-12, "nine definers are too ambiguous to count")
    }

    @Test
    fun `RM-5 a name declared in two files gives each less than a name declared in one`() {
        val ranked = RepoRanker.rank(
            index(
                file("user.kt", refs = mapOf("UniqueName" to 1, "SharedName" to 1)),
                file("x.kt", listOf("UniqueName")),
                file("y.kt", listOf("SharedName")),
                file("z.kt", listOf("SharedName")),
            ),
        )
        assertTrue(ranked.rankOf("x.kt") > ranked.rankOf("y.kt"))
        assertTrue(abs(ranked.rankOf("y.kt") - ranked.rankOf("z.kt")) < 1e-12)
    }

    @Test
    fun `RM-5 repeated use counts, but only by its square root`() {
        val ranked = RepoRanker.rank(
            index(
                file("a.kt", listOf("HeavyTarget")),
                file("b.kt", listOf("LightTarget")),
                file("user.kt", refs = mapOf("HeavyTarget" to 100, "LightTarget" to 1)),
            ),
        )
        val heavy = ranked.first { it.path == "a.kt" }.symbols.single().score
        val light = ranked.first { it.path == "b.kt" }.symbols.single().score
        assertTrue(abs(heavy / light - 10.0) < 1e-6, "100 uses vs 1 use should weigh 10:1, got ${heavy / light}")
    }

    @Test
    fun `RM-5 symbols are ordered by how much of the ranking flows into them`() {
        val ranked = RepoRanker.rank(
            index(
                file("core.kt", listOf("coldThing", "hotThing")), // cold is declared first
                file("a.kt", refs = mapOf("hotThing" to 1)),
                file("b.kt", refs = mapOf("hotThing" to 1)),
                file("c.kt", refs = mapOf("hotThing" to 1, "coldThing" to 1)),
            ),
        )
        assertEquals(listOf("hotThing", "coldThing"), ranked.first { it.path == "core.kt" }.symbols.map { it.definition.name })
    }

    @Test
    fun `RM-5 a name declared twice in one file yields one ranked symbol`() {
        val ranked = RepoRanker.rank(index(file("o.kt", listOf("overloaded", "overloaded", "other"))))
        assertEquals(listOf("overloaded", "other"), ranked.single().symbols.map { it.definition.name })
    }

    @Test
    fun `RM-5 Words splits identifiers and prose`() {
        assertEquals(listOf("parse", "http", "request", "v2"), Words.of("parseHTTPRequest_v2"))
        assertEquals(listOf("snake", "case", "name"), Words.of("snake_case_name"))
        assertEquals(setOf("parser"), Words.terms("Fix the parser"))
        assertEquals(emptySet(), Words.terms("a to of"))
    }

    // ------------------------------------------------------------------ RM-6

    private fun twoClusters() = index(
        file("one/core.kt", listOf("AlphaThing")),
        file("one/user.kt", refs = mapOf("AlphaThing" to 1)),
        file("two/core.kt", listOf("BetaThing")),
        file("two/user.kt", refs = mapOf("BetaThing" to 1)),
    )

    @Test
    fun `RM-6 without a task two symmetric clusters tie and path order decides`() {
        val ranked = RepoRanker.rank(twoClusters())
        assertEquals("one/core.kt", ranked.first().path)
        assertTrue(abs(ranked.rankOf("one/core.kt") - ranked.rankOf("two/core.kt")) < 1e-12)
    }

    @Test
    fun `RM-6 the task decides which cluster wins`() {
        assertEquals("one/core.kt", RepoRanker.rank(twoClusters(), RankingRequest(task = "rework alpha handling")).first().path)
        assertEquals("two/core.kt", RepoRanker.rank(twoClusters(), RankingRequest(task = "beta is broken")).first().path)
    }

    @Test
    fun `RM-6 a path word in the task lifts that file`() {
        val ranked = RepoRanker.rank(
            // the matching file sorts last, so only the path bonus (not the path tie-break) can put it first
            index(file("net/screen.kt", listOf("Aa")), file("ui/http_client.kt", listOf("Bb"))),
            RankingRequest(task = "the http client times out"),
        )
        assertEquals("ui/http_client.kt", ranked.first().path)
    }

    @Test
    fun `RM-6 focus files lift their neighbourhood`() {
        val ranked = RepoRanker.rank(twoClusters(), RankingRequest(focusFiles = setOf("two/user.kt")))
        assertEquals(setOf("two/core.kt", "two/user.kt"), ranked.take(2).map { it.path }.toSet())
    }

    @Test
    fun `RM-6 focus paths tolerate a leading dot-slash and backslashes`() {
        val a = RepoRanker.rank(twoClusters(), RankingRequest(focusFiles = setOf("two/user.kt")))
        val b = RepoRanker.rank(twoClusters(), RankingRequest(focusFiles = setOf("./two\\user.kt")))
        assertEquals(a.map { it.path }, b.map { it.path })
    }

    @Test
    fun `RM-6 a symbol whose name matches the task is listed first`() {
        val idx = index(file("core.kt", listOf("alpha_one", "beta_two")))
        assertEquals(listOf("alpha_one", "beta_two"), RepoRanker.rank(idx).single().symbols.map { it.definition.name })
        assertEquals(
            listOf("beta_two", "alpha_one"),
            RepoRanker.rank(idx, RankingRequest(task = "look at beta")).single().symbols.map { it.definition.name },
        )
    }

    @Test
    fun `RM-6 a task made only of filler words changes nothing`() {
        val plain = RepoRanker.rank(twoClusters())
        val filler = RepoRanker.rank(twoClusters(), RankingRequest(task = "please fix the code"))
        assertEquals(plain.map { it.path }, filler.map { it.path })
        assertEquals(plain.map { it.rank }, filler.map { it.rank })
    }
}
