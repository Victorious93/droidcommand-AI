package ai.droidcommand.tools.android

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NullDeviceControllerTest {
    private val device = NullDeviceController()

    @Test
    fun `getUiTree fails, explicitly, rather than returning a fabricated empty tree`() {
        val result = assertIs<UiTreeResult.Failure>(device.getUiTree())
        assertTrue(result.reason.contains("no real device"))
    }

    @Test
    fun `tap fails, explicitly, rather than fabricating success`() {
        val result = assertIs<DeviceActionResult.Failure>(device.tap(10, 20))
        assertTrue(result.reason.contains("no real device"))
    }

    @Test
    fun `swipe, typeText, pressKey, and launchApp all fail explicitly`() {
        assertIs<DeviceActionResult.Failure>(device.swipe(0, 0, 10, 10, 100))
        assertIs<DeviceActionResult.Failure>(device.typeText("hello"))
        assertIs<DeviceActionResult.Failure>(device.pressKey(DeviceKey.BACK))
        assertIs<DeviceActionResult.Failure>(device.launchApp("com.example.app"))
    }

    @Test
    fun `listInstalledApps fails rather than returning a fabricated empty list`() {
        val result = assertIs<InstalledAppsResult.Failure>(device.listInstalledApps())
        assertTrue(result.reason.contains("no real device"))
    }

    @Test
    fun `takeScreenshot and getDeviceInfo fail explicitly`() {
        assertIs<ScreenshotResult.Failure>(device.takeScreenshot())
        assertIs<DeviceInfoResult.Failure>(device.getDeviceInfo())
    }

    @Test
    fun `readFile fails, explicitly, rather than fabricating content`() {
        val result = assertIs<FileReadResult.Failure>(device.readFile("/sdcard/note.txt"))
        assertTrue(result.reason.contains("no real device"))
    }

    @Test
    fun `writeFile, moveFile, copyFile, and deleteFile all fail explicitly`() {
        assertIs<DeviceActionResult.Failure>(device.writeFile("/a", "content", append = false))
        assertIs<DeviceActionResult.Failure>(device.moveFile("/a", "/b"))
        assertIs<DeviceActionResult.Failure>(device.copyFile("/a", "/b"))
        assertIs<DeviceActionResult.Failure>(device.deleteFile("/a"))
    }

    @Test
    fun `listDirectory fails rather than returning a fabricated empty listing`() {
        val result = assertIs<FileListResult.Failure>(device.listDirectory("/sdcard"))
        assertTrue(result.reason.contains("no real device"))
    }
}
