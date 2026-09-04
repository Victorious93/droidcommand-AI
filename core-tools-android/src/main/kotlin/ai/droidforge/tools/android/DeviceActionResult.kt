package ai.droidforge.tools.android

sealed class DeviceActionResult {
    data class Success(val message: String) : DeviceActionResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : DeviceActionResult()
}

data class InstalledApp(val packageName: String, val appName: String, val versionName: String? = null)

data class DeviceInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val osVersion: String? = null,
    val screenWidthPx: Int? = null,
    val screenHeightPx: Int? = null,
)

sealed class ScreenshotResult {
    data class Success(val path: String, val widthPx: Int, val heightPx: Int, val sizeBytes: Long) : ScreenshotResult()
    data class Failure(val reason: String) : ScreenshotResult()
}

// Read-only queries get their own result types rather than returning a bare
// value, for the same reason the action methods return DeviceActionResult:
// an empty list or an all-null DeviceInfo would be indistinguishable from
// "the device genuinely has no apps" / "the device reported nothing", when
// what actually happened is "there is no device to ask."
sealed class UiTreeResult {
    data class Success(val tree: UiTree) : UiTreeResult()
    data class Failure(val reason: String) : UiTreeResult()
}

sealed class InstalledAppsResult {
    data class Success(val apps: List<InstalledApp>) : InstalledAppsResult()
    data class Failure(val reason: String) : InstalledAppsResult()
}

sealed class DeviceInfoResult {
    data class Success(val info: DeviceInfo) : DeviceInfoResult()
    data class Failure(val reason: String) : DeviceInfoResult()
}
