package ai.droidcommand.notes

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MarkdownAndPluginTest {
    @Test
    fun `parser keeps headings bullets code and paragraphs`() {
        val blocks = parseMarkdown(
            """
            # Title

            A paragraph
            that wraps.

            - one
            - two

            ```
            code
            ```
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                MarkdownBlock.Heading(1, "Title"),
                MarkdownBlock.Paragraph("A paragraph that wraps."),
                MarkdownBlock.Bullet("one"),
                MarkdownBlock.Bullet("two"),
                MarkdownBlock.Code("code"),
            ),
            blocks,
        )
    }

    @Test
    fun `a fresh plugin directory has zero plugins and none are bundled`() {
        val store = FilePluginStore(Files.createTempDirectory("plugins"))
        assertEquals(emptyList(), store.list())
        assertEquals("hello", store.applyEnabled("hello"))
    }

    @Test
    fun `install enable disable and uninstall a declarative plugin`() {
        val store = FilePluginStore(Files.createTempDirectory("plugins"))
        val installed = store.install(
            "id: quote-line\nname: Quote\ndescription: Prefix a quote\nop: prepend|> \nop: replace|TODO|DONE\n",
        )
        assertEquals("quote-line", installed.id)
        assertTrue(installed.enabled)
        assertEquals("> DONE", store.applyEnabled("TODO"))
        store.setEnabled("quote-line", false)
        assertEquals("TODO", store.applyEnabled("TODO"))
        assertTrue(store.uninstall("quote-line"))
        assertEquals(emptyList(), store.list())
    }

    @Test
    fun `code and unknown operations are refused`() {
        val store = FilePluginStore(Files.createTempDirectory("plugins"))
        assertFailsWith<IllegalArgumentException> {
            store.install("id: Bad_Id\nname: No\nop: exec|rm")
        }
        assertFailsWith<IllegalArgumentException> {
            store.install("id: ok-id\nname: Ok\nop: exec|rm")
        }
        assertEquals(emptyList(), store.list())
    }
}
