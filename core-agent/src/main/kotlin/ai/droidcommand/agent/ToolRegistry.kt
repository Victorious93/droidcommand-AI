package ai.droidcommand.agent

class DuplicateToolException(name: String) : IllegalArgumentException("Tool '$name' is already registered")
class UnknownToolException(name: String) : NoSuchElementException("No tool registered under '$name'")

/** Discovery + lookup for every registered [Tool]. Registration is the only integration point a new tool needs. */
class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        val name = tool.spec.name
        if (tools.containsKey(name)) throw DuplicateToolException(name)
        tools[name] = tool
    }

    fun get(name: String): Tool = tools[name] ?: throw UnknownToolException(name)

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
        tools.values.map { it.spec }.filter { mode == null || mode in it.allowedModes }
            .filter { initiator == null || it.requiredInitiator == null || initiator in it.requiredInitiator }
}
