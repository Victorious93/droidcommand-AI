package ai.droidcommand.repomap

/** A rendered map plus what was left out, so a caller can say so instead of implying completeness. */
class RenderedMap(val text: String, val filesShown: Int, val filesOmitted: Int)

/**
 * Renders ranked files as a compact outline, most relevant file first, never longer than `budgetChars`
 * (RM-7): whole file blocks are added while they fit; a block that does not fit is retried with fewer
 * symbols before giving up, and rendering stops at the first block that cannot fit even with one symbol.
 * A trailing note says how many ranked files were left out; it is never dropped for lack of room — trailing
 * file blocks are given up instead — so an incomplete map cannot pass for a complete one.
 */
object RepoMapRenderer {
    fun render(ranked: List<FileRank>, budgetChars: Int, maxSymbolsPerFile: Int = 25, focusFiles: Set<String> = emptySet()): RenderedMap {
        require(budgetChars > 0) { "budgetChars must be > 0, got $budgetChars" }
        val candidates = ranked.filter { it.symbols.isNotEmpty() }
        val blocks = ArrayList<String>()
        var length = 0
        for (file in candidates) {
            var count = minOf(file.symbols.size, maxSymbolsPerFile)
            var block: String? = null
            while (count >= 1) {
                val candidate = block(file, count, focusFiles)
                if (length + (if (blocks.isEmpty()) 0 else 1) + candidate.length <= budgetChars) {
                    block = candidate
                    break
                }
                count = if (count == 1) 0 else maxOf(1, count / 2)
            }
            if (block == null) break
            length += (if (blocks.isEmpty()) 0 else 1) + block.length
            blocks.add(block)
        }
        // An incomplete map must say so. If the note does not fit after the last block, give up trailing
        // blocks until it does, so an omission is never silent.
        fun note() = "\n... ${candidates.size - blocks.size} more file(s) not shown (budget)"
        while (blocks.isNotEmpty() && blocks.size < candidates.size && length + note().length > budgetChars) {
            length -= blocks.removeAt(blocks.size - 1).length + (if (blocks.isEmpty()) 0 else 1)
        }
        val text = if (blocks.isEmpty()) "" else blocks.joinToString("\n") + (if (blocks.size < candidates.size) note() else "")
        return RenderedMap(text, blocks.size, candidates.size - blocks.size)
    }

    private fun block(file: FileRank, symbolCount: Int, focusFiles: Set<String>): String {
        val header = file.path + (if (file.path in focusFiles) " (focus)" else "") + ":"
        val lines = file.symbols.take(symbolCount).sortedBy { it.definition.line }
            .joinToString("\n") { "  ${it.definition.line}: ${it.definition.kind} ${it.definition.name}" }
        val more = file.symbols.size - symbolCount
        return header + "\n" + lines + if (more > 0) "\n  ... +$more more" else ""
    }
}
