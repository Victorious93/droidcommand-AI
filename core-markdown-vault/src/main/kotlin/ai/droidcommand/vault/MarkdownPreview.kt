package ai.droidcommand.vault

/** A small subset of markdown, enough to preview a note. Not a full CommonMark parser. */
sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()

    data class Paragraph(val text: String) : MarkdownBlock()

    data class Bullet(val text: String) : MarkdownBlock()

    data class Code(val text: String) : MarkdownBlock()
}

object MarkdownPreview {
    fun parse(markdown: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = StringBuilder()
        var inCode = false
        val code = StringBuilder()

        fun flushParagraph() {
            val text = paragraph.toString().trim()
            if (text.isNotEmpty()) blocks += MarkdownBlock.Paragraph(text)
            paragraph.clear()
        }

        for (line in markdown.lines()) {
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
            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                flushParagraph()
                blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
                continue
            }
            val bullet = BULLET.matchEntire(line)
            if (bullet != null) {
                flushParagraph()
                blocks += MarkdownBlock.Bullet(bullet.groupValues[1].trim())
                continue
            }
            if (line.isBlank()) {
                flushParagraph()
            } else {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
        if (inCode) blocks += MarkdownBlock.Code(code.toString().trimEnd())
        flushParagraph()
        return blocks
    }

    private val HEADING = Regex("(#{1,6})\\s+(\\S.*)")
    private val BULLET = Regex("[-*]\\s+(\\S.*)")
}
