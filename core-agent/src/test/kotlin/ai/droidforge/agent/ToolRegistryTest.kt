package ai.droidforge.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class NoopTool(name: String) : Tool {
    override val spec = ToolSpec(name = name, description = "no-op")
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
}
