package ai.droidcommand.notes

/** A small preview. Headings, bullets, fenced code, and paragraphs. Tables, images, and HTML are left as text. */
sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Bullet(val text: String) : MarkdownBlock()
    data class Code(val text: String) : MarkdownBlock()
}

fun renderMarkdown(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
    }
    val lines = markdown.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                blocks += MarkdownBlock.Code(code.toString())
            }
            line.startsWith("#") -> {
                flush()
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                blocks += MarkdownBlock.Heading(level, line.drop(level).trim())
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                flush()
                blocks += MarkdownBlock.Bullet(line.drop(2).trim())
            }
            line.isBlank() -> flush()
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
        i++
    }
    flush()
    return blocks
}
