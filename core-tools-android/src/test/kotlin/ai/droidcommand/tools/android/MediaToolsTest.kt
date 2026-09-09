package ai.droidcommand.tools.android

import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MediaToolsTest {
    @Test
    fun `MediaPlayPauseTool delegates to the device`() {
        val device = ScriptedDeviceController(mediaPlayPauseResult = DeviceActionResult.Success("toggled"))
        val result = MediaPlayPauseTool(device).execute(emptyMap())
        assertIs<ToolResult.Success>(result)
        assertEquals(1, device.mediaPlayPauseCalls)
    }

    @Test
    fun `MediaPlayPauseTool surfaces a device failure`() {
        val device = ScriptedDeviceController(mediaPlayPauseResult = DeviceActionResult.Failure("no device"))
        assertIs<ToolResult.Failure>(MediaPlayPauseTool(device).execute(emptyMap()))
    }

    @Test
    fun `MediaNextTool delegates to the device`() {
        val device = ScriptedDeviceController(mediaNextResult = DeviceActionResult.Success("skipped"))
        val result = MediaNextTool(device).execute(emptyMap())
        assertIs<ToolResult.Success>(result)
        assertEquals(1, device.mediaNextCalls)
    }

    @Test
    fun `MediaPreviousTool delegates to the device`() {
        val device = ScriptedDeviceController(mediaPreviousResult = DeviceActionResult.Success("skipped back"))
        val result = MediaPreviousTool(device).execute(emptyMap())
        assertIs<ToolResult.Success>(result)
        assertEquals(1, device.mediaPreviousCalls)
    }

    @Test
    fun `SetVolumeTool delegates a valid level`() {
        val device = ScriptedDeviceController(setVolumeResult = DeviceActionResult.Success("set"))
        val result = SetVolumeTool(device).execute(mapOf("levelPercent" to "50"))
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf(50), device.setVolumeCalls)
    }

    @Test
    fun `SetVolumeTool fails without calling the device when levelPercent is missing`() {
        val device = ScriptedDeviceController()
        val result = SetVolumeTool(device).execute(emptyMap())
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.setVolumeCalls.size)
    }

    @Test
    fun `SetVolumeTool fails without calling the device when levelPercent is not a number`() {
        val device = ScriptedDeviceController()
        val result = SetVolumeTool(device).execute(mapOf("levelPercent" to "loud"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.setVolumeCalls.size)
    }

    @Test
    fun `SetVolumeTool fails without calling the device when levelPercent is out of range`() {
        val device = ScriptedDeviceController()
        assertIs<ToolResult.Failure>(SetVolumeTool(device).execute(mapOf("levelPercent" to "-1")))
        assertIs<ToolResult.Failure>(SetVolumeTool(device).execute(mapOf("levelPercent" to "101")))
        assertEquals(0, device.setVolumeCalls.size)
    }
}
