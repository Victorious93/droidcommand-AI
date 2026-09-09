package ai.droidcommand.tools.android

import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SystemToolsTest {
    @Test
    fun `GetBatteryStatusTool reports level and charging state`() {
        val device = ScriptedDeviceController(batteryStatusResult = BatteryStatusResult.Success(BatteryStatus(80, isCharging = true)))
        val result = assertIs<ToolResult.Success>(GetBatteryStatusTool(device).execute(emptyMap()))
        assertEquals(true, result.output.contains("80%"))
        assertEquals(true, result.output.contains("charging"))
    }

    @Test
    fun `GetBatteryStatusTool surfaces a device failure`() {
        val device = ScriptedDeviceController(batteryStatusResult = BatteryStatusResult.Failure("no device"))
        assertIs<ToolResult.Failure>(GetBatteryStatusTool(device).execute(emptyMap()))
    }

    @Test
    fun `GetNetworkStateTool reports type and connectivity`() {
        val device = ScriptedDeviceController(networkStateResult = NetworkStateResult.Success(NetworkState(NetworkType.WIFI, isConnected = true)))
        val result = assertIs<ToolResult.Success>(GetNetworkStateTool(device).execute(emptyMap()))
        assertEquals(true, result.output.contains("WIFI"))
        assertEquals(true, result.output.contains("connected"))
    }

    @Test
    fun `GetNetworkStateTool surfaces a device failure`() {
        val device = ScriptedDeviceController(networkStateResult = NetworkStateResult.Failure("no device"))
        assertIs<ToolResult.Failure>(GetNetworkStateTool(device).execute(emptyMap()))
    }

    @Test
    fun `GetStorageInfoTool reports free and total bytes`() {
        val device = ScriptedDeviceController(storageInfoResult = StorageInfoResult.Success(StorageInfo(totalBytes = 1000, freeBytes = 400)))
        val result = assertIs<ToolResult.Success>(GetStorageInfoTool(device).execute(emptyMap()))
        assertEquals(true, result.output.contains("400"))
        assertEquals(true, result.output.contains("1000"))
    }

    @Test
    fun `GetStorageInfoTool surfaces a device failure`() {
        val device = ScriptedDeviceController(storageInfoResult = StorageInfoResult.Failure("no device"))
        assertIs<ToolResult.Failure>(GetStorageInfoTool(device).execute(emptyMap()))
    }

    @Test
    fun `GetClipboardTool returns the clipboard text`() {
        val device = ScriptedDeviceController(clipboardReadResult = ClipboardReadResult.Success("hello"))
        val result = assertIs<ToolResult.Success>(GetClipboardTool(device).execute(emptyMap()))
        assertEquals("hello", result.output)
    }

    @Test
    fun `GetClipboardTool surfaces a device failure`() {
        val device = ScriptedDeviceController(clipboardReadResult = ClipboardReadResult.Failure("no device"))
        assertIs<ToolResult.Failure>(GetClipboardTool(device).execute(emptyMap()))
    }

    @Test
    fun `SetClipboardTool delegates the given text`() {
        val device = ScriptedDeviceController(setClipboardResult = DeviceActionResult.Success("set"))
        val result = SetClipboardTool(device).execute(mapOf("text" to "copied"))
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf("copied"), device.setClipboardCalls)
    }

    @Test
    fun `SetClipboardTool fails without calling the device when text is missing`() {
        val device = ScriptedDeviceController()
        val result = SetClipboardTool(device).execute(emptyMap())
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.setClipboardCalls.size)
    }
}
