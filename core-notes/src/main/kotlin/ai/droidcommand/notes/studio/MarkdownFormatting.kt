package ai.droidcommand.notes.studio

/** Text plus selection, the state the formatting toolbar works on. [start] and [end] are clamped and ordered. */
data class TextEdit(val text: String, val start: Int, val end: Int) {
    val selStart get() = minOf(start, end).coerceIn(0, text.length)
    val selEnd get() = maxOf(start, end).coerceIn(0, text.length)
}

enum class FormatAction { BOLD, ITALIC, CODE, STRIKE, H1, H2, H3, BULLET, NUMBERED, TASK, QUOTE, LINK, CODE_BLOCK, MERMAID, TABLE, RULE }

/** Pure functions behind the editor toolbar, so they can be tested without Compose. */
object MarkdownFormatting {
    fun apply(action: FormatAction, edit: TextEdit): TextEdit = when (action) {
        FormatAction.BOLD -> wrap(edit, "**", "**", "bold")
        FormatAction.ITALIC -> wrap(edit, "*", "*", "italic")
        FormatAction.CODE -> wrap(edit, "`", "`", "code")
        FormatAction.STRIKE -> wrap(edit, "~~", "~~", "text")
        FormatAction.LINK -> wrap(edit, "[", "](https://)", "link")
        FormatAction.H1 -> prefixLines(edit, "# ")
        FormatAction.H2 -> prefixLines(edit, "## ")
        FormatAction.H3 -> prefixLines(edit, "### ")
        FormatAction.BULLET -> prefixLines(edit, "- ")
        FormatAction.TASK -> prefixLines(edit, "- [ ] ")
        FormatAction.QUOTE -> prefixLines(edit, "> ")
        FormatAction.NUMBERED -> numbered(edit)
        FormatAction.CODE_BLOCK -> block(edit, "```\n", "\n```", "code")
        FormatAction.MERMAID -> block(edit, "```mermaid\n", "\n```", "graph TD\n  A[Start] --> B[Done]")
        FormatAction.TABLE -> insert(edit, "| Column | Column |\n| --- | --- |\n| Cell | Cell |\n")
        FormatAction.RULE -> insert(edit, "\n---\n")
    }

    private fun wrap(edit: TextEdit, before: String, after: String, placeholder: String): TextEdit {
        val s = edit.selStart
        val e = edit.selEnd
        val inner = edit.text.substring(s, e).ifEmpty { placeholder }
        val text = edit.text.substring(0, s) + before + inner + after + edit.text.substring(e)
        return TextEdit(text, s + before.length, s + before.length + inner.length)
    }

    private fun block(edit: TextEdit, before: String, after: String, placeholder: String): TextEdit {
        val s = edit.selStart
        val lead = if (s > 0 && edit.text[s - 1] != '\n') "\n" else ""
        return wrap(edit, lead + before, after + "\n", placeholder)
    }

    private fun insert(edit: TextEdit, snippet: String): TextEdit {
        val s = edit.selStart
        val text = edit.text.substring(0, s) + snippet + edit.text.substring(edit.selEnd)
        return TextEdit(text, s + snippet.length, s + snippet.length)
    }

    private fun lineRange(edit: TextEdit): IntRange {
        val first = edit.text.lastIndexOf('\n', (edit.selStart - 1).coerceAtLeast(0)).let { if (edit.selStart == 0 || it < 0) 0 else it + 1 }
        val lastNl = edit.text.indexOf('\n', edit.selEnd)
        return first until (if (lastNl < 0) edit.text.length else lastNl)
    }

    private fun mapLines(edit: TextEdit, transform: (Int, String) -> String): TextEdit {
        val range = lineRange(edit)
        val chunk = edit.text.substring(range.first, range.last + 1)
        val out = chunk.split('\n').mapIndexed(transform).joinToString("\n")
        val text = edit.text.substring(0, range.first) + out + edit.text.substring(range.last + 1)
        return TextEdit(text, range.first, range.first + out.length)
    }

    /** Adds [prefix] to every selected line, or removes it when every line already has it. */
    private fun prefixLines(edit: TextEdit, prefix: String): TextEdit {
        val range = lineRange(edit)
        val lines = edit.text.substring(range.first, range.last + 1).split('\n')
        val remove = lines.all { it.startsWith(prefix) }
        return mapLines(edit) { _, line -> if (remove) line.removePrefix(prefix) else prefix + line.replace(Regex("^#{1,6} "), "") }
    }

    private fun numbered(edit: TextEdit): TextEdit = mapLines(edit) { i, line -> "${i + 1}. " + line.replace(Regex("^\\d+\\. "), "") }
}
