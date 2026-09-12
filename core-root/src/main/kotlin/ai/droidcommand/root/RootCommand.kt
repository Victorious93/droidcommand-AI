package ai.droidcommand.root

/**
 * A command to run with elevated (root) privileges. Shaped like
 * `core-shell.ShellCommand` (executable/args as a plain argument vector,
 * never a shell string) deliberately — the same injection-safety argument
 * applies here with strictly higher stakes, since a mistake runs as root
 * instead of as the app's own user.
 *
 * [workingDirectory]/[environment] mirror `ShellCommand`'s own fields
 * (added later here than there — see `docs/AUDIT_2026-09-05.md`'s "widen
 * RootCommand" addendum): a caller-supplied working directory or
 * environment can now be honored end to end (`MagiskProvider`,
 * `RootExecutionTarget`) instead of being rejected outright. **Honest
 * caveat, not silently assumed:** these are applied to the outer process
 * that invokes `su`, exactly like `ProcessBuilderShellExecutor` applies
 * them to the process it starts — whether a given `su` implementation
 * actually preserves the calling process's working directory/environment
 * once it elevates to root is a property of that `su` binary, not of this
 * code, and is not guaranteed by any implementation in this repository.
 */
data class RootCommand(
    val executable: String,
    val args: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = 30_000,
)

sealed class RootExecutionResult {
    data class Success(val exitCode: Int, val stdout: String, val stderr: String, val durationMillis: Long) : RootExecutionResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : RootExecutionResult()
}
