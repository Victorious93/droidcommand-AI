package ai.droidcommand.skills.mcp

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.security.ApprovalPrompt

/** A fake MCP server connection: fixed tools, records every call, can be told to fail. */
class FakeMcpToolSource(
    private val tools: List<McpToolInfo>,
    var listFailure: Exception? = null,
    var callFailure: Exception? = null,
    var reply: (String, Map<String, String>) -> ToolResult = { name, args -> ToolResult.Success("$name ran with $args") },
) : McpToolSource {
    val calls = mutableListOf<Pair<String, Map<String, String>>>()
    var closed = false

    override fun listTools(): List<McpToolInfo> {
        listFailure?.let { throw it }
        return tools
    }

    override fun call(name: String, arguments: Map<String, String>): ToolResult {
        calls += name to arguments
        callFailure?.let { throw it }
        return reply(name, arguments)
    }

    override fun close() {
        closed = true
    }
}

/** Records every approval question and answers with [answer]. */
class RecordingApproval(var answer: Boolean = true) : ApprovalPrompt {
    val asked = mutableListOf<String>()

    override fun requestApproval(reason: String): Boolean {
        asked += reason
        return answer
    }

    override fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean = requestApproval(reason)
}

val READ_TOOL = McpToolInfo("search_notes", "Finds notes", readOnlyHint = true)
val WRITE_TOOL = McpToolInfo("add_note", "Adds a note", readOnlyHint = false, destructiveHint = false)
val DELETE_TOOL = McpToolInfo("delete_note", "Deletes a note", destructiveHint = true)
val UNHINTED_TOOL = McpToolInfo("mystery", "No hints")

val HOME = McpServerConfig(id = "home", label = "Home PC", url = "https://home.local/mcp")
