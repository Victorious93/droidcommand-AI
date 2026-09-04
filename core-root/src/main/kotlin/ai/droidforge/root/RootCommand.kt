package ai.droidforge.root

/**
 * A command to run with elevated (root) privileges. Shaped like
 * `core-shell.ShellCommand` (executable/args as a plain argument vector,
 * never a shell string) deliberately — the same injection-safety argument
 * applies here with strictly higher stakes, since a mistake runs as root
 * instead of as the app's own user.
 */
data class RootCommand(
    val executable: String,
    val args: List<String> = emptyList(),
    val timeoutMillis: Long = 30_000,
)

sealed class RootExecutionResult {
    data class Success(val exitCode: Int, val stdout: String, val stderr: String, val durationMillis: Long) : RootExecutionResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : RootExecutionResult()
}
