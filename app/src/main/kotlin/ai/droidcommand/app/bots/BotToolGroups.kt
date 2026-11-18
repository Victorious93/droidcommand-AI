package ai.droidcommand.app.bots

import ai.droidcommand.skills.mcp.McpBridgedTool

/** A named set of tools shown together in the Bot maker so a long list reads as a few sections. */
data class BotToolGroup(val key: String, val title: String, val tools: List<BotToolOption>)

/**
 * Sorts the flat tool list into sections: what the tool is for, and — for MCP tools — which MCP server it came from,
 * so a bot can be given one server's tools in a tap. Pure naming rules; a tool no rule knows lands in "Other", and
 * every tool appears in exactly one group.
 */
object BotToolGroups {
    private const val MCP_PREFIX = "mcp:"

    private val builtIn: List<Pair<String, (String) -> Boolean>> = listOf(
        "Terminal & root" to { n -> n in setOf("run_shell_command", "run_root_command", "run_termux_command") || n.startsWith("run_metasploit") || n.startsWith("run_setoolkit") },
        "Files" to { n -> n in setOf("copy_file", "delete_file", "list_directory", "move_file", "read_file", "write_file") },
        "Screen control" to { n -> n in setOf("tap", "swipe", "type_text", "press_key", "find_element", "tap_element", "get_ui_tree", "take_screenshot", "navigate_to") },
        "Phone" to { n ->
            n.startsWith("get_") || n.startsWith("set_") || n.startsWith("media_") || n.startsWith("list_") ||
                n in setOf("launch_app", "send_broadcast", "create_calendar_event", "make_call", "send_sms")
        },
        "Build" to { n -> n in setOf("build_project", "deploy_and_launch") },
        "Web & AI" to { n -> n == "web_search" || n == "ask_ai" },
        "Notes & memory" to { n -> n.startsWith("notes_") || n.startsWith("brain_") || n.startsWith("memory_") },
        "Bots" to { n -> n.startsWith("bot_") || n.startsWith("sandbox_") },
        "Fleet & remote" to { n ->
            n.startsWith("fleet_") || n.startsWith("remote_") || n.startsWith("rootforge_") || n.startsWith("mesh_") ||
                n.startsWith("filemanager_") || n.startsWith("victorsuite_") || n.startsWith("n8n_") ||
                n.startsWith("pentest_swarm_") || n.startsWith("run_hackerai")
        },
    )

    /**
     * [mcpServerLabelOf] gives the server label for an MCP tool name, or null when it is not currently loaded.
     * `set_` and `list_` are matched after the more specific groups, so e.g. `list_directory` stays in Files.
     */
    fun group(options: List<BotToolOption>, mcpServerLabelOf: (String) -> String? = { null }): List<BotToolGroup> {
        val byKey = linkedMapOf<String, MutableList<BotToolOption>>()
        val titles = mutableMapOf<String, String>()
        for (tool in options) {
            val key: String
            if (McpBridgedTool.isMcpTool(tool.name)) {
                val label = mcpServerLabelOf(tool.name)?.takeIf { it.isNotBlank() } ?: "an MCP server"
                key = MCP_PREFIX + label
                titles[key] = "MCP · $label"
            } else {
                val title = builtIn.firstOrNull { (_, matches) -> matches(tool.name) }?.first ?: "Other"
                key = title
                titles[key] = title
            }
            byKey.getOrPut(key) { mutableListOf() } += tool
        }
        val builtInOrder = builtIn.map { it.first } + "Other"
        val ordered = byKey.keys.sortedWith(
            compareBy<String> { if (it.startsWith(MCP_PREFIX)) 1 else 0 }
                .thenBy { if (it.startsWith(MCP_PREFIX)) 0 else builtInOrder.indexOf(it) }
                .thenBy { it.lowercase() },
        )
        return ordered.map { key -> BotToolGroup(key, titles.getValue(key), byKey.getValue(key).sortedBy { it.name }) }
    }

    /** Tools in [options] whose name or description contains [query] (case-insensitive); everything for a blank query. */
    fun filter(options: List<BotToolOption>, query: String): List<BotToolOption> {
        val q = query.trim()
        return if (q.isEmpty()) options else options.filter { it.name.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true) }
    }
}
