package ai.droidcommand.shell

/**
 * A command to run — [executable] and [args] are passed to the process
 * launcher as a plain argument vector, never concatenated into a string
 * and handed to a shell (`sh -c "..."`). That is a deliberate, structural
 * choice: it makes shell-metacharacter injection impossible by
 * construction, not merely discouraged by convention.
 */
data class ShellCommand(
    val executable: String,
    val args: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = 30_000,
    val maxOutputBytes: Long = 1_000_000,
)

sealed class ShellExecutionResult {
    data class Success(val exitCode: Int, val stdout: String, val stderr: String, val durationMillis: Long) : ShellExecutionResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : ShellExecutionResult()
}
