package ai.droidcommand.tools.android

import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProductivityToolsTest {
    @Test
    fun `CreateCalendarEventTool delegates title and start-end times`() {
        val device = ScriptedDeviceController(createCalendarEventResult = DeviceActionResult.Success("created"))
        val result = CreateCalendarEventTool(device).execute(
            mapOf("title" to "Standup", "startEpochMillis" to "1000", "endEpochMillis" to "2000"),
        )
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf(Triple("Standup", 1000L, 2000L)), device.createCalendarEventCalls)
    }

    @Test
    fun `CreateCalendarEventTool fails without calling the device when endEpochMillis is missing`() {
        val device = ScriptedDeviceController()
        val result = CreateCalendarEventTool(device).execute(mapOf("title" to "Standup", "startEpochMillis" to "1000"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.createCalendarEventCalls.size)
    }

    @Test
    fun `CreateCalendarEventTool fails without calling the device when startEpochMillis is not a number`() {
        val device = ScriptedDeviceController()
        val result = CreateCalendarEventTool(device).execute(mapOf("title" to "Standup", "startEpochMillis" to "not-a-number", "endEpochMillis" to "2000"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.createCalendarEventCalls.size)
    }

    @Test
    fun `SetAlarmTool delegates hour, minute, and an optional label`() {
        val device = ScriptedDeviceController(setAlarmResult = DeviceActionResult.Success("set"))
        val result = SetAlarmTool(device).execute(mapOf("hour" to "7", "minute" to "30", "label" to "wake up"))
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf<Triple<Int, Int, String?>>(Triple(7, 30, "wake up")), device.setAlarmCalls)
    }

    @Test
    fun `SetAlarmTool defaults label to null when omitted`() {
        val device = ScriptedDeviceController(setAlarmResult = DeviceActionResult.Success("set"))
        SetAlarmTool(device).execute(mapOf("hour" to "7", "minute" to "30"))
        assertEquals(listOf<Triple<Int, Int, String?>>(Triple(7, 30, null)), device.setAlarmCalls)
    }

    @Test
    fun `SetAlarmTool fails without calling the device when minute is missing`() {
        val device = ScriptedDeviceController()
        val result = SetAlarmTool(device).execute(mapOf("hour" to "7"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.setAlarmCalls.size)
    }

    @Test
    fun `SetTimerTool delegates duration and an optional label`() {
        val device = ScriptedDeviceController(setTimerResult = DeviceActionResult.Success("set"))
        val result = SetTimerTool(device).execute(mapOf("durationSeconds" to "90", "label" to "tea"))
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf<Pair<Long, String?>>(90L to "tea"), device.setTimerCalls)
    }

    @Test
    fun `SetTimerTool fails without calling the device when durationSeconds is missing`() {
        val device = ScriptedDeviceController()
        val result = SetTimerTool(device).execute(emptyMap())
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.setTimerCalls.size)
    }

    @Test
    fun `SetReminderTool delegates text and due time`() {
        val device = ScriptedDeviceController(setReminderResult = DeviceActionResult.Success("set"))
        val result = SetReminderTool(device).execute(mapOf("text" to "call mom", "dueEpochMillis" to "5000"))
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf("call mom" to 5000L), device.setReminderCalls)
    }

    @Test
    fun `SetReminderTool fails without calling the device when text is missing`() {
        val device = ScriptedDeviceController()
        val result = SetReminderTool(device).execute(mapOf("dueEpochMillis" to "5000"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.setReminderCalls.size)
    }
}
