package ai.droidcommand.git

import ai.droidcommand.shell.ShellExecutor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** A commit made by [GitCheckpointer.begin]/`commit`. [paths] are repository-relative with `/` separators. */
data class Checkpoint(val commit: String, val paths: List<String>, val subject: String)

/** One entry of [GitCheckpointer.checkpoints]: an earlier agent checkpoint and whether a later undo already reverted it. */
data class CheckpointInfo(val commit: String, val subject: String, val undone: Boolean)

sealed class BeginResult {
    /** Safe to let the agent edit [paths]; call [commit] afterwards to record the edit. */
    class Ready internal constructor(private val owner: GitCheckpointer, val paths: List<String>) : BeginResult() {
        fun commit(message: String): CheckpointResult = owner.commitPaths(paths, message)
    }

    /** Checkpointing is not possible or not safe right now; the edit itself may still proceed, just unrecorded. */
    data class Skipped(val reason: String) : BeginResult()
}

sealed class CheckpointResult {
    data class Committed(val checkpoint: Checkpoint) : CheckpointResult()

    /** The paths ended up identical to `HEAD`; no empty commit was made. */
    data object NoChanges : CheckpointResult()

    data class Failed(val reason: String) : CheckpointResult()
}

sealed class UndoResult {
    data class Undone(val checkpoint: String, val undoCommit: String, val paths: List<String>) : UndoResult()

    /** Undoing could lose or overwrite someone's work, so nothing was changed. [blockingPaths] says which paths. */
    data class Refused(val reason: String, val blockingPaths: List<String> = emptyList()) : UndoResult()

    data object NothingToUndo : UndoResult()

    data class Failed(val reason: String) : UndoResult()
}

sealed class CheckpointList {
    data class Listed(val items: List<CheckpointInfo>) : CheckpointList()

    data class Failed(val reason: String) : CheckpointList()
}

/**
 * Optional automatic commits of agent changes, plus an undo that cannot clobber anyone else's work.
 *
 * **The safety rule:** every git operation here is limited by pathspec to the files the agent touched,
 * and pathspecs are literal (`GIT_LITERAL_PATHSPECS`). Nothing uses `reset --hard`, `clean`, `stash`,
 * `checkout .`, `commit -a`, or any history rewrite, so unrelated staged, unstaged and untracked work
 * is untouched *by construction*, not by care.
 *
 * Flow: `begin(paths)` before the agent edits → the edit → `Ready.commit(message)`; later [undo].
 *
 * - [begin] refuses (returns [BeginResult.Skipped]) whenever a checkpoint could sweep in something that
 *   is not the agent's: a path with uncommitted changes, an untracked pre-existing file, a repository
 *   mid-merge/rebase, and so on. The user's work-in-progress is never committed on their behalf.
 * - Commits carry a `DroidCommand-Checkpoint: v1` trailer, use [authorName]/[authorEmail] regardless of
 *   the user's git identity, disable GPG signing for that one command, and run the user's hooks (a
 *   failing hook yields [CheckpointResult.Failed] and leaves the index as it was).
 * - [undo] restores the checkpoint's paths to their pre-checkpoint content and records that as a *new*
 *   commit with a `DroidCommand-Undo: <sha>` trailer. It refuses if any of those paths has uncommitted
 *   changes or was changed by a later commit, rather than merging.
 *
 * Not thread-safe: callers run tools one at a time.
 *
 * git runs only through [shell], so the caller's `ShellSecurityPolicy` must allow [gitExecutable] and
 * [repoDir]; otherwise everything reports "could not be run" instead of doing anything.
 */
