package ai.droidcommand.promptregen

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * Reads real, on-disk repo state only — no `git` subprocess. This repo's own `core-shell` module
 * exists for real subprocess execution, but pulling it in here for one read-only `git branch --show-
 * current` call would add a process-execution dependency this module doesn't otherwise need, purely
 * to save parsing one plumbing file `git` itself writes in plain text. `.git/HEAD` is `git`'s own
 * documented on-disk format (`ref: refs/heads/<branch>` for an attached HEAD, a bare commit hash for
 * a detached one) — parsing it directly is a real, stable technique, not a guess.
 */
object RepoContextScanner {
    fun scan(repoRoot: Path): RepoContext {
        val docsDir = repoRoot.resolve("docs")
        val docFiles = if (docsDir.exists()) {
            Files.list(docsDir).use { stream ->
                stream.asSequence()
                    .filter { it.isRegularFile() && it.name.endsWith(".md") }
                    .map { it.name }
                    .sorted()
                    .toList()
            }
        } else {
            emptyList()
        }

        return RepoContext(
            hasClaudeMd = repoRoot.resolve("CLAUDE.md").exists(),
            hasAgentsMd = repoRoot.resolve("AGENTS.md").exists(),
            hasReadme = repoRoot.resolve("README.md").exists(),
            docFiles = docFiles,
            gitBranch = readGitBranch(repoRoot),
        )
    }

    /** `null` for a missing `.git` directory, a detached HEAD, or any other unrecognized format — never guessed. */
    private fun readGitBranch(repoRoot: Path): String? {
        val headFile = repoRoot.resolve(".git").resolve("HEAD")
        if (!headFile.exists() || !headFile.isRegularFile()) return null
        val content = runCatching { headFile.readText() }.getOrNull()?.trim() ?: return null
        val prefix = "ref: refs/heads/"
        return if (content.startsWith(prefix)) content.removePrefix(prefix).trim() else null
    }
}
