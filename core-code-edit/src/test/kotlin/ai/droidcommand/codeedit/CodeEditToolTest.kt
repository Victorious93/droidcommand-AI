package ai.droidcommand.codeedit

import ai.droidcommand.agent.ToolResult
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ParseEditBlocksTest {
    @Test
    fun `parses a single block`() {
        val blocks = parseEditBlocks(
            """
            <<<<<<< SEARCH
            old text
            =======
            new text
            >>>>>>> REPLACE
            """.trimIndent(),
        )
        assertEquals(listOf(EditBlock("old text", "new text")), blocks)
    }

    @Test
    fun `parses multiple blocks in one string`() {
        val blocks = parseEditBlocks(
            """
            <<<<<<< SEARCH
            a
            =======
            b
            >>>>>>> REPLACE
            <<<<<<< SEARCH
            c
            =======
            d
            >>>>>>> REPLACE
            """.trimIndent(),
        )
        assertEquals(listOf(EditBlock("a", "b"), EditBlock("c", "d")), blocks)
    }

    @Test
    fun `throws when no blocks are present`() {
        assertFailsWith<IllegalArgumentException> { parseEditBlocks("not a block") }
    }

    @Test
    fun `throws on an unterminated SEARCH block`() {
        assertFailsWith<IllegalArgumentException> {
            parseEditBlocks("<<<<<<< SEARCH\nold\n")
        }
    }

    @Test
    fun `throws on an unterminated REPLACE block`() {
        assertFailsWith<IllegalArgumentException> {
            parseEditBlocks("<<<<<<< SEARCH\nold\n=======\nnew\n")
        }
    }
}

class CodeEditToolTest {
    @Test
    fun `fails without editing when 'path' is missing`() {
        val root = createTempDirectory("code-edit-tool-test")
        val result = CodeEditTool(SearchReplaceEditor(listOf(root))).execute(mapOf("edits" to "x"))
        assertIs<ToolResult.Failure>(result)
    }

    @Test
    fun `fails without editing when 'edits' is missing`() {
        val root = createTempDirectory("code-edit-tool-test")
        val result = CodeEditTool(SearchReplaceEditor(listOf(root))).execute(mapOf("path" to root.resolve("F.kt").toString()))
        assertIs<ToolResult.Failure>(result)
    }

    @Test
    fun `fails with a readable reason when 'edits' is malformed`() {
        val root = createTempDirectory("code-edit-tool-test")
        val file = root.resolve("F.kt")
        file.writeText("val a = 1\n")

        val result = CodeEditTool(SearchReplaceEditor(listOf(root))).execute(
            mapOf("path" to file.toString(), "edits" to "not a block"),
        )
        assertIs<ToolResult.Failure>(result)
        assertEquals(true, result.reason.contains("SEARCH/REPLACE"))
    }

    @Test
    fun `applies a real edit end to end and reports success`() {
        val root = createTempDirectory("code-edit-tool-test")
        val file = root.resolve("F.kt")
        file.writeText("val a = 1\n")

        val result = CodeEditTool(SearchReplaceEditor(listOf(root))).execute(
            mapOf(
                "path" to file.toString(),
                "edits" to """
                    <<<<<<< SEARCH
                    val a = 1
                    =======
                    val a = 2
                    >>>>>>> REPLACE
                """.trimIndent(),
            ),
        )

        assertIs<ToolResult.Success>(result)
        assertEquals("val a = 2\n", file.readText())
    }
}