class GitCheckpointer(
    shell: ShellExecutor,
    repoDir: Path,
    gitExecutable: String = "git",
    private val authorName: String = "DroidCommand AI",
    private val authorEmail: String = "droidcommand-ai@localhost",
    timeoutMillis: Long = 60_000,
    extraEnvironment: Map<String, String> = emptyMap(),
    private val maxScanCommits: Int = 1_000,
) {
    private val repoDir: Path = repoDir.toAbsolutePath().normalize()
    private val git = GitCli(shell, gitExecutable, this.repoDir.toString(), timeoutMillis, extraEnvironment)

    // ------------------------------------------------------------------ begin

    fun begin(paths: List<String>): BeginResult = try {
        beginUnchecked(paths)
    } catch (e: GitException) {
        BeginResult.Skipped("git could not be run: ${e.message}")
    } catch (e: IOException) {
        BeginResult.Skipped("could not inspect the repository: ${e.message}")
    } catch (e: java.nio.file.InvalidPathException) {
        BeginResult.Skipped("a path is not valid on this filesystem: ${e.message}")
    }

    private fun beginUnchecked(paths: List<String>): BeginResult {
        if (paths.isEmpty()) return BeginResult.Skipped("no paths were supplied")
        val top = topLevel() ?: return BeginResult.Skipped("'$repoDir' is not inside a git repository")
        if (!git.run(listOf("rev-parse", "--verify", "--quiet", "HEAD^{commit}"), readOnly = true).ok) {
            return BeginResult.Skipped("the repository has no commits yet")
        }
        operationInProgress()?.let { return BeginResult.Skipped("a $it is in progress in this repository") }

        val relative = ArrayList<String>()
        for (raw in paths) {
            val rel = relativize(top, raw) ?: return BeginResult.Skipped("'$raw' is outside the repository at '$top'")
            invalidPathReason(top, rel)?.let { return BeginResult.Skipped("'$rel' $it") }
            relative.add(rel)
        }
        val unique = relative.distinct().sorted()

        for (rel in unique) {
            // exit 0 = ignored, 1 = not ignored, anything else = error. Tracked files are never "ignored" here.
            // check-ignore takes plain paths (no globbing) and rejects literal-pathspec mode outright, so that
            // variable is switched off for this one command. Any exit other than 0/1 fails closed.
            val ignored = git.run(listOf("check-ignore", "-q", "--", rel), environment = mapOf("GIT_LITERAL_PATHSPECS" to "0"), readOnly = true)
            when (ignored.exitCode) {
                0 -> return BeginResult.Skipped("'$rel' is ignored by git, so it cannot be checkpointed")
                1 -> Unit
                else -> return BeginResult.Skipped("could not determine whether '$rel' is ignored: ${ignored.stderr.trim()}")
            }
        }

        val status = git.run(listOf("status", "--porcelain=v1", "-uall", "--") + unique, readOnly = true)
        if (!status.ok) return BeginResult.Skipped("git status failed: ${status.stderr.trim()}")
        if (status.text.isNotEmpty()) {
            val dirty = statusPaths(status.stdout)
            return BeginResult.Skipped(
                "uncommitted changes exist in ${dirty.joinToString(", ") { "'$it'" }}; not checkpointing so your work-in-progress is never included in an agent commit",
            )
        }
        return BeginResult.Ready(this, unique)
    }

    // ----------------------------------------------------------------- commit

    internal fun commitPaths(paths: List<String>, message: String): CheckpointResult = try {
        commitUnchecked(paths, message)
    } catch (e: GitException) {
        CheckpointResult.Failed("git could not be run: ${e.message}")
    } catch (e: IOException) {
        CheckpointResult.Failed("could not inspect the repository: ${e.message}")
    }

    private fun commitUnchecked(paths: List<String>, message: String): CheckpointResult {
        val add = git.run(listOf("add", "-A", "--") + paths)
        if (!add.ok) return CheckpointResult.Failed("git add failed: ${add.stderr.trim()}")

        // Exit 0: nothing staged for these paths; 1: something is.
        when (val staged = git.run(listOf("diff", "--cached", "--quiet", "--") + paths, readOnly = true).exitCode) {
            0 -> return CheckpointResult.NoChanges
            1 -> Unit
            else -> {
                resetIndex(paths)
                return CheckpointResult.Failed("git diff --cached failed (exit $staged)")
            }
        }

        val subject = subjectOf(message)
        val commit = git.run(
            listOf("-c", "commit.gpgsign=false", "commit", "--only", "-q", "-m", commitText(message, subject), "-m", CHECKPOINT_TRAILER, "--") + paths,
            environment = identityEnvironment(),
        )
        if (!commit.ok) {
            resetIndex(paths) // leaves the agent's edit in the working tree, unstaged
            return CheckpointResult.Failed("git commit failed (exit ${commit.exitCode}): ${(commit.stderr + commit.stdout).trim()}")
        }
        val sha = git.run(listOf("rev-parse", "HEAD"), readOnly = true).text
        return CheckpointResult.Committed(Checkpoint(sha, paths, subject))
    }

    // ------------------------------------------------------------------- undo

    /** Newest first, at most [limit] entries, scanning at most `maxScanCommits` commits back from HEAD. */
    fun checkpoints(limit: Int = 20): CheckpointList = try {
        CheckpointList.Listed(scan().take(limit))
    } catch (e: GitException) {
        CheckpointList.Failed(e.message ?: "git failed")
    }

    /**
     * Undoes [checkpoint] (a full or abbreviated commit id, matched only against known checkpoints and
     * never passed to git as typed) or, when null, the most recent checkpoint not yet undone.
     */
    fun undo(checkpoint: String? = null): UndoResult = try {
        undoUnchecked(checkpoint)
    } catch (e: GitException) {
        UndoResult.Failed("git could not be run: ${e.message}")
    } catch (e: IOException) {
        UndoResult.Failed("could not inspect the repository: ${e.message}")
    }

    private fun undoUnchecked(requested: String?): UndoResult {
        topLevel() ?: return UndoResult.Failed("'$repoDir' is not inside a git repository")
        operationInProgress()?.let { return UndoResult.Refused("a $it is in progress in this repository; finish or abort it first") }

        val known = scan()
        val target = if (requested == null) {
            known.firstOrNull { !it.undone } ?: return UndoResult.NothingToUndo
        } else {
            val wanted = requested.trim().lowercase()
            if (!COMMIT_ID.matches(wanted)) return UndoResult.Failed("'$requested' is not a commit id")
            val matches = known.filter { it.commit.startsWith(wanted) }
            when {
                matches.isEmpty() -> return UndoResult.Failed("no agent checkpoint matches '$requested' (see the checkpoint list)")
                matches.size > 1 -> return UndoResult.Failed("'$requested' matches ${matches.size} checkpoints; use a longer id")
                matches[0].undone -> return UndoResult.Refused("checkpoint ${matches[0].commit.take(8)} was already undone")
                else -> matches[0]
            }
        }

        val changes = changedFiles(target.commit)
        if (changes.isEmpty()) return UndoResult.Failed("checkpoint ${target.commit.take(8)} changed no files")
        val parentRef = git.run(listOf("rev-parse", "--verify", "--quiet", "${target.commit}^"), readOnly = true)
        if (!parentRef.ok) return UndoResult.Failed("checkpoint ${target.commit.take(8)} has no parent commit to restore from")
        val parent = parentRef.text
        val paths = changes.map { it.second }.sorted()

        val status = git.run(listOf("status", "--porcelain=v1", "-uall", "--") + paths, readOnly = true)
        if (!status.ok) return UndoResult.Failed("git status failed: ${status.stderr.trim()}")
        if (status.text.isNotEmpty()) {
            val dirty = statusPaths(status.stdout)
            return UndoResult.Refused(
                "uncommitted changes exist in ${dirty.joinToString(", ") { "'$it'" }}; commit or stash them, then undo again. Nothing was changed.",
                dirty,
            )
        }
        val later = git.run(listOf("diff", "--name-only", "-z", target.commit, "HEAD", "--") + paths, readOnly = true)
        if (!later.ok) return UndoResult.Failed("git diff failed: ${later.stderr.trim()}")
        val changedLater = splitNul(later.stdout)
        if (changedLater.isNotEmpty()) {
            return UndoResult.Refused(
                "${changedLater.joinToString(", ") { "'$it'" }} changed in a later commit since checkpoint ${target.commit.take(8)}; undoing would overwrite that work. Nothing was changed.",
                changedLater,
            )
        }

        val added = changes.filter { it.first == 'A' }.map { it.second }
        val others = changes.filter { it.first != 'A' }.map { it.second }
        if (added.isNotEmpty()) {
            val removal = git.run(listOf("rm", "-q", "--") + added)
            if (!removal.ok) return rollbackUndo(paths, "git rm failed: ${removal.stderr.trim()}")
        }
        if (others.isNotEmpty()) {
            val restore = git.run(listOf("checkout", parent, "--") + others)
            if (!restore.ok) return rollbackUndo(paths, "git checkout failed: ${restore.stderr.trim()}")
        }

        val subject = "Undo: ${target.subject}".take(SUBJECT_LIMIT)
        val commit = git.run(
            listOf(
                "-c", "commit.gpgsign=false", "commit", "--only", "-q",
                "-m", subject,
                "-m", "Restores ${paths.size} file(s) to their state before agent checkpoint ${target.commit.take(8)}.",
                "-m", "$UNDO_TRAILER_PREFIX${target.commit}",
                "--",
            ) + paths,
            environment = identityEnvironment(),
        )
        if (!commit.ok) return rollbackUndo(paths, "git commit failed (exit ${commit.exitCode}): ${(commit.stderr + commit.stdout).trim()}")
        val sha = git.run(listOf("rev-parse", "HEAD"), readOnly = true).text
        return UndoResult.Undone(target.commit, sha, paths)
    }

    /** Puts the paths back exactly as `HEAD` has them (safe: they were clean before the undo started). */
    private fun rollbackUndo(paths: List<String>, why: String): UndoResult {
        val index = git.run(listOf("reset", "-q", "HEAD", "--") + paths)
        val tree = git.run(listOf("checkout", "HEAD", "--") + paths)
        val note = if (index.ok && tree.ok) "; the files were put back as they were" else "; rolling back also failed, run `git status` to inspect ${paths.joinToString(", ")}"
        return UndoResult.Failed(why + note)
    }

    // ---------------------------------------------------------------- helpers

    private fun topLevel(): Path? {
        val out = git.run(listOf("rev-parse", "--show-toplevel"), readOnly = true)
        if (!out.ok || out.text.isEmpty()) return null
        return Path.of(out.text).toRealPath()
    }

    private fun operationInProgress(): String? {
        val out = git.run(listOf("rev-parse", "--absolute-git-dir"), readOnly = true)
        if (!out.ok) return null
        val gitDir = Path.of(out.text)
        return IN_PROGRESS_MARKERS.firstOrNull { Files.exists(gitDir.resolve(it.first)) }?.second
    }

    /** Repository-relative, `/`-separated path of [raw] (absolute, or relative to [repoDir]), or null if it lies outside [top]. Symlinks are resolved. */
    private fun relativize(top: Path, raw: String): String? {
        val given = Path.of(raw)
        val absolute = (if (given.isAbsolute) given else repoDir.resolve(given)).normalize()
        val resolved = realOrLexical(absolute)
        if (!resolved.startsWith(top)) return null
        return top.relativize(resolved).joinToString("/") { it.toString() }
    }

    /** Real path of the nearest existing ancestor plus the not-yet-existing tail, so a file the agent is about to create can be named. */
    private fun realOrLexical(path: Path): Path {
        var existing: Path? = path
        val tail = ArrayDeque<Path>()
        while (existing != null && !Files.exists(existing)) {
            existing.fileName?.let { tail.addFirst(it) }
            existing = existing.parent
        }
        val base = existing?.toRealPath() ?: return path
        return tail.fold(base) { acc, part -> acc.resolve(part) }
    }

    private fun invalidPathReason(top: Path, rel: String): String? = when {
        rel.isEmpty() -> "is the repository root; pass file paths"
        rel.contains('\n') || rel.contains('\u0000') -> "contains a newline or NUL character, which checkpoints do not support"
        rel == ".git" || rel.startsWith(".git/") -> "is inside .git"
        Files.isDirectory(top.resolve(rel)) -> "is a directory; pass file paths"
        else -> null
    }

    /** Paths named by `status --porcelain=v1` lines (`XY path`, or `XY old -> new`). Only used to build messages. */
    private fun statusPaths(porcelain: String): List<String> =
        porcelain.lines().filter { it.length > 3 }.map { it.substring(3).substringAfter(" -> ") }.distinct()

    private fun splitNul(text: String): List<String> = text.trimEnd('\n').split('\u0000').filter { it.isNotEmpty() }

    /** (status letter, path) for every file `commit` changed relative to its first parent. */
    private fun changedFiles(commit: String): List<Pair<Char, String>> {
        val out = git.run(listOf("diff-tree", "--no-commit-id", "--name-status", "-r", "-z", "--no-renames", commit), readOnly = true)
        if (!out.ok) throw GitException("git diff-tree failed: ${out.stderr.trim()}")
        val tokens = splitNul(out.stdout)
        return tokens.chunked(2).filter { it.size == 2 }.map { it[0].first() to it[1] }
    }

    private fun scan(): List<CheckpointInfo> {
        val log = git.run(listOf("log", "-n", "$maxScanCommits", "--format=%H%x1f%B%x1e", "HEAD"), readOnly = true)
        if (!log.ok) throw GitException("git log failed: ${log.stderr.trim()}")

        class Entry(val sha: String, val subject: String, val trailers: List<String>)

        val entries = log.stdout.split('\u001e').mapNotNull { raw ->
            val entry = raw.trim('\n')
            val sep = entry.indexOf('\u001f')
            if (sep < 0) return@mapNotNull null
            val message = entry.substring(sep + 1).trimEnd()
            // Only the message's last paragraph counts, so text quoted in a body can never pose as a trailer.
            val trailers = message.split("\n\n").last().lines().map { it.trim() }
            Entry(entry.substring(0, sep).trim(), message.lineSequence().firstOrNull().orEmpty(), trailers)
        }
        val undone = entries.flatMap { it.trailers }.filter { it.startsWith(UNDO_TRAILER_PREFIX) }
            .map { it.removePrefix(UNDO_TRAILER_PREFIX).trim() }.toSet()
        return entries.filter { CHECKPOINT_TRAILER in it.trailers }.map { CheckpointInfo(it.sha, it.subject, it.sha in undone) }
    }

    private fun resetIndex(paths: List<String>) {
        runCatching { git.run(listOf("reset", "-q", "HEAD", "--") + paths) }
    }

    private fun identityEnvironment() = mapOf(
        "GIT_AUTHOR_NAME" to authorName,
        "GIT_AUTHOR_EMAIL" to authorEmail,
        "GIT_COMMITTER_NAME" to authorName,
        "GIT_COMMITTER_EMAIL" to authorEmail,
    )

    private fun subjectOf(message: String): String {
        val first = message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "agent changes"
        return first.replace("\u0000", "").take(SUBJECT_LIMIT)
    }

    /** Subject plus any further lines of the caller's message, as one paragraph-safe text. */
    private fun commitText(message: String, subject: String): String {
        val rest = message.replace("\u0000", "").lines().dropWhile { it.isBlank() }.drop(1).joinToString("\n").trim()
        return if (rest.isEmpty()) subject else "$subject\n\n$rest"
    }

    private companion object {
        const val CHECKPOINT_TRAILER = "DroidCommand-Checkpoint: v1"
        const val UNDO_TRAILER_PREFIX = "DroidCommand-Undo: "
        const val SUBJECT_LIMIT = 72
        val COMMIT_ID = Regex("[0-9a-f]{4,64}")
        val IN_PROGRESS_MARKERS = listOf(
            "MERGE_HEAD" to "merge",
            "CHERRY_PICK_HEAD" to "cherry-pick",
            "REVERT_HEAD" to "revert",
            "rebase-merge" to "rebase",
            "rebase-apply" to "rebase or am",
        )
    }
}
