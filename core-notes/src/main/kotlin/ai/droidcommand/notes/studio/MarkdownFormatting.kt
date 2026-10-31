package ai.droidcommand.notes.studio

/** Text plus a selection, independent of any UI toolkit. [start] <= [end]. */
data class TextEdit(val text: String, val start: Int, val end: Int) {
    init {
        require(start in 0..text.length && end in start..text.length) { "selection out of range" }
    }
}

enum class FormatAction { BOLD, ITALIC, CODE, HEADING, BULLET, NUMBERED, QUOTE, LINK, CODE_BLOCK, MERMAID, CHECKBOX }

/** The formatting toolbar's actions as pure functions so they are JVM-tested. */
object MarkdownFormatter {
    fun apply(edit: TextEdit, action: FormatAction): TextEdit = when (action) {
        FormatAction.BOLD -> wrap(edit, "**", "**", "bold")
        FormatAction.ITALIC -> wrap(edit, "_", "_", "italic")
        FormatAction.CODE -> wrap(edit, "`", "`", "code")
        FormatAction.LINK -> wrap(edit, "[", "](https://)", "link")
        FormatAction.HEADING -> prefixLines(edit, "## ")
        FormatAction.BULLET -> prefixLines(edit, "- ")
        FormatAction.CHECKBOX -> prefixLines(edit, "- [ ] ")
        FormatAction.QUOTE -> prefixLines(edit, "> ")
        FormatAction.NUMBERED -> numberLines(edit)
        FormatAction.CODE_BLOCK -> block(edit, "```\n", "\n```", "code")
        FormatAction.MERMAID -> block(edit, "```mermaid\n", "\n```", "graph TD\n  A[Start] --> B[Done]")
    }

    private fun wrap(edit: TextEdit, before: String, after: String, placeholder: String): TextEdit {
        val selected = edit.text.substring(edit.start, edit.end).ifEmpty { placeholder }
        val text = edit.text.substring(0, edit.start) + before + selected + after + edit.text.substring(edit.end)
        val s = edit.start + before.length
        return TextEdit(text, s, s + selected.length)
    }

    private fun block(edit: TextEdit, before: String, after: String, placeholder: String): TextEdit {
        val needsNewline = edit.start > 0 && edit.text[edit.start - 1] != '\n'
        val lead = if (needsNewline) "\n" else ""
        return wrap(edit, lead + before, after, placeholder)
    }

    private fun lineRange(edit: TextEdit): IntRange {
        val lineStart = edit.text.lastIndexOf('\n', (edit.start - 1).coerceAtLeast(0)).let { if (edit.start == 0) 0 else it + 1 }
        val lineEnd = edit.text.indexOf('\n', edit.end).let { if (it < 0) edit.text.length else it }
        return lineStart until lineEnd
    }

    private fun prefixLines(edit: TextEdit, prefix: String): TextEdit = transformLines(edit) { _, line ->
        if (line.startsWith(prefix)) line.removePrefix(prefix) else prefix + line
    }

    private fun numberLines(edit: TextEdit): TextEdit = transformLines(edit) { i, line -> "${i + 1}. $line" }

    private fun transformLines(edit: TextEdit, f: (Int, String) -> String): TextEdit {
        val range = lineRange(edit)
        val segment = edit.text.substring(range.first, range.last + 1)
        val replaced = segment.split('\n').mapIndexed(f).joinToString("\n")
        val text = edit.text.substring(0, range.first) + replaced + edit.text.substring(range.last + 1)
        return TextEdit(text, range.first, range.first + replaced.length)
    }
}
