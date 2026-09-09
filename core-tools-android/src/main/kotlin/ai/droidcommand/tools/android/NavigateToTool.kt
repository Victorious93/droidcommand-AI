package ai.droidcommand.tools.android

import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Navigation (ROADMAP-042 / master prompt Phase 5 "Navigation": "launch
 * supported navigation applications/intents"). This is the last of the six
 * master-prompt Phase 5 device-tool categories. [SecurityLevel.SENSITIVE],
 * matching [LaunchAppTool] — launching a navigation intent is a launch-app
 * action in substance (it hands control to another app with an implicit
 * destination), not a read.
 */
class NavigateToTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "navigate_to", description = "Launches navigation to a destination via the device's navigation app", securityLevel = SecurityLevel.SENSITIVE)

    override fun execute(input: Map<String, String>): ToolResult {
        val destination = input["destination"] ?: return ToolResult.Failure("Missing required input 'destination'")
        val mode = input["mode"]?.let {
            runCatching { NavigationMode.valueOf(it.uppercase()) }
                .getOrElse { return ToolResult.Failure("Unknown navigation mode '$it'; expected one of ${NavigationMode.entries.joinToString()}") }
        } ?: NavigationMode.DRIVING
        return toToolResult(device.launchNavigation(destination, mode))
    }
}
