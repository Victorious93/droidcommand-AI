package ai.droidcommand.codeedit

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnifiedDiffTest {
    private fun numbered(count: Int, replace: Map<Int, String> = emptyMap()) =
        (1..count).joinToString("") { (replace[it] ?: "l$it") + "\n" }

    @Test
    fun `identical texts produce an empty diff`() {
        assertEquals("", UnifiedDiff.generate("a/f", "b/f", "same\n", "same\n"))
    }

    @Test
    fun `a single changed line gets three lines of context on each side`() {
        val diff = UnifiedDiff.generate("a/f", "b/f", numbered(10), numbered(10, mapOf(5 to "L5")))
        val expected = """
            |--- a/f
            |+++ b/f
            |@@ -2,7 +2,7 @@
            | l2
            | l3
            | l4
            |-l5
            |+L5
            | l6
            | l7
            | l8
            |
        """.trimMargin()
        assertEquals(expected, diff)
    }

    @Test
    fun `context is clipped at the start and end of the file`() {
        val diff = UnifiedDiff.generate("a/f", "b/f", numbered(3), numbered(3, mapOf(1 to "X")))
        assertTrue(diff.contains("@@ -1,3 +1,3 @@"), diff)
    }

    @Test
    fun `inserting into an empty file and deleting a whole file use empty-range headers`() {
        assertEquals("--- a/f\n+++ b/f\n@@ -0,0 +1 @@\n+a\n", UnifiedDiff.generate("a/f", "b/f", "", "a\n"))
        assertEquals("--- a/f\n+++ b/f\n@@ -1 +0,0 @@\n-a\n", UnifiedDiff.generate("a/f", "b/f", "a\n", ""))
    }

    @Test
    fun `a missing trailing newline is marked on the right side`() {
        val diff = UnifiedDiff.generate("a/f", "b/f", "a", "b")
        assertEquals(
            "--- a/f\n+++ b/f\n@@ -1 +1 @@\n-a\n\\ No newline at end of file\n+b\n\\ No newline at end of file\n",
            diff,
        )
    }

    @Test
    fun `adding only a trailing newline is a real change`() {
        val diff = UnifiedDiff.generate("a/f", "b/f", "a", "a\n")
        assertEquals("--- a/f\n+++ b/f\n@@ -1 +1 @@\n-a\n\\ No newline at end of file\n+a\n", diff)
    }

    @Test
    fun `distant changes become separate hunks and close changes merge`() {
        val far = UnifiedDiff.generate("a/f", "b/f", numbered(40), numbered(40, mapOf(3 to "A", 37 to "B")))
        assertEquals(2, far.lines().count { it.startsWith("@@") }, far)

        // A gap of exactly 2 * context (6) unchanged lines still merges; 7 does not.
        val merged = UnifiedDiff.generate("a/f", "b/f", numbered(20), numbered(20, mapOf(5 to "A", 12 to "B")))
        assertEquals(1, merged.lines().count { it.startsWith("@@") }, merged)
        val split = UnifiedDiff.generate("a/f", "b/f", numbered(20), numbered(20, mapOf(5 to "A", 13 to "B")))
        assertEquals(2, split.lines().count { it.startsWith("@@") }, split)
    }

    @Test
    fun `an oversized middle degrades to a correct delete-all insert-all diff`() {
        val old = (1..1600).joinToString("") { "old$it\n" }
        val new = (1..1600).joinToString("") { "new$it\n" }
        val diff = UnifiedDiff.generate("a/f", "b/f", old, new)
        assertEquals(new, applyUnifiedDiff(old, diff))
    }

    @Test
    fun `randomized diffs always apply back to the new text`() {
        val random = Random(20260929)
        val alphabet = listOf("a", "b", "c", "d", "  e", "")
        repeat(500) { round ->
            val old = randomText(random, alphabet)
            val new = mutate(random, old, alphabet)
            val diff = UnifiedDiff.generate("a/f", "b/f", old, new)
            if (old == new) {
                assertEquals("", diff)
            } else {
                assertEquals(new, applyUnifiedDiff(old, diff), "round $round\nold=<$old>\nnew=<$new>\ndiff=\n$diff")
            }
        }
    }

    private fun randomText(random: Random, alphabet: List<String>): String {
        val lines = List(random.nextInt(0, 30)) { alphabet[random.nextInt(alphabet.size)] }
        val body = lines.joinToString("\n")
        return if (lines.isNotEmpty() && random.nextBoolean()) body + "\n" else body
    }

    private fun mutate(random: Random, text: String, alphabet: List<String>): String {
        val lines = text.split("\n").toMutableList()
        repeat(random.nextInt(0, 5)) {
            when (random.nextInt(3)) {
                0 -> if (lines.isNotEmpty()) lines.removeAt(random.nextInt(lines.size))
                1 -> lines.add(random.nextInt(lines.size + 1), alphabet[random.nextInt(alphabet.size)])
                else -> if (lines.isNotEmpty()) lines[random.nextInt(lines.size)] = alphabet[random.nextInt(alphabet.size)]
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * A deliberately independent, minimal unified-diff applier: it re-derives line positions from the
     * hunk headers and checks every context/deleted line against the old text, so a wrong header or a
     * wrong line in the diff makes the round trip fail.
     */
    private fun applyUnifiedDiff(old: String, diff: String): String {
        // Independent of the production splitter on purpose: each element keeps its own "\n" if it had one.
        val oldLines = Regex("[^\n]*\n|[^\n]+").findAll(old).map { it.value }.toList()
        val diffLines = diff.split("\n").let { if (it.last().isEmpty()) it.dropLast(1) else it }
        val out = StringBuilder()
        var oldIndex = 0
        var i = 2 // skip the two header lines
        while (i < diffLines.size) {
            val header = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@$""").matchEntire(diffLines[i])
                ?: error("Bad hunk header '${diffLines[i]}'")
            val oldStart = header.groupValues[1].toInt()
            val oldCount = header.groupValues[2].ifEmpty { "1" }.toInt()
            val hunkFirstIndex = if (oldCount == 0) oldStart else oldStart - 1
            while (oldIndex < hunkFirstIndex) out.append(oldLines[oldIndex++])
            i++
            var consumed = 0
            while (i < diffLines.size && !diffLines[i].startsWith("@@")) {
                val line = diffLines[i]
                val noNewlineNext = i + 1 < diffLines.size && diffLines[i + 1].startsWith("\\ No newline")
                val text = line.substring(1) + if (noNewlineNext) "" else "\n"
                when (line[0]) {
                    ' ' -> {
                        assertEquals(oldLines[oldIndex], text, "context mismatch")
                        out.append(text)
                        oldIndex++
                        consumed++
                    }
                    '-' -> {
                        assertEquals(oldLines[oldIndex], text, "deleted-line mismatch")
                        oldIndex++
                        consumed++
                    }
                    '+' -> out.append(text)
                    '\\' -> Unit
                    else -> error("Bad diff line '$line'")
                }
                i++
            }
            assertEquals(oldCount, consumed, "hunk old-line count disagrees with its header")
        }
        while (oldIndex < oldLines.size) out.append(oldLines[oldIndex++])
        return out.toString()
    }
}
