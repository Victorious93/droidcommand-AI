package ai.droidcommand.skills.mcp

/**
 * What a helper bot's saved MCP tool is tied to: one saved server entry ([McpServerConfig.instanceId]) and the
 * exact name the server gives the tool. The local tool name (`mcp_<id>_<tool>`) is not enough: a different
 * server can be added under the same label and get the same id, and two remote names can clean up to one local
 * name.
 */
data class McpToolBinding(val serverInstance: String, val remoteName: String)

/** One MCP tool that is in the registry right now. */
data class McpLoadedTool(val name: String, val binding: McpToolBinding, val serverLabel: String)

/** What a helper bot's saved tool list means right now. Names in the three lists are the bot's saved names. */
data class McpResolution(
    /** Every tool name the bot may be offered and may call now: its built-in tools plus [current]'s values. */
    val allowed: Set<String>,
    /** Saved MCP name → the name that same tool has in the registry now (nearly always the same name). */
    val current: Map<String, String>,
    /** Picked and still tied to a saved server, but not loaded now (MCP or the server is off, down, or dropped it). */
    val unavailable: List<String>,
    /**
     * Must be picked again by the owner: the server it was picked from was removed (a server added later under the
     * same label is a different server), or it was saved before tools were tied to a server and it is not clear
     * which tool was meant. Never offered and never callable until the owner ticks it again in Bot maker.
     */
    val needsReselect: List<String>,
    /** Bindings worked out now for tools saved before bindings existed. Save them with the bot. */
    val adopted: Map<String, McpToolBinding>,
)

/**
 * Ties a helper bot's saved MCP tools to the server and tool the owner picked, and works out what the bot may
 * use right now. Built-in tools are passed through untouched.
 *
 * This only narrows what a bot can reach. An allowed tool still goes through the bot's `ScopedToolRunner`,
 * `SecureToolExecutor` and the approval dialogs like before.
 */
object McpSelections {
    /** At save time: ties each ticked MCP tool to what is loaded under that name now. An MCP name that is not loaded gets nothing. */
    fun bind(picked: Set<String>, loaded: List<McpLoadedTool>): Map<String, McpToolBinding> {
        val byName = loaded.associateBy { it.name }
        return picked.filter { McpBridgedTool.isMcpTool(it) }.mapNotNull { name -> byName[name]?.let { name to it.binding } }.toMap()
    }

    /**
     * @param allowedTools the bot's saved tool names (built-in and MCP).
     * @param bindings the bot's saved bindings, by saved MCP name.
     * @param loaded the MCP tools in the registry now ([McpToolCatalog.loadedTools]).
     * @param servers the owner's saved servers, turned on or not.
     * @param answered instance ids of the servers whose tool list was loaded in the last refresh.
     */
    fun resolve(
        allowedTools: Set<String>,
        bindings: Map<String, McpToolBinding>,
        loaded: List<McpLoadedTool>,
        servers: List<McpServerConfig>,
        answered: Set<String>,
    ): McpResolution {
        val byBinding = loaded.associateBy { it.binding }
        val byName = loaded.associateBy { it.name }
        val configured = servers.map { it.instanceId }.toSet()
        val current = linkedMapOf<String, String>()
        val unavailable = mutableListOf<String>()
        val needsReselect = mutableListOf<String>()
        val adopted = linkedMapOf<String, McpToolBinding>()

        for (name in allowedTools.filter { McpBridgedTool.isMcpTool(it) }.sorted()) {
            val saved = bindings[name]
            if (saved != null) {
                when {
                    saved.serverInstance !in configured -> needsReselect += name
                    else -> byBinding[saved]?.let { current[name] = it.name } ?: unavailable.add(name)
                }
                continue
            }
            // Saved before bindings existed. Only a server that was itself saved back then can be the one meant,
            // and only when exactly one of its tools has this name.
            val serverId = McpBridgedTool.serverIdOf(name)
            val legacy = serverId?.let { McpServerConfig.legacyInstanceId(it) }
            val tool = byName[name]
            when {
                legacy == null || legacy !in configured -> needsReselect += name
                tool != null && tool.binding.serverInstance == legacy &&
                    McpBridgedTool.toolName(serverId, tool.binding.remoteName) == name -> {
                    current[name] = name
                    adopted[name] = tool.binding
                }
                legacy in answered -> needsReselect += name
                else -> unavailable += name
            }
        }
        return McpResolution(
            allowed = allowedTools.filterNot { McpBridgedTool.isMcpTool(it) }.toSet() + current.values,
            current = current,
            unavailable = unavailable,
            needsReselect = needsReselect,
            adopted = adopted,
        )
    }

    /** Plain words for the bot's card and for Chat, or null when nothing needs picking again. */
    fun reselectWords(needsReselect: List<String>): String? =
        if (needsReselect.isEmpty()) {
            null
        } else {
            "Pick again: ${needsReselect.sorted().joinToString(", ")}. The server was replaced or removed, or the tool " +
                "name is not clear any more, so this bot cannot use ${if (needsReselect.size == 1) "it" else "them"}. " +
                "Tap Edit, tick the tool you want and save."
        }
}
