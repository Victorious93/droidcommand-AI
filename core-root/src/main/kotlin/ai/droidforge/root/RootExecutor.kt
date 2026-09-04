package ai.droidforge.root

/**
 * The extension point for actually running a command as root — mirrors
 * `core-build.BuildExecutor`, `core-tools-android.DeviceController`, and
 * `core-shell.ShellExecutor`'s role in those modules. [isRootAvailable] is
 * meant to be wired directly into `core-security.SecurityPolicy.rootAvailable`,
 * so root-availability detection lives in exactly one place rather than
 * being duplicated between this module and the security layer that gates
 * it. [NullRootExecutor] is the only implementation in this repository —
 * a real one needs an actual rooted device, which this environment does
 * not have.
 */
interface RootExecutor {
    fun isRootAvailable(): Boolean
    fun execute(command: RootCommand, isCancelled: () -> Boolean = { false }): RootExecutionResult
}
