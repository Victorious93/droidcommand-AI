package ai.droidcommand.tools.android

import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

class FindElementTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "find_element", description = "Finds UI element(s) on the current screen matching a selector")

    override fun execute(input: Map<String, String>): ToolResult {
        val selector = parseSelector(input).getOrElse { return ToolResult.Failure(it.message ?: "invalid selector") }
        val tree = when (val result = device.getUiTree()) {
            is UiTreeResult.Success -> result.tree
            is UiTreeResult.Failure -> return ToolResult.Failure(result.reason)
        }

        val matches = tree.findAll(selector)
        if (matches.isEmpty()) return ToolResult.Failure("No element matched selector: $selector")

        return ToolResult.Success(
            matches.joinToString("\n") { node ->
                "${node.className ?: "Node"} @${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}" +
                    (node.text?.let { " text=\"$it\"" } ?: "")
            },
        )
    }
}
