package ai.droidcommand.termux

/**
 * The always-present [TermuxExecutor]. Same honesty rule as
 * `core-root.NullRootExecutor`/`core-tools-android.NullDeviceController`:
 * [isAvailable] is truthfully `false` (there genuinely is no Termux backend
 * configured), and [execute] fails explicitly rather than fabricating a
 * successful command.
 */
class NullTermuxExecutor : TermuxExecutor {
    override fun isAvailable(): Boolean = false

    override fun execute(command: TermuxCommand, isCancelled: () -> Boolean) =
        TermuxExecutionResult.Failure("Cannot run '${command.executable}' in Termux: no real Termux backend is configured (NullTermuxExecutor)")
}
