package ai.droidcommand.git

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** GC-2 .. GC-5 and GC-11: when checkpointing is refused, what a checkpoint commit contains, and how git is invoked. */
class GitCheckpointerTest {
    private fun skipReason(result: BeginResult): String {
        assertIs<BeginResult.Skipped>(result)
        return result.reason
    }

    // ------------------------------------------------------------- GC-2: skips

    @Test
    fun `GC-2 skips outside a git repository`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.empty()
        assertContains(skipReason(repo.checkpointer().begin(listOf("a.txt"))), "not inside a git repository")
    }

    @Test
    fun `GC-2 skips a repository with no commits`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.empty()
        repo.git("init", "-q", "-b", "main")
        assertContains(skipReason(repo.checkpointer().begin(listOf("a.txt"))), "no commits")
    }

    @Test
    fun `GC-2 skips while a merge or rebase is in progress`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()

        repo.write(".git/MERGE_HEAD", repo.head() + "\n")
        assertContains(skipReason(cp.begin(listOf("a.txt"))), "merge is in progress")
        repo.delete(".git/MERGE_HEAD")

        Files.createDirectory(repo.root.resolve(".git/rebase-merge"))
        assertContains(skipReason(cp.begin(listOf("a.txt"))), "rebase is in progress")
    }

    @Test
    fun `GC-2 skips a path outside the repository`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val outside = Files.createTempFile("outside", ".txt")
        assertContains(skipReason(repo.checkpointer().begin(listOf(outside.toString()))), "outside the repository")
        assertContains(skipReason(repo.checkpointer().begin(listOf("../elsewhere.txt"))), "outside the repository")
    }

    @Test
    fun `GC-2 skips an ignored path`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        repo.write(".gitignore", "*.log\n")
        repo.commitAll("ignore logs")
        assertContains(skipReason(repo.checkpointer().begin(listOf("debug.log"))), "ignored")
    }

    @Test
    fun `GC-2 skips a directory and paths inside dot-git`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        repo.write("sub/x.txt", "x\n")
        repo.commitAll("sub")
        assertContains(skipReason(repo.checkpointer().begin(listOf("sub"))), "directory")
        assertContains(skipReason(repo.checkpointer().begin(listOf(".git/config"))), "inside .git")
    }

    @Test
    fun `GC-2 skips a path whose name has a newline or is not a valid path`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        assertIs<BeginResult.Skipped>(repo.checkpointer().begin(listOf("bad\nname.txt")))
        assertIs<BeginResult.Skipped>(repo.checkpointer().begin(listOf("bad\u0000name.txt")))
    }

    @Test
    fun `GC-2 skips paths that already have uncommitted changes so user work is never swept into a commit`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()

        repo.write("a.txt", "user wip\n")
        val unstaged = skipReason(cp.begin(listOf("a.txt")))
        assertContains(unstaged, "uncommitted changes")
        assertContains(unstaged, "'a.txt'") // the reported name must be exact, not a mangled prefix

        repo.git("add", "a.txt")
        assertContains(skipReason(cp.begin(listOf("a.txt"))), "'a.txt'")
        repo.git("reset", "-q", "HEAD", "a.txt")
        repo.git("checkout", "--", "a.txt")

        repo.write("mine.txt", "untracked user file\n")
        assertContains(skipReason(cp.begin(listOf("mine.txt"))), "'mine.txt'")

        // A clean path next to dirty ones is fine.
        repo.write("b.txt", "dirty\n")
        assertIs<BeginResult.Ready>(cp.begin(listOf("a.txt")))
        assertEquals(1, repo.commitCount(), "begin must not create commits")
    }

    @Test
    fun `GC-2 begin has no side effects`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        repo.write("b.txt", "staged\n")
        repo.git("add", "b.txt")
        repo.write("c.txt", "unstaged\n")
        val before = repo.status()

        assertIs<BeginResult.Ready>(repo.checkpointer().begin(listOf("a.txt")))

        assertEquals(before, repo.status())
        assertEquals(1, repo.commitCount())
    }

    // ------------------------------------------------ GC-3: only the agent's paths

    @Test
    fun `GC-3 a checkpoint contains only the agent's files and leaves other work exactly as it was`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        repo.write("b.txt", "b2 staged\n")
        repo.git("add", "b.txt") // user: staged
        repo.write("c.txt", "c2 unstaged\n") // user: unstaged
        repo.write("d.txt", "d untracked\n") // user: untracked
        val cp = repo.checkpointer()

        val result = repo.agentEdit(cp, listOf("a.txt", "new.txt")) {
            repo.write("a.txt", "a2\n")
            repo.write("new.txt", "brand new\n")
        }

        assertIs<CheckpointResult.Committed>(result)
        assertEquals(listOf("a.txt", "new.txt"), repo.filesIn("HEAD"))
        assertEquals(listOf(" M c.txt", "?? d.txt", "M  b.txt"), repo.status())
        assertEquals("b2 staged\n", repo.read("b.txt"))
        assertEquals("c2 unstaged\n", repo.read("c.txt"))
        assertEquals("d untracked\n", repo.read("d.txt"))
        assertEquals(listOf("a.txt", "new.txt"), result.checkpoint.paths)
    }

    @Test
    fun `GC-3 a deletion by the agent is checkpointed`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val result = repo.agentEdit(repo.checkpointer(), listOf("e.txt")) { repo.delete("e.txt") }
        assertIs<CheckpointResult.Committed>(result)
        assertEquals(listOf("e.txt"), repo.filesIn("HEAD"))
        assertFalse(repo.exists("e.txt"))
    }

    @Test
    fun `GC-3 a file in a directory that does not exist yet can be checkpointed`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val result = repo.agentEdit(repo.checkpointer(), listOf("new/deep/dir/f.txt")) { repo.write("new/deep/dir/f.txt", "hi\n") }
        assertIs<CheckpointResult.Committed>(result)
        assertEquals(listOf("new/deep/dir/f.txt"), result.checkpoint.paths)
    }

    @Test
    fun `GC-3 absolute and repo-relative spellings of a path are the same file`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val result = repo.agentEdit(repo.checkpointer(), listOf(repo.root.resolve("a.txt").toString(), "./a.txt", "sub/../a.txt")) {
            repo.write("a.txt", "a2\n")
        }
        assertIs<CheckpointResult.Committed>(result)
        assertEquals(listOf("a.txt"), result.checkpoint.paths)
    }

    // -------------------------------------------------- GC-4: commit properties

    @Test
    fun `GC-4 the commit carries the trailer and the agent identity even with no git identity configured`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val result = repo.agentEdit(repo.checkpointer(), listOf("a.txt"), "edit a.txt\n\nlonger explanation") { repo.write("a.txt", "a2\n") }
        assertIs<CheckpointResult.Committed>(result)

        assertEquals("DroidCommand AI", repo.git("log", "-1", "--format=%an").trim())
        assertEquals("droidcommand-ai@localhost", repo.git("log", "-1", "--format=%ae").trim())
        assertEquals("DroidCommand AI", repo.git("log", "-1", "--format=%cn").trim())
        val message = repo.message()
        assertTrue(message.startsWith("edit a.txt\n\nlonger explanation\n\nDroidCommand-Checkpoint: v1"), message)
        assertEquals(result.checkpoint.commit, repo.head())
    }

    @Test
    fun `GC-4 a configured GPG signing requirement does not block or prompt`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        repo.git("config", "commit.gpgsign", "true")
        repo.git("config", "gpg.program", "/bin/false")
        // Prove the config really would break a plain commit, so the assertion below means something.
        val plain = repo.gitRaw("commit", "--allow-empty", "-qm", "plain")
        assertTrue(plain.first != 0, "test premise: signing must fail here: ${plain.second}")

        val result = repo.agentEdit(repo.checkpointer(), listOf("a.txt")) { repo.write("a.txt", "a2\n") }
        assertIs<CheckpointResult.Committed>(result)
    }

    @Test
    fun `GC-4 the user's hooks still run`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val hook = repo.root.resolve(".git/hooks/pre-commit")
        Files.writeString(hook, "#!/bin/sh\ntouch .git/hook-ran\nexit 0\n")
        hook.toFile().setExecutable(true)

        val result = repo.agentEdit(repo.checkpointer(), listOf("a.txt")) { repo.write("a.txt", "a2\n") }

        assertIs<CheckpointResult.Committed>(result)
        assertTrue(repo.exists(".git/hook-ran"), "pre-commit hook must not be bypassed")
    }

    @Test
    fun `GC-4 a failing hook yields Failed and leaves the edit in place but unstaged`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        repo.write("b.txt", "b2 staged\n")
        repo.git("add", "b.txt")
        val hook = repo.root.resolve(".git/hooks/pre-commit")
        Files.writeString(hook, "#!/bin/sh\necho 'lint failed' >&2\nexit 1\n")
        hook.toFile().setExecutable(true)
        val before = repo.commitCount()

        val result = repo.agentEdit(repo.checkpointer(), listOf("a.txt")) { repo.write("a.txt", "a2\n") }

        assertIs<CheckpointResult.Failed>(result)
        assertContains(result.reason, "lint failed")
        assertEquals(before, repo.commitCount())
        assertEquals("a2\n", repo.read("a.txt"))
        assertEquals(listOf(" M a.txt", "M  b.txt"), repo.status()) // a.txt not staged; user's b.txt still staged
    }

    // ---------------------------------------------------------- GC-5: no change

    @Test
    fun `GC-5 no edit means no commit`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val result = repo.agentEdit(repo.checkpointer(), listOf("a.txt")) { }
        assertEquals(CheckpointResult.NoChanges, result)
        assertEquals(1, repo.commitCount())
    }

    @Test
    fun `GC-5 an edit that restores the original content means no commit`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val result = repo.agentEdit(repo.checkpointer(), listOf("a.txt")) {
            repo.write("a.txt", "temporary\n")
            repo.write("a.txt", "a1\n")
        }
        assertEquals(CheckpointResult.NoChanges, result)
        assertEquals(1, repo.commitCount())
        assertEquals(emptyList(), repo.status())
    }

    // ------------------------------------------------------ GC-11: how git runs

    @Test
    fun `GC-11 nothing happens when git is not on the executable allow-list`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer(executables = emptySet())
        assertContains(skipReason(cp.begin(listOf("a.txt"))), "could not be run")
        val undo = cp.undo()
        assertIs<UndoResult.Failed>(undo)
        assertContains(undo.reason, "could not be run")
        assertIs<CheckpointList.Failed>(cp.checkpoints())
    }

    @Test
    fun `GC-11 nothing happens when the repository directory is not authorized`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer(workingDirs = emptyList())
        assertContains(skipReason(cp.begin(listOf("a.txt"))), "could not be run")
    }

    @Test
    fun `GC-11 glob characters in a file name are literal and never pull in other files`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.empty()
        repo.git("init", "-q", "-b", "main")
        repo.write("we*rd.txt", "star1\n")
        repo.write("weXrd.txt", "other1\n")
        repo.commitAll("base")
        repo.write("weXrd.txt", "user edit\n") // the file a glob would also match

        val result = repo.agentEdit(repo.checkpointer(), listOf("we*rd.txt")) { repo.write("we*rd.txt", "star2\n") }

        assertIs<CheckpointResult.Committed>(result)
        assertEquals(listOf("we*rd.txt"), repo.filesIn("HEAD"))
        assertEquals(listOf(" M weXrd.txt"), repo.status())
    }

    @Test
    fun `GC-11 a file name that looks like shell syntax is just a file name`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val nasty = "x; touch pwned.txt; \$(touch pwned2.txt) 'q'.txt"

        val result = repo.agentEdit(repo.checkpointer(), listOf(nasty)) { repo.write(nasty, "hi\n") }

        assertIs<CheckpointResult.Committed>(result)
        assertEquals(listOf(nasty), repo.filesIn("HEAD"))
        assertFalse(repo.exists("pwned.txt"))
        assertFalse(repo.exists("pwned2.txt"))
    }

    @Test
    fun `GC-11 non-ASCII file names round-trip through commit and undo`() {
        if (!TestRepo.gitAvailable()) return
        // Java encodes process arguments and file names with sun.jnu.encoding (from the locale). Under a
        // POSIX locale Path.of itself rejects such names; GitCheckpointer then reports Skipped, which is the
        // correct degradation, so this round trip is only meaningful on a UTF-8 JVM.
        if (System.getProperty("sun.jnu.encoding")?.uppercase()?.replace("-", "") != "UTF8") return
        val repo = TestRepo.seeded()
        val name = "données/日本語 ü.txt"
        val cp = repo.checkpointer()

        val committed = repo.agentEdit(cp, listOf(name)) { repo.write(name, "hi\n") }
        assertIs<CheckpointResult.Committed>(committed)
        assertEquals(listOf(name), committed.checkpoint.paths)

        val undone = cp.undo()
        assertIs<UndoResult.Undone>(undone)
        assertFalse(repo.exists(name))
    }
}
