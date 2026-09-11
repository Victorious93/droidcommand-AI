package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeCapabilityTool(name: String) : Tool {
    override val spec = ToolSpec(name = name, description = "no-op")
    override fun execute(input: Map<String, String>) = ToolResult.Success("noop")
}

private fun capability(id: String, targetType: ExecutionTargetType = ExecutionTargetType.LOCAL_PC) =
    RegisteredCapability(CapabilityId(id), targetType, FakeCapabilityTool(id))

private fun request(id: String, targetType: ExecutionTargetType = ExecutionTargetType.LOCAL_PC) =
    ExecutionRequest(CapabilityId(id), targetType, emptyMap(), RiskTier.READ_ONLY)

class CapabilityIdTest {
    @Test
    fun `accepts a valid lowercase, dotted, namespace-qualified id`() {
        assertEquals("shell.echo-v1", CapabilityId("shell.echo-v1").value)
    }

    @Test
    fun `accepts an id starting with a digit`() {
        assertEquals("1password", CapabilityId("1password").value)
    }

    @Test
    fun `rejects an uppercase id`() {
        assertFailsWith<IllegalArgumentException> { CapabilityId("Shell.Echo") }
    }

    @Test
    fun `rejects an id starting with a dot, dash, or underscore`() {
        assertFailsWith<IllegalArgumentException> { CapabilityId(".shell") }
        assertFailsWith<IllegalArgumentException> { CapabilityId("-shell") }
        assertFailsWith<IllegalArgumentException> { CapabilityId("_shell") }
    }

    @Test
    fun `rejects an empty id`() {
        assertFailsWith<IllegalArgumentException> { CapabilityId("") }
    }
}

class InMemoryCapabilityRegistryTest {
    @Test
    fun `registers and retrieves a capability by id`() {
        val registry = InMemoryCapabilityRegistry()
        val cap = capability("shell.echo")

        registry.register(cap)

        assertEquals(cap, registry.lookup(CapabilityId("shell.echo")))
    }

    @Test
    fun `lookup of an unregistered id returns null`() {
        assertNull(InMemoryCapabilityRegistry().lookup(CapabilityId("nope")))
    }

    @Test
    fun `rejects duplicate registration`() {
        val registry = InMemoryCapabilityRegistry()
        registry.register(capability("shell.echo"))

        assertFailsWith<DuplicateCapabilityException> { registry.register(capability("shell.echo")) }
    }

    @Test
    fun `list returns every registered id, sorted`() {
        val registry = InMemoryCapabilityRegistry()
        registry.register(capability("zeta"))
        registry.register(capability("alpha"))

        assertEquals(listOf(CapabilityId("alpha"), CapabilityId("zeta")), registry.list())
    }
}

class CheckCapabilityAvailabilityTest {
    @Test
    fun `an unregistered capability is reported unavailable`() {
        val registry = InMemoryCapabilityRegistry()

        val result = checkCapabilityAvailability(request("nope"), registry)

        val unavailable = assertIs<ExecutionResponse.CapabilityUnavailable>(result)
        assertEquals(CapabilityId("nope"), unavailable.capabilityId)
        assertTrue(unavailable.reason.contains("not registered"))
    }

    @Test
    fun `a registered capability requested for a different target type is reported unavailable`() {
        val registry = InMemoryCapabilityRegistry()
        registry.register(capability("shell.echo", ExecutionTargetType.LOCAL_PC))

        val result = checkCapabilityAvailability(request("shell.echo", ExecutionTargetType.ANDROID), registry)

        val unavailable = assertIs<ExecutionResponse.CapabilityUnavailable>(result)
        assertTrue(unavailable.reason.contains("LOCAL_PC"))
        assertTrue(unavailable.reason.contains("ANDROID"))
    }

    @Test
    fun `a registered capability matching the requested target type is available`() {
        val registry = InMemoryCapabilityRegistry()
        registry.register(capability("shell.echo", ExecutionTargetType.LOCAL_PC))

        assertNull(checkCapabilityAvailability(request("shell.echo", ExecutionTargetType.LOCAL_PC), registry))
    }
}
