package ai.droidcommand.integration

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.codeedit.CodeEditTool
import ai.droidcommand.codeedit.SearchReplaceEditor
import ai.droidcommand.git.CheckpointingTool
import ai.droidcommand.git.GitCheckpointer
import ai.droidcommand.git.GitUndoTool
import ai.droidcommand.shell.ProcessBuilderShellExecutor
import ai.droidcommand.shell.ShellSecurityPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The three developer-assistance features working together through their public tool interfaces, against a
 * real git repository: `edit_file` (core-code-edit) wrapped by [CheckpointingTool] and undone by `git_undo`
 * (both core-git). Neither module depends on the other, which is why this lives here.
 *
 * Like the core-git tests, each case returns early when no `git` executable exists.
 */
class DevAssistIntegrationTest {
    private class Repo {
        val root: Path = createTempDirectory("devassist-it").toRealPath()
        private val isolation: Map<String, String>

        init {
            val emptyConfig = Files.createFile(root.resolveSibling(root.fileName.toString() + ".gitconfig"))
            isolation = mapOf(
                "GIT_CONFIG_GLOBAL" to emptyConfig.toString(),
                "GIT_CONFIG_NOSYSTEM" to "1",
                "GIT_CEILING_DIRECTORIES" to root.parent.toString(),
                "GIT_AUTHOR_NAME" to "Test User",
                "GIT_AUTHOR_EMAIL" to "test@example.com",
                "GIT_COMMITTER_NAME" to "Test User",
                "GIT_COMMITTER_EMAIL" to "test@example.com",
            )
            git("init", "-q", "-b", "main")
            for (name in listOf("a", "b", "c")) write("$name.txt", "${name}1\n")
            git("add", "-A")
            git("commit", "-q", "-m", "base")
        }

        fun git(vararg args: String): String {
            val builder = ProcessBuilder(listOf("git") + args).directory(root.toFile()).redirectErrorStream(true)
            builder.environment().putAll(isolation)
            val process = builder.start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
            return output
        }

        fun write(name: String, text: String) = root.resolve(name).writeText(text)

        fun read(name: String) = root.resolve(name).readText()

        fun abs(name: String) = root.resolve(name).toString()

        fun commitCount() = git("rev-list", "--count", "HEAD").trim().toInt()

        fun status() = git("status", "--porcelain", "-uall").lines().filter { it.isNotBlank() }.sorted()

        fun checkpointer() = GitCheckpointer(
            ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("git"), allowedWorkingDirectories = listOf(root.toString()))),
            root,
            extraEnvironment = isolation,
        )

        fun editTool(enabled: Boolean, checkpointer: GitCheckpointer = checkpointer()) =
            CheckpointingTool(CodeEditTool(SearchReplaceEditor(listOf(root))), checkpointer, enabled = enabled)
    }

    private fun edit(search: String, replace: String) = "<<<<<<< SEARCH\n$search\n=======\n$replace\n>>>>>>> REPLACE\n"

    private fun gitAvailable() = runCatching {
        ProcessBuilder("git", "--version").redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() == 0 }
    }.getOrDefault(false)

    @Test
    fun `edit then checkpoint then undo restores the file and keeps unrelated user work`() {
        if (!gitAvailable()) return
        val repo = Repo()
        repo.write("c.txt", "c1\nmy own uncommitted work\n") // the user's, unrelated to the agent's edit
        repo.write("d.txt", "untracked\n")
        val before = repo.commitCount()
        val checkpointer = repo.checkpointer()

        val edited = repo.editTool(enabled = true, checkpointer).execute(mapOf("path" to repo.abs("a.txt"), "edits" to edit("a1", "a2")))

        assertIs<ToolResult.Success>(edited)
        assertContains(edited.output, "+a2") // the diff from edit_file
        assertContains(edited.output, "checkpoint") // the note from the wrapper
        assertEquals("a2\n", repo.read("a.txt"))
        assertEquals(before + 1, repo.commitCount())
        assertEquals(listOf(" M c.txt", "?? d.txt"), repo.status()) // the user's work was not swept into the commit

        val undone = GitUndoTool(checkpointer).execute(emptyMap())

        assertIs<ToolResult.Success>(undone)
        assertEquals("a1\n", repo.read("a.txt"))
        assertEquals("c1\nmy own uncommitted work\n", repo.read("c.txt"))
        assertEquals("untracked\n", repo.read("d.txt"))
        assertEquals(listOf(" M c.txt", "?? d.txt"), repo.status())
        assertEquals(before + 2, repo.commitCount()) // undo is a new commit, history is not rewritten
    }

    @Test
    fun `an ambiguous edit changes nothing and makes no commit`() {
        if (!gitAvailable()) return
        val repo = Repo()
        repo.write("a.txt", "dup\nx\ndup\n")
        repo.git("commit", "-q", "-am", "dups")
        val before = repo.commitCount()

        val result = repo.editTool(enabled = true).execute(mapOf("path" to repo.abs("a.txt"), "edits" to edit("dup", "y")))

        assertIs<ToolResult.Failure>(result)
        assertContains(result.reason, "AMBIGUOUS")
        assertEquals("dup\nx\ndup\n", repo.read("a.txt"))
        assertEquals(before, repo.commitCount())
        assertEquals(emptyList(), repo.status())
    }

    @Test
    fun `checkpointing is off unless enabled`() {
        if (!gitAvailable()) return
        val repo = Repo()
        val before = repo.commitCount()

        val result = repo.editTool(enabled = false).execute(mapOf("path" to repo.abs("a.txt"), "edits" to edit("a1", "a2")))

        assertIs<ToolResult.Success>(result)
        assertFalse("checkpoint" in result.output)
        assertEquals("a2\n", repo.read("a.txt"))
        assertEquals(before, repo.commitCount())
        assertEquals(listOf(" M a.txt"), repo.status())
    }

    @Test
    fun `undo refuses rather than overwrite the user's later edit to the same file`() {
        if (!gitAvailable()) return
        val repo = Repo()
        val checkpointer = repo.checkpointer()
        repo.editTool(enabled = true, checkpointer).execute(mapOf("path" to repo.abs("a.txt"), "edits" to edit("a1", "a2")))
        repo.write("a.txt", "a2\nuser kept editing\n")

        val undone = GitUndoTool(checkpointer).execute(emptyMap())

        assertIs<ToolResult.Failure>(undone)
        assertEquals("a2\nuser kept editing\n", repo.read("a.txt"))
        assertTrue(repo.status().any { it.endsWith("a.txt") })
    }

    @Test
    fun `dry run previews without writing or committing`() {
        if (!gitAvailable()) return
        val repo = Repo()
        val before = repo.commitCount()

        val result = repo.editTool(enabled = true).execute(
            mapOf("path" to repo.abs("a.txt"), "edits" to edit("a1", "a2"), "dry_run" to "true"),
        )

        assertIs<ToolResult.Success>(result)
        assertContains(result.output, "DRY RUN")
        assertEquals("a1\n", repo.read("a.txt"))
        assertEquals(before, repo.commitCount())
    }
}
