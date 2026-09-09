package ai.droidcommand.tools.android

import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Productivity (ROADMAP-040 / master prompt Phase 5 "Productivity":
 * calendar, alarms, timers, reminders). All four are
 * [SecurityLevel.SENSITIVE]: every one of them schedules something that
 * will surface to the device's owner later (a notification, a ringing
 * alarm, an event on their calendar) — the same "mutates real device
 * state, not just reads it" reasoning [SetClipboardTool]/[SendSmsTool]
 * already apply, not [SecurityLevel.NORMAL] like a read-only query.
 */
class CreateCalendarEventTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "create_calendar_event", description = "Creates a calendar event", securityLevel = SecurityLevel.SENSITIVE)

    override fun execute(input: Map<String, String>): ToolResult {
        val title = input["title"] ?: return ToolResult.Failure("Missing required input 'title'")
        val start = input["startEpochMillis"]?.toLongOrNull() ?: return ToolResult.Failure("Missing or invalid required input 'startEpochMillis'")
        val end = input["endEpochMillis"]?.toLongOrNull() ?: return ToolResult.Failure("Missing or invalid required input 'endEpochMillis'")
        return toToolResult(device.createCalendarEvent(title, start, end))
    }
}

class SetAlarmTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "set_alarm", description = "Sets a device alarm for a given time of day", securityLevel = SecurityLevel.SENSITIVE)

    override fun execute(input: Map<String, String>): ToolResult {
        val hour = input["hour"]?.toIntOrNull() ?: return ToolResult.Failure("Missing or invalid required input 'hour'")
        val minute = input["minute"]?.toIntOrNull() ?: return ToolResult.Failure("Missing or invalid required input 'minute'")
        return toToolResult(device.setAlarm(hour, minute, input["label"]))
    }
}

class SetTimerTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "set_timer", description = "Sets a countdown timer", securityLevel = SecurityLevel.SENSITIVE)

    override fun execute(input: Map<String, String>): ToolResult {
        val durationSeconds = input["durationSeconds"]?.toLongOrNull() ?: return ToolResult.Failure("Missing or invalid required input 'durationSeconds'")
        return toToolResult(device.setTimer(durationSeconds, input["label"]))
    }
}

class SetReminderTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "set_reminder", description = "Sets a reminder due at a given time", securityLevel = SecurityLevel.SENSITIVE)

    override fun execute(input: Map<String, String>): ToolResult {
        val text = input["text"] ?: return ToolResult.Failure("Missing required input 'text'")
        val dueEpochMillis = input["dueEpochMillis"]?.toLongOrNull() ?: return ToolResult.Failure("Missing or invalid required input 'dueEpochMillis'")
        return toToolResult(device.setReminder(text, dueEpochMillis))
    }
}
