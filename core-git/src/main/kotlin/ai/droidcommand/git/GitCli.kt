package ai.droidcommand.git

import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellExecutor

/** Git could not be run at all, or a step that must succeed did not. Never escapes the public API: [GitCheckpointer] maps it to a result value. */
internal class GitException(message: String) : RuntimeException(message)

/**
 * The only place a process is described. Every call goes through [ShellExecutor] as an argument
 * vector (no shell, so no metacharacter injection), which means `core-shell`'s executable and
 * working-directory allow-list applies to git exactly as it does to any other command.
 */
internal class GitCli(
    private val shell: ShellExecutor,
    private val gitExecutable: String,
    private val workingDirectory: String,
    private val timeoutMillis: Long,
    private val extraEnvironment: Map<String, String>,
) {
    data class Output(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0

        /** stdout without the trailing newline `ShellExecutor` appends to every line. */
        val text: String get() = stdout.trim()
    }

    /**
     * @param readOnly adds `GIT_OPTIONAL_LOCKS=0` so a status-style query never takes the index lock,
     * which could otherwise make a concurrent command of the user's own fail spuriously.
     * @throws GitException if the process could not be started (executable not allowed, working
     * directory not authorized, timeout, ...).
     */
    fun run(args: List<String>, environment: Map<String, String> = emptyMap(), readOnly: Boolean = false): Output {
        val env = BASE_ENVIRONMENT + extraEnvironment + environment + if (readOnly) mapOf("GIT_OPTIONAL_LOCKS" to "0") else emptyMap()
        val command = ShellCommand(
            executable = gitExecutable,
            args = listOf("-c", "core.quotepath=false") + args,
            workingDirectory = workingDirectory,
            environment = env,
            timeoutMillis = timeoutMillis,
        )
        return when (val result = shell.execute(command)) {
            is ShellExecutionResult.Failure -> throw GitException(result.reason)
            is ShellExecutionResult.Success -> Output(result.exitCode, result.stdout, result.stderr)
        }
    }

    private companion object {
        /**
         * - `GIT_LITERAL_PATHSPECS`: a file called `a*b.txt` (or `:(top)x`) must mean that file, never a
         *   glob or magic pathspec that could pull unrelated files into a commit.
         * - `GIT_TERMINAL_PROMPT`: never block waiting for input nobody can give.
         * - `LC_ALL=C`: stable, untranslated messages.
         */
        val BASE_ENVIRONMENT = mapOf(
            "GIT_LITERAL_PATHSPECS" to "1",
            "GIT_TERMINAL_PROMPT" to "0",
            "LC_ALL" to "C",
        )
    }
}
