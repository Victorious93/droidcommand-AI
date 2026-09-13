package ai.droidcommand.promptregen

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepoContextScannerTest {
    @Test
    fun `empty directory has no markers and no branch`() {
        val dir = Files.createTempDirectory("repo-context-test")
        val context = RepoContextScanner.scan(dir)
        assertFalse(context.hasClaudeMd)
        assertFalse(context.hasAgentsMd)
        assertFalse(context.hasReadme)
        assertTrue(context.docFiles.isEmpty())
        assertNull(context.gitBranch)
    }

    @Test
    fun `detects CLAUDE md AGENTS md and README md`() {
        val dir = Files.createTempDirectory("repo-context-test")
        dir.resolve("CLAUDE.md").writeText("orientation")
        dir.resolve("AGENTS.md").writeText("agents")
        dir.resolve("README.md").writeText("readme")

        val context = RepoContextScanner.scan(dir)
        assertTrue(context.hasClaudeMd)
        assertTrue(context.hasAgentsMd)
        assertTrue(context.hasReadme)
    }

    @Test
    fun `lists markdown files in docs directory sorted`() {
        val dir = Files.createTempDirectory("repo-context-test")
        val docs = dir.resolve("docs").createDirectories()
        docs.resolve("B.md").writeText("b")
        docs.resolve("A.md").writeText("a")
        docs.resolve("ignored.txt").writeText("not markdown")

        val context = RepoContextScanner.scan(dir)
        assertEquals(listOf("A.md", "B.md"), context.docFiles)
    }

    @Test
    fun `reads branch name from attached HEAD`() {
        val dir = Files.createTempDirectory("repo-context-test")
        val gitDir = dir.resolve(".git").createDirectories()
        gitDir.resolve("HEAD").writeText("ref: refs/heads/claude/prompt-regenerator-svq4l8\n")

        val context = RepoContextScanner.scan(dir)
        assertEquals("claude/prompt-regenerator-svq4l8", context.gitBranch)
    }

    @Test
    fun `detached HEAD yields null branch, not a fabricated name`() {
        val dir = Files.createTempDirectory("repo-context-test")
        val gitDir = dir.resolve(".git").createDirectories()
        gitDir.resolve("HEAD").writeText("bd6e13ac1234567890abcdef1234567890abcdef\n")

        val context = RepoContextScanner.scan(dir)
        assertNull(context.gitBranch)
    }

    @Test
    fun `missing git directory yields null branch`() {
        val dir = Files.createTempDirectory("repo-context-test")
        val context = RepoContextScanner.scan(dir)
        assertNull(context.gitBranch)
    }
}
