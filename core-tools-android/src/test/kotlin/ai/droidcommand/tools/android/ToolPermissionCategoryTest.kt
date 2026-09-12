package ai.droidcommand.tools.android

import ai.droidcommand.agent.PermissionCategory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Confirms every `core-tools-android` `Tool`'s `ToolSpec.permissionCategory`
 * (CAP-010) matches the category this module actually assigned it, so the
 * mapping is verified rather than merely asserted in a doc comment.
 * [TapTool] is covered by [DeviceToolSecureExecutorIntegrationTest] instead
 * (it also proves the category is enforced end to end, not just declared).
 */
class ToolPermissionCategoryTest {
    private val device = ScriptedDeviceController()

    @Test
    fun `productivity tools are all DEVICE_CONTROL`() {
        assertEquals(PermissionCategory.DEVICE_CONTROL, CreateCalendarEventTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.DEVICE_CONTROL, SetAlarmTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.DEVICE_CONTROL, SetTimerTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.DEVICE_CONTROL, SetReminderTool(device).spec.permissionCategory)
    }

    @Test
    fun `system query tools are VIEW, network state is NETWORK, and clipboard splits by read vs write`() {
        assertEquals(PermissionCategory.VIEW, GetBatteryStatusTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.NETWORK, GetNetworkStateTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.VIEW, GetStorageInfoTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.VIEW, GetClipboardTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.DEVICE_CONTROL, SetClipboardTool(device).spec.permissionCategory)
    }

    @Test
    fun `communications tools split VIEW (read) from DEVICE_CONTROL (act)`() {
        assertEquals(PermissionCategory.DEVICE_CONTROL, SendSmsTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.DEVICE_CONTROL, MakeCallTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.VIEW, ListContactsTool(device).spec.permissionCategory)
    }

    @Test
    fun `UI automation tools are AUTOMATION, read-only device tools are VIEW`() {
        assertEquals(PermissionCategory.AUTOMATION, SwipeTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.AUTOMATION, TypeTextTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.AUTOMATION, PressKeyTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.DEVICE_CONTROL, LaunchAppTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.VIEW, ListInstalledAppsTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.VIEW, GetUiTreeTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.VIEW, TakeScreenshotTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.VIEW, GetDeviceInfoTool(device).spec.permissionCategory)
    }

    @Test
    fun `all file tools are FILES, including reads and directory listing`() {
        assertEquals(PermissionCategory.FILES, ReadFileTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.FILES, WriteFileTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.FILES, MoveFileTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.FILES, CopyFileTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.FILES, DeleteFileTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.FILES, ListDirectoryTool(device).spec.permissionCategory)
    }

    @Test
    fun `media tools are all DEVICE_CONTROL`() {
        assertEquals(PermissionCategory.DEVICE_CONTROL, MediaPlayPauseTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.DEVICE_CONTROL, MediaNextTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.DEVICE_CONTROL, MediaPreviousTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.DEVICE_CONTROL, SetVolumeTool(device).spec.permissionCategory)
    }

    @Test
    fun `navigate_to is DEVICE_CONTROL, find_element is VIEW, tap_element is AUTOMATION`() {
        assertEquals(PermissionCategory.DEVICE_CONTROL, NavigateToTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.VIEW, FindElementTool(device).spec.permissionCategory)
        assertEquals(PermissionCategory.AUTOMATION, TapElementTool(device).spec.permissionCategory)
    }
}
