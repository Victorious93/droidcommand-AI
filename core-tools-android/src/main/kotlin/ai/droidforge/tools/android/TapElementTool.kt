package ai.droidforge.tools.android

import ai.droidforge.agent.SecurityLevel
import ai.droidforge.agent.Tool
import ai.droidforge.agent.ToolResult
import ai.droidforge.agent.ToolSpec

/**
 * The agent-friendly composition of [FindElementTool] + [TapTool]: "tap the
 * element matching this selector" rather than requiring the caller to
 * compute pixel coordinates itself. Reads the current UI tree, finds the
 * first matching node, and taps its bounds' center — never invented
 * coordinates, always derived from a node [DeviceController] actually
 * reported.
 */
class TapElementTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "tap_element", description = "Finds a UI element by selector and taps its center", securityLevel = SecurityLevel.SENSITIVE)

    override fun execute(input: Map<String, String>): ToolResult {
        val selector = parseSelector(input).getOrElse { return ToolResult.Failure(it.message ?: "invalid selector") }
        val tree = when (val result = device.getUiTree()) {
            is UiTreeResult.Success -> result.tree
            is UiTreeResult.Failure -> return ToolResult.Failure(result.reason)
        }

        val node = tree.findFirst(selector) ?: return ToolResult.Failure("No element matched selector: $selector")

        return when (val action = device.tap(node.bounds.centerX, node.bounds.centerY)) {
            is DeviceActionResult.Success -> ToolResult.Success(action.message)
            is DeviceActionResult.Failure -> ToolResult.Failure(action.reason, action.cause)
        }
    }
}
