package ai.droidcommand.git

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** GC-1, GC-12 and the `git_undo` tool. */
class CheckpointingToolTest {
    /** Stand-in for an editing tool: writes `content` to `path` and returns whatever [outcome] says. */
    private class WriterTool(
        private val repo: TestRepo,
        private val outcome: (String) -> ToolResult = { ToolResult.Success("wrote $it") },
    ) : Tool {
        override val spec = ToolSpec(
            name = "write_file",
            description = "test tool",
            securityLevel = SecurityLevel.SENSITIVE,
            permissionCategory = PermissionCategory.FILES,
        )

        override fun execute(input: Map<String, String>): ToolResult {
            val path = input.getValue("path")
            val result = outcome(path)
            if (result is ToolResult.Success || result is ToolResult.Partial) repo.write(path, input.getValue("content"))
            return result
        }
    }

    private fun input(path: String, content: String) = mapOf("path" to path, "content" to content)

    @Test
    fun `GC-1 disabled is a pure pass-through that never touches git`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        // Deliberately unusable checkpointer: if it were consulted at all, the output would mention it.
        val tool = CheckpointingTool(WriterTool(repo), repo.checkpointer(executables = emptySet()))

        val result = tool.execute(input("a.txt", "a2\n"))

        assertEquals(ToolResult.Success("wrote a.txt"), result)
        assertEquals(1, repo.commitCount())
        assertEquals("a2\n", repo.read("a.txt"))
    }

    @Test
    fun `GC-12 enabled records a checkpoint and appends a note without changing the result kind`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val tool = CheckpointingTool(WriterTool(repo), repo.checkpointer(), enabled = true)

        val result = tool.execute(input("a.txt", "a2\n"))

        assertIs<ToolResult.Success>(result)
        assertTrue(result.output.startsWith("wrote a.txt\n\n[checkpoint "), result.output)
        assertContains(result.output, "recorded; undo with the git_undo tool")
        assertEquals(2, repo.commitCount())
        assertEquals(listOf("a.txt"), repo.filesIn("HEAD"))
        assertContains(repo.message(), "agent: write_file a.txt")
    }

    @Test
    fun `GC-12 a Partial result keeps its reason and still gets checkpointed`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val tool = CheckpointingTool(WriterTool(repo) { ToolResult.Partial("half done", "ran out of budget") }, repo.checkpointer(), enabled = true)

        val result = tool.execute(input("a.txt", "a2\n"))

        assertIs<ToolResult.Partial>(result)
        assertEquals("ran out of budget", result.reason)
        assertContains(result.output, "[checkpoint ")
        assertEquals(2, repo.commitCount())
    }

    @Test
    fun `GC-12 a Failure is returned untouched and nothing is committed`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val failure = ToolResult.Failure("no such block")
        val tool = CheckpointingTool(WriterTool(repo) { failure }, repo.checkpointer(), enabled = true)

        val result = tool.execute(input("a.txt", "a2\n"))

        assertSame(failure, result)
        assertEquals(1, repo.commitCount())
    }

    @Test
    fun `GC-12 an Unexpected result is returned untouched`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val odd = ToolResult.Unexpected("strange")
        val tool = CheckpointingTool(WriterTool(repo) { odd }, repo.checkpointer(), enabled = true)
        assertSame(odd, tool.execute(input("a.txt", "a2\n")))
        assertEquals(1, repo.commitCount())
    }

    @Test
    fun `GC-12 when the path already had user changes the edit still happens and the note says why there is no checkpoint`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        repo.write("a.txt", "user wip\n")
        val tool = CheckpointingTool(WriterTool(repo), repo.checkpointer(), enabled = true)

        val result = tool.execute(input("a.txt", "agent edit\n"))

        assertIs<ToolResult.Success>(result)
        assertContains(result.output, "[checkpoint skipped: uncommitted changes exist in 'a.txt'")
        assertEquals("agent edit\n", repo.read("a.txt"))
        assertEquals(1, repo.commitCount())
    }

    @Test
    fun `GC-12 an edit that changes nothing is reported as such`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val tool = CheckpointingTool(WriterTool(repo), repo.checkpointer(), enabled = true)
        val result = tool.execute(input("a.txt", "a1\n"))
        assertIs<ToolResult.Success>(result)
        assertContains(result.output, "did not change")
        assertEquals(1, repo.commitCount())
    }

    @Test
    fun `GC-12 an unusable path cannot break the wrapped tool`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val delegate = object : Tool {
            override val spec = ToolSpec("noop", "test")
            override fun execute(input: Map<String, String>): ToolResult = ToolResult.Success("ran")
        }
        val tool = CheckpointingTool(delegate, repo.checkpointer(), enabled = true)

        val result = tool.execute(mapOf("path" to "bad\u0000name.txt"))

        assertIs<ToolResult.Success>(result)
        assertTrue(result.output.startsWith("ran"))
        assertContains(result.output, "[checkpoint skipped")
    }

    @Test
    fun `GC-12 a tool with no paths just runs`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val delegate = object : Tool {
            override val spec = ToolSpec("noop", "test")
            override fun execute(input: Map<String, String>): ToolResult = ToolResult.Success("ran")
        }
        assertEquals(ToolResult.Success("ran"), CheckpointingTool(delegate, repo.checkpointer(), enabled = true).execute(emptyMap()))
    }

    @Test
    fun `GC-12 wrapping adds no privilege because the spec is the wrapped tool's own`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val inner = WriterTool(repo)
        val tool = CheckpointingTool(inner, repo.checkpointer(), enabled = true)
        assertSame(inner.spec, tool.spec)
    }

    // ------------------------------------------------------------- git_undo tool

    @Test
    fun `git_undo declares itself sensitive and terminal-category`() {
        if (!TestRepo.gitAvailable()) return
        val tool = GitUndoTool(TestRepo.seeded().checkpointer())
        assertEquals("git_undo", tool.spec.name)
        assertEquals(SecurityLevel.SENSITIVE, tool.spec.securityLevel)
        assertEquals(PermissionCategory.TERMINAL, tool.spec.permissionCategory)
    }

    @Test
    fun `git_undo lists then undoes and reports refusals as failures`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        val edit = CheckpointingTool(WriterTool(repo), cp, enabled = true)
        val undo = GitUndoTool(cp)

        assertEquals(ToolResult.Success("No agent checkpoints found."), undo.execute(mapOf("action" to "list")))
        assertEquals(ToolResult.Success("No agent checkpoint left to undo."), undo.execute(emptyMap()))

        edit.execute(input("a.txt", "a2\n"))
        val listed = undo.execute(mapOf("action" to "list"))
        assertIs<ToolResult.Success>(listed)
        assertContains(listed.output, "agent: write_file a.txt")
        assertFalse(listed.output.contains("(undone)"))

        repo.write("a.txt", "my edit\n")
        val refused = undo.execute(emptyMap())
        assertIs<ToolResult.Failure>(refused)
        assertContains(refused.reason, "Undo refused")
        assertEquals("my edit\n", repo.read("a.txt"))

        repo.git("checkout", "--", "a.txt")
        val done = undo.execute(emptyMap())
        assertIs<ToolResult.Success>(done)
        assertContains(done.output, "restored: a.txt")
        assertEquals("a1\n", repo.read("a.txt"))
        assertContains((undo.execute(mapOf("action" to "list")) as ToolResult.Success).output, "(undone)")
    }

    @Test
    fun `git_undo rejects an unknown action`() {
        if (!TestRepo.gitAvailable()) return
        val result = GitUndoTool(TestRepo.seeded().checkpointer()).execute(mapOf("action" to "reset-hard"))
        assertIs<ToolResult.Failure>(result)
        assertContains(result.reason, "'action' must be")
    }
}
