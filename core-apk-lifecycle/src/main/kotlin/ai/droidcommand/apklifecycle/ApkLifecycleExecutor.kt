package ai.droidcommand.apklifecycle

/**
 * The extension point for actually installing, launching, logging, and
 * testing an app on a device — mirrors `core-build.BuildExecutor` and
 * `core-tools-android.DeviceController`'s role in those modules. Nothing
 * in [ApkLifecyclePipeline] talks to `adb` or any Android API directly;
 * every device-touching step goes through an implementation of this
 * interface. [NullApkLifecycleExecutor] is the fail-closed default; [AdbApkLifecycleExecutor]
 * (ROADMAP-087) is a real, `adb`-shell-backed implementation, in the same PC-drives-a-tethered-phone
 * topology as `core-tools-android.AdbDeviceController`/`core-root.AdbRootExecutor` — verified against a
 * real scripted `adb` subprocess in tests, but not yet runtime-verified against a real device, since none
 * is connected in this environment.
 */
interface ApkLifecycleExecutor {
    fun install(request: InstallRequest): InstallResult
    fun uninstall(packageName: String): UninstallResult
    fun launch(packageName: String): LaunchResult
    fun collectLogs(packageName: String, sinceMillis: Long? = null): LogsResult
    fun runInstrumentedTests(packageName: String, testPackage: String? = null): TestRunResult
}
