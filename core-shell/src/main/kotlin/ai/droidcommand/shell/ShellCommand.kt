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

/**
 * The binary-safe counterpart to [ShellExecutionResult]: [Success.stdout] is the raw bytes a process
 * wrote, exactly as written — no charset decode, no line-boundary splitting/rejoining. [ShellExecutionResult]
 * cannot represent this (its `stdout` is already a decoded `String`, built line-by-line), which is why this
 * is a separate result type rather than a new field on the existing one. `stderr` stays `String` here:
 * diagnostic output is legitimately text in every real use of this, and giving it the same binary treatment
 * would add no value.
 *
 * Not a `data class` for [Success]: a generated `equals`/`hashCode` over a `ByteArray` property compares
 * array *reference* identity, not content, which would be actively misleading for a byte-array-carrying
 * result — callers that need to compare [stdout] should do so explicitly (e.g. `contentEquals`).
 */
sealed class ShellBinaryExecutionResult {
    class Success(val exitCode: Int, val stdout: ByteArray, val stderr: String, val durationMillis: Long) : ShellBinaryExecutionResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : ShellBinaryExecutionResult()
}
