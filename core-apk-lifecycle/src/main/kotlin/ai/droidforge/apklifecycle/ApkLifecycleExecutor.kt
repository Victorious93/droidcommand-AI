package ai.droidforge.apklifecycle

/**
 * The extension point for actually installing, launching, logging, and
 * testing an app on a device — mirrors `core-build.BuildExecutor` and
 * `core-tools-android.DeviceController`'s role in those modules. Nothing
 * in [ApkLifecyclePipeline] talks to `adb` or any Android API directly;
 * every device-touching step goes through an implementation of this
 * interface. [NullApkLifecycleExecutor] is the only one that exists in
 * this repository — a real `adb`-backed or Android-SDK-backed
 * implementation needs a connected/emulated device this environment does
 * not have.
 */
interface ApkLifecycleExecutor {
    fun install(request: InstallRequest): InstallResult
    fun uninstall(packageName: String): UninstallResult
    fun launch(packageName: String): LaunchResult
    fun collectLogs(packageName: String, sinceMillis: Long? = null): LogsResult
    fun runInstrumentedTests(packageName: String, testPackage: String? = null): TestRunResult
}
