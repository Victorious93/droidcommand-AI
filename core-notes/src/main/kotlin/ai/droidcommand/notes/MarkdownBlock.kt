package ai.droidcommand.notes

/** A small block model for preview. It is not a full CommonMark renderer. */
sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Bullet(val text: String) : MarkdownBlock()
    data class Code(val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
}

/**
 * Splits markdown into headings, bullets, fenced code and paragraphs. Enough for an in-app preview.
 * Inline emphasis is left as typed.
 */
fun parseMarkdown(source: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()
    val code = StringBuilder()
    var inCode = false

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks += MarkdownBlock.Paragraph(text)
        paragraph.clear()
    }

    for (raw in source.lineSequence()) {
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                blocks += MarkdownBlock.Code(code.toString().trimEnd())
                code.clear()
                inCode = false
            } else {
                flushParagraph()
                inCode = true
            }
            continue
        }
        if (inCode) {
            if (code.isNotEmpty()) code.append('\n')
            code.append(line)
            continue
        }
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            flushParagraph()
            continue
        }
        val heading = headingOf(trimmed)
        if (heading != null) {
            flushParagraph()
            blocks += heading
            continue
        }
        val bullet = bulletOf(trimmed)
        if (bullet != null) {
            flushParagraph()
            blocks += MarkdownBlock.Bullet(bullet)
            continue
        }
        if (paragraph.isNotEmpty()) paragraph.append(' ')
        paragraph.append(trimmed)
    }
    if (inCode) blocks += MarkdownBlock.Code(code.toString().trimEnd())
    flushParagraph()
    return blocks
}

private fun headingOf(line: String): MarkdownBlock.Heading? {
    var level = 0
    while (level < line.length && line[level] == '#') level++
    if (level == 0 || level > 6) return null
    if (level == line.length || line[level] != ' ') return null
    val text = line.substring(level + 1).trim()
    if (text.isEmpty()) return null
    return MarkdownBlock.Heading(level, text)
}

private fun bulletOf(line: String): String? {
    if (line.startsWith("- ") || line.startsWith("* ")) return line.substring(2).trim()
    val dot = line.indexOf(". ")
    if (dot in 1..2 && line.substring(0, dot).all { it.isDigit() }) return line.substring(dot + 2).trim()
    return null
}
