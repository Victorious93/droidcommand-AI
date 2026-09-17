package ai.droidcommand.shell

/**
 * The extension point for actually running a command — mirrors
 * `core-build.BuildExecutor` and `core-tools-android.DeviceController`'s
 * role in those modules. Unlike those two, a real implementation
 * ([ProcessBuilderShellExecutor]) genuinely belongs in this repository:
 * spawning a subprocess is a plain JVM capability, not something that
 * needs the Android SDK or a connected device. What still needs care is
 * *what* it's allowed to run — see [ShellSecurityPolicy].
 */
interface ShellExecutor {
    fun execute(command: ShellCommand, isCancelled: () -> Boolean = { false }): ShellExecutionResult

    /**
     * Binary-safe variant of [execute]: captures raw stdout bytes exactly as the process wrote them,
     * with no charset-decode or line-boundary pass — required for a command whose stdout isn't text
     * (e.g. `adb exec-out screencap -p`, which writes a raw PNG; [execute]'s own
     * `bufferedReader().forEachLine`-based capture would corrupt that). Defaults to an honest
     * [ShellBinaryExecutionResult.Failure] so an [ShellExecutor] that hasn't implemented raw capture
     * fails plainly rather than silently falling back to [execute]'s text-oriented one and corrupting
     * binary output — the same "fail closed, name it" convention this module's `Null*`/[ShellExecutionResult.Failure]
     * cases already follow elsewhere.
     */
    fun executeBinary(command: ShellCommand, isCancelled: () -> Boolean = { false }): ShellBinaryExecutionResult =
        ShellBinaryExecutionResult.Failure("Binary-safe execution is not supported by this ShellExecutor")
}
