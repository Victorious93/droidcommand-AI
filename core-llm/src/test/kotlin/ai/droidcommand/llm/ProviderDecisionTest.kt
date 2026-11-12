package ai.droidcommand.llm

import ai.droidcommand.agent.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [DefaultAiProviderSelector.explain]: the ranked fallback chain and the reason each provider was left out. */
class ProviderDecisionTest {
    private class Named(name: String) : LlmProvider {
        override val config = LlmConfig(provider = name, model = name)

        override fun complete(request: LlmRequest): LlmResponse = LlmResponse.Text(config.provider)
    }

    private fun registered(id: String, type: ProviderType, context: Int = 8_000, available: Boolean = true, tools: Boolean = false) =
        RegisteredProvider(
            AiProviderInfo(id, id, type, context, available, capabilities = if (tools) setOf(ProviderCapability.TOOL_CALLING) else emptySet()),
            Named(id),
        )

    private val task = Task("t", "chat")
    private val device = registered("device", ProviderType.LOCAL, context = 2_048)
    private val pc = registered("pc", ProviderType.SELF_HOSTED, tools = true)
    private val cloud = registered("cloud", ProviderType.CLOUD, tools = true)
    private val selector = DefaultAiProviderSelector(listOf(cloud, device, pc))

    @Test
    fun `without a type order the ranking is local-first, as before`() {
        assertEquals(listOf("device", "pc", "cloud"), selector.explain(task).map { it.registered.info.id })
        assertSame(device.provider, selector.selectProvider(task))
    }

    @Test
    fun `a type order ranks the PC before this device, and allowed types leave the cloud out with the reason`() {
        val decisions = selector.explain(
            task,
            ProviderPreferences(
                allowedTypes = setOf(ProviderType.SELF_HOSTED, ProviderType.LOCAL),
                typeOrder = listOf(ProviderType.SELF_HOSTED, ProviderType.LOCAL),
            ),
        )
        assertEquals(listOf("pc", "device"), decisions.filter { it.eligible }.map { it.registered.info.id })
        val excluded = decisions.single { !it.eligible }
        assertEquals("cloud", excluded.registered.info.id)
        assertTrue(excluded.excludedBecause!!.contains("not allowed by the selected policy"))
    }

    @Test
    fun `capability, availability and context shortfalls are each named`() {
        val off = registered("pc-off", ProviderType.SELF_HOSTED, available = false, tools = true)
        val decisions = DefaultAiProviderSelector(listOf(device, off, cloud)).explain(
            task,
            ProviderPreferences(requiredCapabilities = setOf(ProviderCapability.TOOL_CALLING), minContextTokens = 4_000),
        ).associateBy { it.registered.info.id }
        assertTrue(decisions.getValue("device").excludedBecause!!.contains("does not support TOOL_CALLING"))
        assertEquals("it is not available", decisions.getValue("pc-off").excludedBecause)
        assertNull(decisions.getValue("cloud").excludedBecause)
        val small = DefaultAiProviderSelector(listOf(device)).explain(task, ProviderPreferences(minContextTokens = 4_000)).single()
        assertTrue(small.excludedBecause!!.contains("context window (2048 tokens) is smaller than the request needs (4000)"))
    }

    @Test
    fun `the router's fallback rule is the one exposed to callers`() {
        assertTrue(ModelRouter.fallsBackOn(LlmError.Network("x")))
        assertTrue(ModelRouter.fallsBackOn(LlmError.Timeout("x")))
        assertTrue(ModelRouter.fallsBackOn(LlmError.ModelUnavailable("x")))
        assertTrue(!ModelRouter.fallsBackOn(LlmError.Authentication("x")))
        assertTrue(!ModelRouter.fallsBackOn(LlmError.InvalidResponse("x")))
        assertTrue(!ModelRouter.fallsBackOn(LlmError.Cancelled("x")))
    }
}
