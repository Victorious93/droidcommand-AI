package ai.droidcommand.tools.android

import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NavigationToolsTest {
    @Test
    fun `NavigateToTool delegates the destination and defaults mode to DRIVING`() {
        val device = ScriptedDeviceController(launchNavigationResult = DeviceActionResult.Success("launched"))
        val result = NavigateToTool(device).execute(mapOf("destination" to "Central Park"))
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf("Central Park" to NavigationMode.DRIVING), device.launchNavigationCalls)
    }

    @Test
    fun `NavigateToTool parses an explicit mode case-insensitively`() {
        val device = ScriptedDeviceController(launchNavigationResult = DeviceActionResult.Success("launched"))
        NavigateToTool(device).execute(mapOf("destination" to "Central Park", "mode" to "walking"))
        assertEquals(listOf("Central Park" to NavigationMode.WALKING), device.launchNavigationCalls)
    }

    @Test
    fun `NavigateToTool fails without calling the device when destination is missing`() {
        val device = ScriptedDeviceController()
        val result = NavigateToTool(device).execute(emptyMap())
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.launchNavigationCalls.size)
    }

    @Test
    fun `NavigateToTool fails without calling the device for an unknown mode`() {
        val device = ScriptedDeviceController()
        val result = NavigateToTool(device).execute(mapOf("destination" to "Central Park", "mode" to "teleport"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.launchNavigationCalls.size)
    }

    @Test
    fun `NavigateToTool surfaces a device failure`() {
        val device = ScriptedDeviceController(launchNavigationResult = DeviceActionResult.Failure("no device"))
        assertIs<ToolResult.Failure>(NavigateToTool(device).execute(mapOf("destination" to "Central Park")))
    }
}
