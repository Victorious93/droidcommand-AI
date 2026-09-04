package ai.droidforge.tools.android

/**
 * The only point at which device control actually "happens" — mirrors
 * `core-build.BuildExecutor`'s role for compilation. Nothing in this
 * module talks to Android's Accessibility APIs, `PackageManager`, or
 * `adb` directly; every device-touching `Tool` in this package delegates
 * to an implementation of this interface. That is what keeps this module
 * compilable and testable in a headless JVM with no Android SDK — the
 * same reasoning `core-build` documents for why it never spawns a
 * compiler itself.
 *
 * No implementation exists in this repository except [NullDeviceController].
 * A real one (`AccessibilityServiceDeviceController`, an `adb`-shell-backed
 * controller, or similar) needs the Android SDK and a connected/emulated
 * device, neither of which exists in this environment.
 */
interface DeviceController {
    fun getUiTree(): UiTreeResult
    fun tap(x: Int, y: Int): DeviceActionResult
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Long): DeviceActionResult
    fun typeText(text: String): DeviceActionResult
    fun pressKey(key: DeviceKey): DeviceActionResult
    fun launchApp(packageName: String): DeviceActionResult
    fun listInstalledApps(): InstalledAppsResult
    fun takeScreenshot(): ScreenshotResult
    fun getDeviceInfo(): DeviceInfoResult
}
