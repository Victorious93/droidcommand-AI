package ai.droidcommand.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MarkdownPreviewTest {
    @Test
    fun `parses headings bullets paragraphs and fenced code`() {
        val blocks = MarkdownPreview.parse(
            """
            # Title

            A line
            continued.

            - one
            * two

            ```
            val x = 1
            ```
            """.trimIndent(),
        )
        assertEquals(MarkdownBlock.Heading(1, "Title"), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("A line continued."), blocks[1])
        assertEquals(MarkdownBlock.Bullet("one"), blocks[2])
        assertEquals(MarkdownBlock.Bullet("two"), blocks[3])
        val code = assertIs<MarkdownBlock.Code>(blocks[4])
        assertEquals("val x = 1", code.text)
    }
}
