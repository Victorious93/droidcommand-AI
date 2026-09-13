package ai.droidcommand.termux

/**
 * A command to run inside a Termux userland. Shaped exactly like
 * `core-root.RootCommand`/`core-shell.ShellCommand` (executable/args as a
 * plain argument vector, never a shell string) for the same
 * injection-safety reason those two already document — [TermuxExecutor]
 * implementations are responsible for their own safe quoting when a given
 * transport (e.g. a remote shell) requires flattening this into a string.
 */
data class TermuxCommand(
    val executable: String,
    val args: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = 30_000,
)

sealed class TermuxExecutionResult {
    data class Success(val exitCode: Int, val stdout: String, val stderr: String, val durationMillis: Long) : TermuxExecutionResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : TermuxExecutionResult()
}
