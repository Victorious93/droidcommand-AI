package ai.droidcommand.git

import ai.droidcommand.shell.ProcessBuilderShellExecutor
import ai.droidcommand.shell.ShellSecurityPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A real git repository in a temp directory, driven by plain `ProcessBuilder` on purpose: the tests
 * arrange and inspect state through a path that shares no code with [GitCheckpointer]. The environment
 * is hermetic (no global/system git config, no repository discovery above the temp dir) so a developer's
 * own settings — signing, hooks, identity — cannot change a result.
 */
internal class TestRepo private constructor(val root: Path, private val isolation: Map<String, String>) {
    companion object {
        fun gitAvailable(): Boolean = runCatching {
            ProcessBuilder("git", "--version").redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() == 0 }
        }.getOrDefault(false)

        /** An initialized repository with one commit `base` containing a.txt, b.txt, c.txt, e.txt. */
        fun seeded(): TestRepo {
            val repo = empty()
            repo.git("init", "-q", "-b", "main")
            for (name in listOf("a", "b", "c", "e")) repo.write("$name.txt", "${name}1\n")
            repo.git("add", "-A")
            repo.git("commit", "-q", "-m", "base")
            return repo
        }

        /** A plain directory: no `git init`. */
        fun empty(): TestRepo {
            val root = createTempDirectory("git-test").toRealPath()
            val emptyConfig = Files.createFile(root.resolveSibling(root.fileName.toString() + ".gitconfig"))
            return TestRepo(
                root,
                mapOf(
                    "GIT_CONFIG_GLOBAL" to emptyConfig.toString(),
                    "GIT_CONFIG_NOSYSTEM" to "1",
                    "GIT_CEILING_DIRECTORIES" to root.parent.toString(),
                ),
            )
        }
    }

    private val identity = mapOf(
        "GIT_AUTHOR_NAME" to "Test User",
        "GIT_AUTHOR_EMAIL" to "test@example.com",
        "GIT_COMMITTER_NAME" to "Test User",
        "GIT_COMMITTER_EMAIL" to "test@example.com",
    )

    /** Runs git, returning combined output and exit code, without judging success. */
    fun gitRaw(vararg args: String): Pair<Int, String> {
        val builder = ProcessBuilder(listOf("git") + args)
        builder.directory(root.toFile())
        builder.environment().putAll(isolation + identity)
        builder.redirectErrorStream(true)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    fun git(vararg args: String): String {
        val (code, output) = gitRaw(*args)
        check(code == 0) { "git ${args.joinToString(" ")} failed ($code): $output" }
        return output
    }

    fun write(rel: String, content: String) {
        val file = root.resolve(rel)
        file.parent?.let { Files.createDirectories(it) }
        file.writeText(content)
    }

    fun read(rel: String): String = root.resolve(rel).readText()

    fun exists(rel: String): Boolean = Files.exists(root.resolve(rel))

    fun delete(rel: String) {
        Files.delete(root.resolve(rel))
    }

    fun commitAll(message: String) {
        git("add", "-A")
        git("commit", "-q", "-m", message)
    }

    fun head(): String = git("rev-parse", "HEAD").trim()

    fun commitCount(): Int = git("rev-list", "--count", "HEAD").trim().toInt()

    /** Sorted `git status --porcelain` lines, e.g. `M  b.txt`, ` M c.txt`, `?? d.txt`. */
    fun status(): List<String> = git("status", "--porcelain", "-uall").lines().filter { it.isNotBlank() }.sorted()

    /** Files touched by the commit `rev`, sorted. */
    fun filesIn(rev: String): List<String> = git("show", "--name-only", "--format=", rev).lines().filter { it.isNotBlank() }.sorted()

    fun message(rev: String = "HEAD"): String = git("log", "-1", "--format=%B", rev)

    fun checkpointer(
        executables: Set<String> = setOf("git"),
        workingDirs: List<String> = listOf(root.toString()),
    ): GitCheckpointer = GitCheckpointer(
        ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = executables, allowedWorkingDirectories = workingDirs)),
        root,
        extraEnvironment = isolation,
    )

    /** begin → edit via [change] → commit, asserting the happy path. */
    fun agentEdit(checkpointer: GitCheckpointer, paths: List<String>, message: String = "agent edit", change: () -> Unit): CheckpointResult {
        val begin = checkpointer.begin(paths)
        check(begin is BeginResult.Ready) { "expected Ready, got $begin" }
        change()
        return begin.commit(message)
    }
}
