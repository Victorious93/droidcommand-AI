package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class NoopTool(
    name: String,
    allowedModes: Set<AgentMode> = AgentMode.entries.toSet(),
    requiredInitiator: Set<Initiator>? = null,
) : Tool {
    override val spec = ToolSpec(name = name, description = "no-op", allowedModes = allowedModes, requiredInitiator = requiredInitiator)
    override fun execute(input: Map<String, String>) = ToolResult.Success("noop")
}

class ToolRegistryTest {
    @Test
    fun `registers and retrieves a tool by name`() {
        val registry = ToolRegistry()
        registry.register(NoopTool("echo"))
        assertEquals("echo", registry.get("echo").spec.name)
    }

    @Test
    fun `rejects duplicate registration`() {
        val registry = ToolRegistry()
        registry.register(NoopTool("echo"))
        assertFailsWith<DuplicateToolException> {
            registry.register(NoopTool("echo"))
        }
    }

    @Test
    fun `throws for unknown tool lookup`() {
        val registry = ToolRegistry()
        assertFailsWith<UnknownToolException> {
            registry.get("does-not-exist")
        }
    }

    @Test
    fun `lists specs for all registered tools`() {
        val registry = ToolRegistry()
        registry.register(NoopTool("a"))
        registry.register(NoopTool("b"))
        assertEquals(setOf("a", "b"), registry.list().map { it.name }.toSet())
    }

    @Test
    fun `unfiltered list includes a mode-restricted tool regardless of its allowedModes`() {
        val registry = ToolRegistry()
        registry.register(NoopTool("pilot-only", allowedModes = setOf(AgentMode.PILOT)))
        assertEquals(setOf("pilot-only"), registry.list().map { it.name }.toSet())
    }

    @Test
    fun `filtering by mode excludes a tool not scoped to it`() {
        val registry = ToolRegistry()
        registry.register(NoopTool("pilot-only", allowedModes = setOf(AgentMode.PILOT)))
        registry.register(NoopTool("forge-only", allowedModes = setOf(AgentMode.FORGE)))
        registry.register(NoopTool("both"))

        assertEquals(setOf("pilot-only", "both"), registry.list(AgentMode.PILOT).map { it.name }.toSet())
        assertEquals(setOf("forge-only", "both"), registry.list(AgentMode.FORGE).map { it.name }.toSet())
    }

    @Test
    fun `unfiltered list includes an initiator-restricted tool regardless of its requiredInitiator`() {
        val registry = ToolRegistry()
        registry.register(NoopTool("owner-only", requiredInitiator = setOf(Initiator.DEVICE_OWNER)))
        assertEquals(setOf("owner-only"), registry.list().map { it.name }.toSet())
    }

    @Test
    fun `filtering by initiator excludes a tool not scoped to it`() {
        val registry = ToolRegistry()
        registry.register(NoopTool("owner-only", requiredInitiator = setOf(Initiator.DEVICE_OWNER)))
        registry.register(NoopTool("ai-only", requiredInitiator = setOf(Initiator.AI)))
        registry.register(NoopTool("unrestricted"))

        assertEquals(
            setOf("ai-only", "unrestricted"),
            registry.list(initiator = Initiator.AI).map { it.name }.toSet(),
        )
        assertEquals(
            setOf("owner-only", "unrestricted"),
            registry.list(initiator = Initiator.DEVICE_OWNER).map { it.name }.toSet(),
        )
    }
}
