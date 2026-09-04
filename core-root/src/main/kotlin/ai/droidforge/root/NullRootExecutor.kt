package ai.droidforge.root

/**
 * The only [RootExecutor] implementation in this repository. Same
 * honesty rule as `core-tools-android.NullDeviceController` and
 * `core-apk-lifecycle.NullApkLifecycleExecutor`: [isRootAvailable] is
 * truthfully `false` (there genuinely is no root here), and [execute]
 * fails explicitly rather than fabricating a successful elevated command.
 */
class NullRootExecutor : RootExecutor {
    override fun isRootAvailable(): Boolean = false

    override fun execute(command: RootCommand, isCancelled: () -> Boolean) =
        RootExecutionResult.Failure("Cannot run '${command.executable}' as root: no real rooted device is connected (NullRootExecutor)")
}
