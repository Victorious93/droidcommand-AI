package ai.droidcommand.git

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** GC-6 .. GC-10: undo restores exactly the checkpoint's files, records it as a new commit, and refuses instead of overwriting. */
class UndoTest {
    private fun committed(result: CheckpointResult): Checkpoint {
        assertIs<CheckpointResult.Committed>(result)
        return result.checkpoint
    }

    // ------------------------------------------------------ GC-6 / GC-9: what undo does

    @Test
    fun `GC-6 undo restores a modified file and records a new commit without rewriting history`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        val checkpoint = committed(repo.agentEdit(cp, listOf("a.txt")) { repo.write("a.txt", "a2\n") })
        val checkpointParent = repo.git("rev-parse", "HEAD^").trim()

        val result = cp.undo()

        assertIs<UndoResult.Undone>(result)
        assertEquals(checkpoint.commit, result.checkpoint)
        assertEquals("a1\n", repo.read("a.txt"))
        assertEquals(3, repo.commitCount(), "base, checkpoint, undo")
        assertEquals(checkpoint.commit, repo.git("rev-parse", "HEAD^").trim(), "the checkpoint commit is still in history")
        assertEquals(checkpointParent, repo.git("rev-parse", "HEAD~2").trim())
        assertTrue(repo.message().contains("DroidCommand-Undo: ${checkpoint.commit}"), repo.message())
        assertEquals(listOf("a.txt"), repo.filesIn("HEAD"))
        assertEquals(emptyList(), repo.status())
        assertEquals(result.undoCommit, repo.head())
    }

    @Test
    fun `GC-9 undo removes a file the checkpoint added`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        committed(repo.agentEdit(cp, listOf("sub/new.txt")) { repo.write("sub/new.txt", "n\n") })

        assertIs<UndoResult.Undone>(cp.undo())

        assertFalse(repo.exists("sub/new.txt"))
        assertEquals(emptyList(), repo.status())
    }

    @Test
    fun `GC-9 undo restores a file the checkpoint deleted`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        committed(repo.agentEdit(cp, listOf("e.txt")) { repo.delete("e.txt") })

        assertIs<UndoResult.Undone>(cp.undo())

        assertEquals("e1\n", repo.read("e.txt"))
        assertEquals(emptyList(), repo.status())
    }

    @Test
    fun `GC-9 one checkpoint that modified, added and deleted files is undone as a whole`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        committed(
            repo.agentEdit(cp, listOf("a.txt", "e.txt", "n.txt")) {
                repo.write("a.txt", "a2\n")
                repo.delete("e.txt")
                repo.write("n.txt", "n\n")
            },
        )

        val result = cp.undo()

        assertIs<UndoResult.Undone>(result)
        assertEquals(listOf("a.txt", "e.txt", "n.txt"), result.paths)
        assertEquals("a1\n", repo.read("a.txt"))
        assertEquals("e1\n", repo.read("e.txt"))
        assertFalse(repo.exists("n.txt"))
        assertEquals(emptyList(), repo.status())
    }

    @Test
    fun `GC-9 an executable-bit change by the agent is undone too`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        repo.write("run.sh", "#!/bin/sh\n")
        repo.commitAll("script")
        val cp = repo.checkpointer()
        committed(repo.agentEdit(cp, listOf("run.sh")) { repo.root.resolve("run.sh").toFile().setExecutable(true) })
        assertTrue(repo.git("ls-files", "-s", "run.sh").startsWith("100755"))

        assertIs<UndoResult.Undone>(cp.undo())

        assertTrue(repo.git("ls-files", "-s", "run.sh").startsWith("100644"))
    }

    @Test
    fun `GC-4 undo also commits without prompting when GPG signing is configured`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        committed(repo.agentEdit(cp, listOf("a.txt")) { repo.write("a.txt", "a2\n") })
        repo.git("config", "commit.gpgsign", "true")
        repo.git("config", "gpg.program", "/bin/false")
        assertTrue(repo.gitRaw("commit", "--allow-empty", "-qm", "plain").first != 0, "test premise: signing must fail here")

        assertIs<UndoResult.Undone>(cp.undo())
        assertEquals("a1\n", repo.read("a.txt"))
    }

    // ------------------------------------------------------- GC-7: user work survives

    @Test
    fun `GC-7 undo leaves staged, unstaged, untracked work and later commits to other files alone`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        committed(repo.agentEdit(cp, listOf("a.txt", "n.txt")) {
            repo.write("a.txt", "a2\n")
            repo.write("n.txt", "n\n")
        })

        repo.write("e.txt", "e2 committed by user\n")
        repo.commitAll("user commit after the checkpoint")
        repo.write("b.txt", "b2 staged\n")
        repo.git("add", "b.txt")
        repo.write("c.txt", "c2 unstaged\n")
        repo.write("d.txt", "d untracked\n")
        val userCommit = repo.head()

        val result = cp.undo()

        assertIs<UndoResult.Undone>(result)
        assertEquals(listOf("a.txt", "n.txt"), repo.filesIn("HEAD"), "the undo commit touches only the agent's files")
        assertEquals(userCommit, repo.git("rev-parse", "HEAD^").trim(), "the user's commit is untouched history")
        assertEquals("e2 committed by user\n", repo.read("e.txt"))
        assertEquals("b2 staged\n", repo.read("b.txt"))
        assertEquals("c2 unstaged\n", repo.read("c.txt"))
        assertEquals("d untracked\n", repo.read("d.txt"))
        assertEquals(listOf(" M c.txt", "?? d.txt", "M  b.txt"), repo.status())
        assertEquals("a1\n", repo.read("a.txt"))
        assertFalse(repo.exists("n.txt"))
    }

    // ------------------------------------------------------------ GC-8: refusals

    @Test
    fun `GC-8 undo refuses when a checkpoint path has an unstaged change and changes nothing`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        committed(repo.agentEdit(cp, listOf("a.txt", "n.txt")) {
            repo.write("a.txt", "a2\n")
            repo.write("n.txt", "n\n")
        })
        repo.write("a.txt", "a2 plus my own tweak\n")
        val headBefore = repo.head()

        val result = cp.undo()

        assertIs<UndoResult.Refused>(result)
        assertEquals(listOf("a.txt"), result.blockingPaths)
        assertContains(result.reason, "'a.txt'")
        assertEquals(headBefore, repo.head())
        assertEquals("a2 plus my own tweak\n", repo.read("a.txt"))
        assertEquals("n\n", repo.read("n.txt"), "the other checkpoint file must not be half-undone")
        assertEquals(listOf(" M a.txt"), repo.status())
    }

    @Test
    fun `GC-8 undo refuses when a checkpoint path has a staged change`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        committed(repo.agentEdit(cp, listOf("a.txt")) { repo.write("a.txt", "a2\n") })
        repo.write("a.txt", "a3 staged\n")
        repo.git("add", "a.txt")

        val result = cp.undo()

        assertIs<UndoResult.Refused>(result)
        assertEquals("a3 staged\n", repo.read("a.txt"))
        assertEquals(listOf("M  a.txt"), repo.status())
    }

    @Test
    fun `GC-8 undo refuses when a later commit changed a checkpoint path`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        committed(repo.agentEdit(cp, listOf("a.txt")) { repo.write("a.txt", "a2\n") })
        repo.write("a.txt", "a3 by user, committed\n")
        repo.commitAll("user follow-up")
        val headBefore = repo.head()

        val result = cp.undo()

        assertIs<UndoResult.Refused>(result)
        assertEquals(listOf("a.txt"), result.blockingPaths)
        assertContains(result.reason, "later commit")
        assertEquals(headBefore, repo.head())
        assertEquals("a3 by user, committed\n", repo.read("a.txt"))
    }

    @Test
    fun `GC-8 undo refuses while a merge is in progress`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        committed(repo.agentEdit(cp, listOf("a.txt")) { repo.write("a.txt", "a2\n") })
        repo.write(".git/MERGE_HEAD", repo.head() + "\n")

        val result = cp.undo()

        assertIs<UndoResult.Refused>(result)
        assertContains(result.reason, "merge")
        assertEquals("a2\n", repo.read("a.txt"))
    }

    @Test
    fun `GC-8 a failing hook during undo rolls the files back to where they were`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        committed(repo.agentEdit(cp, listOf("a.txt", "n.txt")) {
            repo.write("a.txt", "a2\n")
            repo.write("n.txt", "n\n")
        })
        val hook = repo.root.resolve(".git/hooks/pre-commit")
        java.nio.file.Files.writeString(hook, "#!/bin/sh\nexit 1\n")
        hook.toFile().setExecutable(true)
        val headBefore = repo.head()

        val result = cp.undo()

        assertIs<UndoResult.Failed>(result)
        assertContains(result.reason, "put back")
        assertEquals(headBefore, repo.head())
        assertEquals("a2\n", repo.read("a.txt"))
        assertEquals("n\n", repo.read("n.txt"))
        assertEquals(emptyList(), repo.status())
    }

    // ---------------------------------------------------- GC-10: which checkpoint

    @Test
    fun `GC-10 with nothing to undo the result says so`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        assertEquals(UndoResult.NothingToUndo, repo.checkpointer().undo())
    }

    @Test
    fun `GC-10 undo with no argument walks back through checkpoints newest first and never repeats one`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        val first = committed(repo.agentEdit(cp, listOf("a.txt"), "edit a") { repo.write("a.txt", "a2\n") })
        val second = committed(repo.agentEdit(cp, listOf("b.txt"), "edit b") { repo.write("b.txt", "b2\n") })

        val listed = cp.checkpoints()
        assertIs<CheckpointList.Listed>(listed)
        assertEquals(listOf(second.commit, first.commit), listed.items.map { it.commit })
        assertEquals(listOf("edit b", "edit a"), listed.items.map { it.subject })
        assertEquals(listOf(false, false), listed.items.map { it.undone })

        val undoSecond = cp.undo()
        assertIs<UndoResult.Undone>(undoSecond)
        assertEquals(second.commit, undoSecond.checkpoint)
        assertEquals("b1\n", repo.read("b.txt"))
        assertEquals("a2\n", repo.read("a.txt"))

        val undoFirst = cp.undo()
        assertIs<UndoResult.Undone>(undoFirst)
        assertEquals(first.commit, undoFirst.checkpoint)
        assertEquals("a1\n", repo.read("a.txt"))

        assertEquals(UndoResult.NothingToUndo, cp.undo())
        val after = cp.checkpoints()
        assertIs<CheckpointList.Listed>(after)
        assertTrue(after.items.all { it.undone }, "both are marked undone: $after")
    }

    @Test
    fun `GC-10 an older checkpoint can be undone by abbreviated id when its files were not touched since`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        val first = committed(repo.agentEdit(cp, listOf("a.txt")) { repo.write("a.txt", "a2\n") })
        val second = committed(repo.agentEdit(cp, listOf("b.txt")) { repo.write("b.txt", "b2\n") })

        val result = cp.undo(first.commit.take(10))

        assertIs<UndoResult.Undone>(result)
        assertEquals(first.commit, result.checkpoint)
        assertEquals("a1\n", repo.read("a.txt"))
        assertEquals("b2\n", repo.read("b.txt"), "the newer checkpoint's file is left alone")
        val next = cp.undo()
        assertIs<UndoResult.Undone>(next)
        assertEquals(second.commit, next.checkpoint)
    }

    @Test
    fun `GC-10 an already-undone checkpoint is refused when named explicitly`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        val only = committed(repo.agentEdit(cp, listOf("a.txt")) { repo.write("a.txt", "a2\n") })
        assertIs<UndoResult.Undone>(cp.undo())

        val again = cp.undo(only.commit)

        assertIs<UndoResult.Refused>(again)
        assertContains(again.reason, "already undone")
    }

    @Test
    fun `GC-10 undoing an older checkpoint whose file a newer checkpoint also changed is refused`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        val first = committed(repo.agentEdit(cp, listOf("a.txt")) { repo.write("a.txt", "a2\n") })
        committed(repo.agentEdit(cp, listOf("a.txt")) { repo.write("a.txt", "a3\n") })

        val result = cp.undo(first.commit)

        assertIs<UndoResult.Refused>(result)
        assertEquals(listOf("a.txt"), result.blockingPaths)
        assertEquals("a3\n", repo.read("a.txt"))
    }

    @Test
    fun `GC-10 only commits with the checkpoint trailer as their last paragraph count, so quoted text cannot spoof one`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        repo.write("a.txt", "a2\n")
        repo.git("add", "a.txt")
        repo.git("commit", "-q", "-m", "user commit", "-m", "DroidCommand-Checkpoint: v1", "-m", "and then some more text")
        val cp = repo.checkpointer()

        assertEquals(UndoResult.NothingToUndo, cp.undo())
        assertEquals("a2\n", repo.read("a.txt"))
    }

    @Test
    fun `GC-10 an argument that is not a commit id never reaches git`() {
        if (!TestRepo.gitAvailable()) return
        val repo = TestRepo.seeded()
        val cp = repo.checkpointer()
        committed(repo.agentEdit(cp, listOf("a.txt")) { repo.write("a.txt", "a2\n") })

        for (hostile in listOf("--output=hacked.txt", "HEAD", "abc; touch hacked.txt", "../../etc/passwd")) {
            val result = cp.undo(hostile)
            assertIs<UndoResult.Failed>(result, hostile)
        }
        assertFalse(repo.exists("hacked.txt"))
        assertEquals("a2\n", repo.read("a.txt"))
    }
}
