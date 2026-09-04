package ai.droidforge.shell

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
}
