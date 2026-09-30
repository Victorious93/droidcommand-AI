package ai.droidcommand.codeedit

/**
 * Renders the difference between two texts in the unified diff format
 * (the public `diff -u` convention: `---`/`+++` headers, `@@ -a,b +c,d @@`
 * hunks, ` `/`-`/`+` line prefixes, `\ No newline at end of file` marker).
 *
 * Strategy: strip the common leading and trailing lines first — an edit
 * usually touches a small region of a large file — then compute a longest
 * common subsequence on only the differing middle. If that middle is so
 * large that the LCS table would be wasteful ([MAX_LCS_CELLS]), the middle
 * degrades to "delete all old lines, add all new lines": still a correct,
 * applicable diff, just not a minimal one.
 */
object UnifiedDiff {
    private const val MAX_LCS_CELLS = 2_000_000L

    private enum class Kind { SAME, DELETE, INSERT }

    private class Op(val kind: Kind, val line: String)

    /** Returns the diff, or an empty string when [oldText] and [newText] are identical. */
    fun generate(oldLabel: String, newLabel: String, oldText: String, newText: String, context: Int = 3): String {
        require(context >= 0) { "context must be >= 0, got $context" }
        if (oldText == newText) return ""

        val ops = script(splitKeepingTerminators(oldText), splitKeepingTerminators(newText))
        val changeIndexes = ops.indices.filter { ops[it].kind != Kind.SAME }
        if (changeIndexes.isEmpty()) return ""

        // Group change indexes into hunks: two changes belong to the same hunk when the
        // unchanged gap between them is small enough for their context windows to touch.
        val groups = mutableListOf<IntRange>()
        var groupStart = changeIndexes.first()
        var groupEnd = groupStart
        for (index in changeIndexes.drop(1)) {
            if (index - groupEnd - 1 <= 2 * context) {
                groupEnd = index
            } else {
                groups.add(groupStart..groupEnd)
                groupStart = index
                groupEnd = index
            }
        }
        groups.add(groupStart..groupEnd)

        // Line numbers consumed before each op, so a hunk header can be computed from any op index.
        val oldBefore = IntArray(ops.size + 1)
        val newBefore = IntArray(ops.size + 1)
        for (i in ops.indices) {
            oldBefore[i + 1] = oldBefore[i] + if (ops[i].kind != Kind.INSERT) 1 else 0
            newBefore[i + 1] = newBefore[i] + if (ops[i].kind != Kind.DELETE) 1 else 0
        }

        val out = StringBuilder()
        out.append("--- ").append(oldLabel).append('\n')
        out.append("+++ ").append(newLabel).append('\n')
        for (group in groups) {
            val from = maxOf(0, group.first - context)
            val to = minOf(ops.size - 1, group.last + context)
            val oldCount = oldBefore[to + 1] - oldBefore[from]
            val newCount = newBefore[to + 1] - newBefore[from]
            out.append("@@ -").append(range(oldBefore[from], oldCount))
                .append(" +").append(range(newBefore[from], newCount)).append(" @@\n")
            for (i in from..to) {
                val op = ops[i]
                out.append(
                    when (op.kind) {
                        Kind.SAME -> ' '
                        Kind.DELETE -> '-'
                        Kind.INSERT -> '+'
                    },
                )
                if (op.line.endsWith("\n")) {
                    out.append(op.line)
                } else {
                    out.append(op.line).append("\n\\ No newline at end of file\n")
                }
            }
        }
        return out.toString()
    }

    /** `start` is the count of lines before the range; unified diff numbers lines from 1, and an empty range points at the line before it. */
    private fun range(linesBefore: Int, count: Int): String {
        val start = if (count == 0) linesBefore else linesBefore + 1
        return if (count == 1) "$start" else "$start,$count"
    }

    /** Splits into lines that keep their own `\n`, so "last line has no newline" is part of the line's identity. */
    internal fun splitKeepingTerminators(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val newline = text.indexOf('\n', start)
            if (newline < 0) {
                lines.add(text.substring(start))
                break
            }
            lines.add(text.substring(start, newline + 1))
            start = newline + 1
        }
        return lines
    }

    private fun script(old: List<String>, new: List<String>): List<Op> {
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix &&
            old[old.size - 1 - suffix] == new[new.size - 1 - suffix]
        ) {
            suffix++
        }

        val ops = ArrayList<Op>(old.size + new.size)
        for (i in 0 until prefix) ops.add(Op(Kind.SAME, old[i]))
        middle(old.subList(prefix, old.size - suffix), new.subList(prefix, new.size - suffix), ops)
        for (i in old.size - suffix until old.size) ops.add(Op(Kind.SAME, old[i]))
        return ops
    }

    private fun middle(old: List<String>, new: List<String>, ops: MutableList<Op>) {
        val n = old.size
        val m = new.size
        if (n == 0 || m == 0 || n.toLong() * m > MAX_LCS_CELLS) {
            old.forEach { ops.add(Op(Kind.DELETE, it)) }
            new.forEach { ops.add(Op(Kind.INSERT, it)) }
            return
        }

        // lcs[i][j] = length of the LCS of old[i..] and new[j..], flattened row-major.
        val width = m + 1
        val lcs = IntArray((n + 1) * width)
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i * width + j] = if (old[i] == new[j]) {
                    lcs[(i + 1) * width + j + 1] + 1
                } else {
                    maxOf(lcs[(i + 1) * width + j], lcs[i * width + j + 1])
                }
            }
        }

        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                old[i] == new[j] -> {
                    ops.add(Op(Kind.SAME, old[i]))
                    i++
                    j++
                }
                // Prefer deleting first so a replaced line renders as "-old" then "+new".
                lcs[(i + 1) * width + j] >= lcs[i * width + j + 1] -> {
                    ops.add(Op(Kind.DELETE, old[i]))
                    i++
                }
                else -> {
                    ops.add(Op(Kind.INSERT, new[j]))
                    j++
                }
            }
        }
        while (i < n) ops.add(Op(Kind.DELETE, old[i++]))
        while (j < m) ops.add(Op(Kind.INSERT, new[j++]))
    }
}
