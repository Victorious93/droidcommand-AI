package ai.droidcommand.agent

class DuplicateToolException(name: String) : IllegalArgumentException("Tool '$name' is already registered")
class UnknownToolException(name: String) : NoSuchElementException("No tool registered under '$name'")

/**
 * Discovery + lookup for every registered [Tool]. Registration is the only integration point a new tool needs.
 *
 * Thread-safe: tools discovered at runtime (for example an opt-in MCP server's tools, see
 * `core-skills` `McpToolCatalog`) are registered and [unregister]ed from a background thread
 * while Chat or a planner may be listing or looking up tools.
 */
class ToolRegistry {
    private val tools = linkedMapOf<String, Tool>()

    fun register(tool: Tool) = synchronized(tools) {
        val name = tool.spec.name
        if (tools.containsKey(name)) throw DuplicateToolException(name)
        tools[name] = tool
    }

    /**
     * Removes the tool registered under [name], if any. Returns true when something was removed.
     * Only runtime-discovered tools are meant to be removed; built-in tools stay for the app's lifetime.
     */
    fun unregister(name: String): Boolean = synchronized(tools) { tools.remove(name) != null }

    fun get(name: String): Tool = synchronized(tools) { tools[name] } ?: throw UnknownToolException(name)

    /**
     * All registered tool specs, or only those whose [ToolSpec.allowedModes]
     * includes [mode] when one is given. A planner is only ever handed the
     * filtered list for its own mode, so it cannot select a tool the current
     * mode doesn't offer in the first place.
     *
     * When [initiator] is given, a tool whose [ToolSpec.requiredInitiator]
     * doesn't include it is filtered out the same way — in particular,
     * [ObjectiveEngine] always plans as [Initiator.AI], so a
     * `DEVICE_OWNER`/`REMOTE`-restricted tool is never even offered to the
     * planner, let alone selectable, from an AI-driven objective.
     */
    fun list(mode: AgentMode? = null, initiator: Initiator? = null): List<ToolSpec> =
        synchronized(tools) { tools.values.map { it.spec } }
            .filter { mode == null || mode in it.allowedModes }
            .filter { initiator == null || it.requiredInitiator == null || initiator in it.requiredInitiator }
}
