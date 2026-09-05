package ai.droidcommand.tools.android

/** A fully scripted [DeviceController] test double — every call is recorded and answered by a canned response, no real device involved. */
internal class ScriptedDeviceController(
    private val uiTree: UiTreeResult = UiTreeResult.Failure("not scripted"),
    private val tapResult: DeviceActionResult = DeviceActionResult.Failure("not scripted"),
    private val swipeResult: DeviceActionResult = DeviceActionResult.Failure("not scripted"),
    private val typeTextResult: DeviceActionResult = DeviceActionResult.Failure("not scripted"),
    private val pressKeyResult: DeviceActionResult = DeviceActionResult.Failure("not scripted"),
    private val launchAppResult: DeviceActionResult = DeviceActionResult.Failure("not scripted"),
    private val installedAppsResult: InstalledAppsResult = InstalledAppsResult.Failure("not scripted"),
    private val screenshotResult: ScreenshotResult = ScreenshotResult.Failure("not scripted"),
    private val deviceInfoResult: DeviceInfoResult = DeviceInfoResult.Failure("not scripted"),
) : DeviceController {
    var tapCalls = mutableListOf<Pair<Int, Int>>()
        private set
    var swipeCalls = 0
        private set
    var typeTextCalls = mutableListOf<String>()
        private set
    var pressKeyCalls = mutableListOf<DeviceKey>()
        private set
    var launchAppCalls = mutableListOf<String>()
        private set
    var getUiTreeCalls = 0
        private set

    override fun getUiTree(): UiTreeResult {
        getUiTreeCalls++
        return uiTree
    }

    override fun tap(x: Int, y: Int): DeviceActionResult {
        tapCalls += x to y
        return tapResult
    }

    override fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Long): DeviceActionResult {
        swipeCalls++
        return swipeResult
    }

    override fun typeText(text: String): DeviceActionResult {
        typeTextCalls += text
        return typeTextResult
    }

    override fun pressKey(key: DeviceKey): DeviceActionResult {
        pressKeyCalls += key
        return pressKeyResult
    }

    override fun launchApp(packageName: String): DeviceActionResult {
        launchAppCalls += packageName
        return launchAppResult
    }

    override fun listInstalledApps(): InstalledAppsResult = installedAppsResult
    override fun takeScreenshot(): ScreenshotResult = screenshotResult
    override fun getDeviceInfo(): DeviceInfoResult = deviceInfoResult
}
