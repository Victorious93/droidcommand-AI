package ai.droidcommand.tools.android

import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Media controls (ROADMAP-041 / master prompt Phase 5 "Media": "supported
 * media controls"). All four are [SecurityLevel.SENSITIVE], consistent with
 * every other mutating tool in this package (`tap`, `write_file`,
 * `set_alarm`, ...) — none of those are more dangerous in isolation than
 * skipping a track or changing the volume, but this module has never made
 * an exception for a mutation just because its real-world impact happens
 * to be small, and doesn't start here.
 */
class MediaPlayPauseTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "media_play_pause", description = "Toggles play/pause for the current media session", securityLevel = SecurityLevel.SENSITIVE)

    override fun execute(input: Map<String, String>): ToolResult = toToolResult(device.mediaPlayPause())
}

class MediaNextTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "media_next", description = "Skips to the next media track", securityLevel = SecurityLevel.SENSITIVE)

    override fun execute(input: Map<String, String>): ToolResult = toToolResult(device.mediaNext())
}

class MediaPreviousTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "media_previous", description = "Skips to the previous media track", securityLevel = SecurityLevel.SENSITIVE)

    override fun execute(input: Map<String, String>): ToolResult = toToolResult(device.mediaPrevious())
}

class SetVolumeTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "set_volume", description = "Sets the device's media volume as a percentage (0-100)", securityLevel = SecurityLevel.SENSITIVE)

    override fun execute(input: Map<String, String>): ToolResult {
        val levelPercent = input["levelPercent"]?.toIntOrNull() ?: return ToolResult.Failure("Missing or invalid required input 'levelPercent'")
        if (levelPercent !in 0..100) return ToolResult.Failure("Input 'levelPercent' must be between 0 and 100, got $levelPercent")
        return toToolResult(device.setVolume(levelPercent))
    }
}
