package ai.droidforge.agent

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

    fun list(): List<ToolSpec> = tools.values.map { it.spec }
}
