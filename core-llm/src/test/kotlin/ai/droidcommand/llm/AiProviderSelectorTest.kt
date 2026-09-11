package ai.droidcommand.llm

import ai.droidcommand.agent.ComplexityThresholds
import ai.droidcommand.agent.DefaultTokenBudgetManager
import ai.droidcommand.agent.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

private class NamedLlmProvider(name: String) : LlmProvider {
    override val config = LlmConfig(provider = name, model = "$name-1")

    override fun complete(request: LlmRequest): LlmResponse = LlmResponse.Text(config.provider)
}

private fun info(
    id: String,
    type: ProviderType = ProviderType.LOCAL,
    maxContextTokens: Int = 8000,
    available: Boolean = true,
    cost: Cost? = null,
    capabilities: Set<ProviderCapability> = emptySet(),
) = AiProviderInfo(
    id = id,
    name = id,
    type = type,
    maxContextTokens = maxContextTokens,
    available = available,
    cost = cost,
    capabilities = capabilities,
)

private val task = Task(id = "t1", description = "do the thing")

class AiProviderSelectorTest {
    @Test
    fun `selects the only eligible provider`() {
        val provider = NamedLlmProvider("only")
        val selector = DefaultAiProviderSelector(listOf(RegisteredProvider(info("only"), provider)))

        assertSame(provider, selector.selectProvider(task))
    }

    @Test
    fun `returns null when no providers are registered`() {
        val selector = DefaultAiProviderSelector(emptyList())

        assertNull(selector.selectProvider(task))
    }

    @Test
    fun `excludes an unavailable provider`() {
        val available = NamedLlmProvider("up")
        val unavailable = NamedLlmProvider("down")
        val selector = DefaultAiProviderSelector(
            listOf(
                RegisteredProvider(info("down", available = false), unavailable),
                RegisteredProvider(info("up"), available),
            ),
        )

        assertSame(available, selector.selectProvider(task))
    }

    @Test
    fun `excludes a provider missing a required capability`() {
        val toolCalling = NamedLlmProvider("tools")
        val textOnly = NamedLlmProvider("text-only")
        val selector = DefaultAiProviderSelector(
            listOf(
                RegisteredProvider(info("text-only"), textOnly),
                RegisteredProvider(info("tools", capabilities = setOf(ProviderCapability.TOOL_CALLING)), toolCalling),
            ),
        )

        val result = selector.selectProvider(
            task,
            ProviderPreferences(requiredCapabilities = setOf(ProviderCapability.TOOL_CALLING)),
        )

        assertSame(toolCalling, result)
    }

    @Test
    fun `excludes a provider whose max context is below an explicit minimum`() {
        val small = NamedLlmProvider("small")
        val large = NamedLlmProvider("large")
        val selector = DefaultAiProviderSelector(
            listOf(
                RegisteredProvider(info("small", maxContextTokens = 1000), small),
                RegisteredProvider(info("large", maxContextTokens = 20000), large),
            ),
        )

        val result = selector.selectProvider(task, ProviderPreferences(minContextTokens = 5000))

        assertSame(large, result)
    }

    @Test
    fun `derives a minimum context requirement from Task complexity via an injected TokenBudgetManager`() {
        val small = NamedLlmProvider("small")
        val large = NamedLlmProvider("large")
        // A long description with many dependencies/verification criteria pushes selectBudget to FULL (16000 tokens).
        val complexTask = Task(
            id = "t2",
            description = "x".repeat(4000),
            dependencies = setOf("a", "b", "c", "d"),
            verificationCriteria = listOf("v1", "v2", "v3", "v4"),
        )
        val budgetManager = DefaultTokenBudgetManager(ComplexityThresholds())
        val selector = DefaultAiProviderSelector(
            listOf(
                RegisteredProvider(info("small", maxContextTokens = 8000), small),
                RegisteredProvider(info("large", maxContextTokens = 20000), large),
            ),
            tokenBudgetManager = budgetManager,
        )

        val result = selector.selectProvider(complexTask)

        assertSame(large, result)
    }

    @Test
    fun `without an injected TokenBudgetManager and no explicit minimum, context length is not filtered`() {
        val small = NamedLlmProvider("small")
        val selector = DefaultAiProviderSelector(listOf(RegisteredProvider(info("small", maxContextTokens = 100), small)))

        assertSame(small, selector.selectProvider(task))
    }

    @Test
    fun `requireLocal excludes cloud providers`() {
        val cloud = NamedLlmProvider("cloud")
        val local = NamedLlmProvider("local")
        val selector = DefaultAiProviderSelector(
            listOf(
                RegisteredProvider(info("cloud", type = ProviderType.CLOUD), cloud),
                RegisteredProvider(info("local", type = ProviderType.LOCAL), local),
            ),
        )

        val result = selector.selectProvider(task, ProviderPreferences(requireLocal = true))

        assertSame(local, result)
    }

    @Test
    fun `a cost ceiling excludes both an over-priced provider and one with unknown cost`() {
        val overPriced = NamedLlmProvider("expensive")
        val unknownCost = NamedLlmProvider("unknown-cost")
        val withinBudget = NamedLlmProvider("cheap")
        val selector = DefaultAiProviderSelector(
            listOf(
                RegisteredProvider(info("expensive", cost = Cost(50.0, 100.0)), overPriced),
                RegisteredProvider(info("unknown-cost", cost = null), unknownCost),
                RegisteredProvider(info("cheap", cost = Cost(1.0, 2.0)), withinBudget),
            ),
        )

        val result = selector.selectProvider(task, ProviderPreferences(maxCostPerMillionInputTokens = 5.0))

        assertSame(withinBudget, result)
    }

    @Test
    fun `preferredProviderId wins when eligible`() {
        val a = NamedLlmProvider("a")
        val b = NamedLlmProvider("b")
        val selector = DefaultAiProviderSelector(
            listOf(
                RegisteredProvider(info("a"), a),
                RegisteredProvider(info("b"), b),
            ),
        )

        val result = selector.selectProvider(task, ProviderPreferences(preferredProviderId = "b"))

        assertSame(b, result)
    }

    @Test
    fun `an ineligible or unknown preferredProviderId falls back to normal local-first ranking`() {
        val cloud = NamedLlmProvider("cloud")
        val local = NamedLlmProvider("local")
        val selector = DefaultAiProviderSelector(
            listOf(
                RegisteredProvider(info("cloud", type = ProviderType.CLOUD), cloud),
                RegisteredProvider(info("local", type = ProviderType.LOCAL), local),
            ),
        )

        val result = selector.selectProvider(task, ProviderPreferences(preferredProviderId = "does-not-exist"))

        assertSame(local, result)
    }

    @Test
    fun `ties within one ProviderType are broken by registration order`() {
        val first = NamedLlmProvider("first")
        val second = NamedLlmProvider("second")
        val selector = DefaultAiProviderSelector(
            listOf(
                RegisteredProvider(info("first"), first),
                RegisteredProvider(info("second"), second),
            ),
        )

        assertSame(first, selector.selectProvider(task))
    }

    @Test
    fun `listProviders returns the declared info unchanged, in registration order`() {
        val infoA = info("a")
        val infoB = info("b", type = ProviderType.CLOUD)
        val selector = DefaultAiProviderSelector(
            listOf(
                RegisteredProvider(infoA, NamedLlmProvider("a")),
                RegisteredProvider(infoB, NamedLlmProvider("b")),
            ),
        )

        assertEquals(listOf(infoA, infoB), selector.listProviders())
    }
}
