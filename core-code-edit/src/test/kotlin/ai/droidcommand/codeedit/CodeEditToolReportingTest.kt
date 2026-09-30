package ai.droidcommand.codeedit

import ai.droidcommand.agent.ToolResult
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun edit(search: String, replace: String) = "<<<<<<< SEARCH\n$search\n=======\n$replace\n>>>>>>> REPLACE"

/** ED-9: the tool surface — diff on success, per-block report on rejection, dry_run, and parse errors that name a line. */
class CodeEditToolReportingTest {
    private fun tool(content: String, maxDiffChars: Int = 20_000): Triple<CodeEditTool, java.nio.file.Path, java.nio.file.Path> {
        val root = createTempDirectory("code-edit-report")
        val file = root.resolve("F.kt")
        file.writeText(content)
        return Triple(CodeEditTool(SearchReplaceEditor(listOf(root)), maxDiffChars), root, file)
    }

    @Test
    fun `success output contains the diff`() {
        val (tool, _, file) = tool("val a = 1\n")
        val result = tool.execute(mapOf("path" to file.toString(), "edits" to edit("val a = 1", "val a = 2")))
        assertIs<ToolResult.Success>(result)
        assertContains(result.output, "Applied 1 edit(s)")
        assertContains(result.output, "-val a = 1")
        assertContains(result.output, "+val a = 2")
    }

    @Test
    fun `dry_run true previews without writing`() {
        val (tool, _, file) = tool("val a = 1\n")
        val result = tool.execute(mapOf("path" to file.toString(), "edits" to edit("val a = 1", "val a = 2"), "dry_run" to "true"))
        assertIs<ToolResult.Success>(result)
        assertContains(result.output, "DRY RUN")
        assertContains(result.output, "+val a = 2")
        assertEquals("val a = 1\n", file.readText())
    }

    @Test
    fun `an invalid dry_run value is rejected before anything runs`() {
        val (tool, _, file) = tool("val a = 1\n")
        val result = tool.execute(mapOf("path" to file.toString(), "edits" to edit("val a = 1", "x"), "dry_run" to "maybe"))
        assertIs<ToolResult.Failure>(result)
        assertContains(result.reason, "dry_run")
        assertEquals("val a = 1\n", file.readText())
    }

    @Test
    fun `a rejected edit reports each block with its status and line numbers`() {
        val (tool, _, file) = tool("dup\nx\ndup\n")
        val edits = edit("dup", "y") + "\n" + edit("absent", "z")
        val result = tool.execute(mapOf("path" to file.toString(), "edits" to edits))
        assertIs<ToolResult.Failure>(result)
        assertContains(result.reason, "Block 1: AMBIGUOUS")
        assertContains(result.reason, "lines 1, 3")
        assertContains(result.reason, "Block 2: NOT_FOUND")
        assertEquals("dup\nx\ndup\n", file.readText())
    }

    @Test
    fun `a long diff is truncated with a count of the hidden lines`() {
        val content = (1..200).joinToString("") { "line$it\n" }
        val (tool, _, file) = tool(content, maxDiffChars = 200)
        val edits = (1..200 step 20).joinToString("\n") { edit("line$it\n", "changed$it\n") }
        val result = tool.execute(mapOf("path" to file.toString(), "edits" to edits))
        assertIs<ToolResult.Success>(result)
        assertContains(result.output, "diff truncated")
        assertTrue(result.output.length < 600, "output length ${result.output.length}")
        assertTrue(file.readText().contains("changed181"), "the edit itself is complete even though the shown diff is truncated")
    }

    @Test
    fun `parse errors name the line they occurred on`() {
        val unterminatedSearch = assertFailsWith<IllegalArgumentException> { parseEditBlocks("intro\n<<<<<<< SEARCH\nold\n") }
        assertContains(unterminatedSearch.message.orEmpty(), "line 2")

        val unterminatedReplace = assertFailsWith<IllegalArgumentException> {
            parseEditBlocks("<<<<<<< SEARCH\nold\n=======\nnew\n")
        }
        assertContains(unterminatedReplace.message.orEmpty(), "line 1")
    }

    @Test
    fun `a block missing its terminator cannot silently swallow the next block`() {
        val text = "<<<<<<< SEARCH\na\n=======\nb\n<<<<<<< SEARCH\nc\n=======\nd\n>>>>>>> REPLACE\n"
        val error = assertFailsWith<IllegalArgumentException> { parseEditBlocks(text) }
        assertContains(error.message.orEmpty(), "missing its")
        assertContains(error.message.orEmpty(), "Line 5")
    }

    @Test
    fun `CRLF in the edits text is handled`() {
        val blocks = parseEditBlocks("<<<<<<< SEARCH\r\nold\r\n=======\r\nnew\r\n>>>>>>> REPLACE\r\n")
        assertEquals(listOf(EditBlock("old", "new")), blocks)
    }

    @Test
    fun `a replacement may contain a divider-like line`() {
        val blocks = parseEditBlocks("<<<<<<< SEARCH\nTitle\n=======\nTitle\n=======\n>>>>>>> REPLACE")
        assertEquals(listOf(EditBlock("Title", "Title\n=======")), blocks)
    }

    @Test
    fun `text around the blocks is ignored`() {
        val blocks = parseEditBlocks("Here is the change:\n```\n<<<<<<< SEARCH\na\n=======\nb\n>>>>>>> REPLACE\n```\nDone.")
        assertEquals(listOf(EditBlock("a", "b")), blocks)
    }

    @Test
    fun `the tool spec still requires approval-level security`() {
        val (tool, _, _) = tool("x")
        assertFalse(tool.spec.securityLevel == ai.droidcommand.agent.SecurityLevel.NORMAL)
    }
}
