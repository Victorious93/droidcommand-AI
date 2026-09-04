package ai.droidforge.tools.android

import ai.droidforge.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeviceToolsTest {
    @Test
    fun `TapTool delegates parsed coordinates to the device`() {
        val device = ScriptedDeviceController(tapResult = DeviceActionResult.Success("tapped"))
        val result = TapTool(device).execute(mapOf("x" to "10", "y" to "20"))
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf(10 to 20), device.tapCalls)
    }

    @Test
    fun `TapTool fails without calling the device when x is missing`() {
        val device = ScriptedDeviceController()
        val result = TapTool(device).execute(mapOf("y" to "20"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.tapCalls.size)
    }

    @Test
    fun `TapTool fails without calling the device when a coordinate is not an integer`() {
        val device = ScriptedDeviceController()
        val result = TapTool(device).execute(mapOf("x" to "not-a-number", "y" to "20"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.tapCalls.size)
    }

    @Test
    fun `SwipeTool parses all four coordinates and an optional duration`() {
        val device = ScriptedDeviceController(swipeResult = DeviceActionResult.Success("swiped"))
        val result = SwipeTool(device).execute(mapOf("startX" to "0", "startY" to "0", "endX" to "100", "endY" to "100", "durationMillis" to "500"))
        assertIs<ToolResult.Success>(result)
        assertEquals(1, device.swipeCalls)
    }

    @Test
    fun `SwipeTool fails without calling the device when a coordinate is missing`() {
        val device = ScriptedDeviceController()
        val result = SwipeTool(device).execute(mapOf("startX" to "0", "startY" to "0", "endX" to "100"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.swipeCalls)
    }

    @Test
    fun `TypeTextTool delegates the given text`() {
        val device = ScriptedDeviceController(typeTextResult = DeviceActionResult.Success("typed"))
        val result = TypeTextTool(device).execute(mapOf("text" to "hello world"))
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf("hello world"), device.typeTextCalls)
    }

    @Test
    fun `TypeTextTool fails without calling the device when text is missing`() {
        val device = ScriptedDeviceController()
        val result = TypeTextTool(device).execute(emptyMap())
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.typeTextCalls.size)
    }

    @Test
    fun `PressKeyTool parses a valid key name case-insensitively`() {
        val device = ScriptedDeviceController(pressKeyResult = DeviceActionResult.Success("pressed"))
        val result = PressKeyTool(device).execute(mapOf("key" to "back"))
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf(DeviceKey.BACK), device.pressKeyCalls)
    }

    @Test
    fun `PressKeyTool fails without calling the device for an unknown key`() {
        val device = ScriptedDeviceController()
        val result = PressKeyTool(device).execute(mapOf("key" to "not-a-real-key"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.pressKeyCalls.size)
    }

    @Test
    fun `LaunchAppTool delegates the given package name`() {
        val device = ScriptedDeviceController(launchAppResult = DeviceActionResult.Success("launched"))
        val result = LaunchAppTool(device).execute(mapOf("packageName" to "com.example.app"))
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf("com.example.app"), device.launchAppCalls)
    }

    @Test
    fun `LaunchAppTool fails without calling the device when packageName is missing`() {
        val device = ScriptedDeviceController()
        val result = LaunchAppTool(device).execute(emptyMap())
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.launchAppCalls.size)
    }

    @Test
    fun `ListInstalledAppsTool formats each app on its own line`() {
        val device = ScriptedDeviceController(
            installedAppsResult = InstalledAppsResult.Success(
                listOf(InstalledApp("com.example.a", "App A", "1.0"), InstalledApp("com.example.b", "App B")),
            ),
        )
        val result = assertIs<ToolResult.Success>(ListInstalledAppsTool(device).execute(emptyMap()))
        assertEquals(2, result.output.lines().size)
    }

    @Test
    fun `ListInstalledAppsTool surfaces a device failure`() {
        val device = ScriptedDeviceController(installedAppsResult = InstalledAppsResult.Failure("no device"))
        assertIs<ToolResult.Failure>(ListInstalledAppsTool(device).execute(emptyMap()))
    }

    @Test
    fun `GetUiTreeTool renders a successfully retrieved tree`() {
        val tree = UiTree(UiNode(text = "Hello", bounds = Rect(0, 0, 1, 1)), java.time.Instant.now())
        val device = ScriptedDeviceController(uiTree = UiTreeResult.Success(tree))
        val result = assertIs<ToolResult.Success>(GetUiTreeTool(device).execute(emptyMap()))
        assertEquals(true, result.output.contains("Hello"))
    }

    @Test
    fun `GetUiTreeTool surfaces a device failure`() {
        val device = ScriptedDeviceController(uiTree = UiTreeResult.Failure("no device"))
        assertIs<ToolResult.Failure>(GetUiTreeTool(device).execute(emptyMap()))
    }

    @Test
    fun `TakeScreenshotTool reports path and dimensions on success`() {
        val device = ScriptedDeviceController(screenshotResult = ScreenshotResult.Success("/tmp/shot.png", 1080, 1920, 4096))
        val result = assertIs<ToolResult.Success>(TakeScreenshotTool(device).execute(emptyMap()))
        assertEquals(true, result.output.contains("/tmp/shot.png"))
    }

    @Test
    fun `TakeScreenshotTool surfaces a device failure`() {
        val device = ScriptedDeviceController(screenshotResult = ScreenshotResult.Failure("no device"))
        assertIs<ToolResult.Failure>(TakeScreenshotTool(device).execute(emptyMap()))
    }
}
