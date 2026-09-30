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

    @Test
    fun `ListNotificationsTool reports count and package names`() {
        val device = ScriptedDeviceController(
            notificationsResult = NotificationsResult.Success(
                listOf(
                    NotificationSummary("com.example.app", 42, null),
                    NotificationSummary("com.other.app", 7, "reply"),
                ),
            ),
        )
        val result = assertIs<ToolResult.Success>(ListNotificationsTool(device).execute(emptyMap()))
        assertEquals(true, result.output.contains("2 active"))
        assertEquals(true, result.output.contains("com.example.app"))
        assertEquals(true, result.output.contains("com.other.app"))
        assertEquals(true, result.output.contains("tag=reply"))
    }

    @Test
    fun `ListNotificationsTool reports empty-list case`() {
        val device = ScriptedDeviceController(notificationsResult = NotificationsResult.Success(emptyList()))
        val result = assertIs<ToolResult.Success>(ListNotificationsTool(device).execute(emptyMap()))
        assertEquals(true, result.output.contains("No active"))
    }

    @Test
    fun `ListNotificationsTool surfaces a device failure`() {
        val device = ScriptedDeviceController(notificationsResult = NotificationsResult.Failure("no device"))
        assertIs<ToolResult.Failure>(ListNotificationsTool(device).execute(emptyMap()))
    }

    @Test
    fun `SendBroadcastTool delegates action, packageName, and parsed extras`() {
        val device = ScriptedDeviceController(sendBroadcastResult = DeviceActionResult.Success("sent"))
        val result = assertIs<ToolResult.Success>(
            SendBroadcastTool(device).execute(
                mapOf("action" to "com.example.TEST", "package_name" to "com.example", "extras" to "key1=val1,key2=val2"),
            ),
        )
        assertEquals(1, device.sendBroadcastCalls.size)
        val (action, pkg, extras) = device.sendBroadcastCalls[0]
        assertEquals("com.example.TEST", action)
        assertEquals("com.example", pkg)
        assertEquals(mapOf("key1" to "val1", "key2" to "val2"), extras)
    }

    @Test
    fun `SendBroadcastTool works with no extras or package_name`() {
        val device = ScriptedDeviceController(sendBroadcastResult = DeviceActionResult.Success("sent"))
        SendBroadcastTool(device).execute(mapOf("action" to "com.example.PING"))
        assertEquals(Triple("com.example.PING", null, emptyMap<String, String>()), device.sendBroadcastCalls[0])
    }

    @Test
    fun `SendBroadcastTool fails without calling the device when action is missing`() {
        val device = ScriptedDeviceController()
        val result = assertIs<ToolResult.Failure>(SendBroadcastTool(device).execute(emptyMap()))
        assertEquals(0, device.sendBroadcastCalls.size)
        assertEquals(true, result.reason.contains("action"))
    }

    @Test
    fun `SendBroadcastTool surfaces a device failure`() {
        val device = ScriptedDeviceController(sendBroadcastResult = DeviceActionResult.Failure("not supported"))
        assertIs<ToolResult.Failure>(SendBroadcastTool(device).execute(mapOf("action" to "com.example.TEST")))
    }
}
