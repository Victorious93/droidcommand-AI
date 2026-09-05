package ai.droidcommand.tools.android

/**
 * The only [DeviceController] implementation in this repository. Unlike
 * `core-build.MockBuildExecutor` (which has a coherent "success, zero
 * artifacts" no-op result), there is no honest "successful" default here:
 * reporting a tap or a UI-tree read as successful with no device attached
 * would be exactly the fabricated capability this project's rules forbid.
 * Every method fails, explicitly, with a message that says why —
 * "no real device is connected" — rather than silently returning an empty
 * success that could be mistaken for a real (if boring) device response.
 */
class NullDeviceController : DeviceController {
    private fun failure(action: String) = DeviceActionResult.Failure("Cannot $action: no real device is connected (NullDeviceController)")

    override fun getUiTree() = UiTreeResult.Failure("Cannot read the UI tree: no real device is connected (NullDeviceController)")
    override fun tap(x: Int, y: Int) = failure("tap ($x, $y)")
    override fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Long) = failure("swipe")
    override fun typeText(text: String) = failure("type text")
    override fun pressKey(key: DeviceKey) = failure("press key $key")
    override fun launchApp(packageName: String) = failure("launch app $packageName")
    override fun listInstalledApps() = InstalledAppsResult.Failure("Cannot list installed apps: no real device is connected (NullDeviceController)")
    override fun takeScreenshot() = ScreenshotResult.Failure("Cannot take a screenshot: no real device is connected (NullDeviceController)")
    override fun getDeviceInfo() = DeviceInfoResult.Failure("Cannot read device info: no real device is connected (NullDeviceController)")
}
