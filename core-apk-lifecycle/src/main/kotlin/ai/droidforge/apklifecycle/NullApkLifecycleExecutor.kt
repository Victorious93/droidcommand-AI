package ai.droidforge.apklifecycle

/**
 * The only [ApkLifecycleExecutor] implementation in this repository.
 * Same honesty rule as `core-tools-android.NullDeviceController`: there is
 * no coherent "successful install with no device" result, so every method
 * fails explicitly with a message naming the reason, rather than
 * fabricating a successful install, launch, log read, or test run.
 */
class NullApkLifecycleExecutor : ApkLifecycleExecutor {
    override fun install(request: InstallRequest) =
        InstallResult.Failure("Cannot install '${request.packageName}': no real device/adb is connected (NullApkLifecycleExecutor)")

    override fun uninstall(packageName: String) =
        UninstallResult.Failure("Cannot uninstall '$packageName': no real device/adb is connected (NullApkLifecycleExecutor)")

    override fun launch(packageName: String) =
        LaunchResult.Failure("Cannot launch '$packageName': no real device/adb is connected (NullApkLifecycleExecutor)")

    override fun collectLogs(packageName: String, sinceMillis: Long?) =
        LogsResult.Failure("Cannot collect logs for '$packageName': no real device/adb is connected (NullApkLifecycleExecutor)")

    override fun runInstrumentedTests(packageName: String, testPackage: String?) =
        TestRunResult.Failure("Cannot run instrumented tests for '$packageName': no real device/adb is connected (NullApkLifecycleExecutor)")
}
