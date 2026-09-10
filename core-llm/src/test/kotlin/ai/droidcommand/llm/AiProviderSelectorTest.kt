package ai.droidcommand.llm

import ai.droidcommand.agent.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class FakeSelectableLlmProvider(name: String, authToken: () -> String? = { null }) : LlmProvider {
    override val config = LlmConfig(provider = name, model = "$name-1", authToken = authToken)

    override fun complete(request: LlmRequest): LlmResponse = LlmResponse.Text(config.provider)

    override fun toString(): String = config.provider
}

private fun registered(
    id: String,
    type: ProviderType,
    maxContextTokens: Int = 16000,
    available: Boolean = true,
    cost: Cost? = null,
    provider: LlmProvider = FakeSelectableLlmProvider(id),
): RegisteredAiProvider =
    RegisteredAiProvider(
        AiProviderInfo(id, id, type, maxContextTokens, available, cost, setOf(ProviderCapability.TEXT_COMPLETION)),
        provider,
    )

private val simpleTask = Task("t1", "Fix a typo") // LIGHTWEIGHT budget (1000 tokens)

class AiProviderSelectorTest {
    @Test
    fun `listProviders returns every registered descriptor unchanged`() {
        val a = registered("a", ProviderType.LOCAL)
        val b = registered("b", ProviderType.CLOUD)
        val selector = DefaultAiProviderSelector(listOf(a, b))

        assertEquals(listOf(a.info, b.info), selector.listProviders())
    }

    @Test
    fun `an unavailable provider is never selected even if otherwise the best match`() {
        val unavailable = registered("best", ProviderType.LOCAL, available = false)
        val fallback = registered("fallback", ProviderType.CLOUD)
        val selector = DefaultAiProviderSelector(listOf(unavailable, fallback))

        val selected = selector.selectProvider(simpleTask)

        assertSame(fallback.provider, selected)
    }

    @Test
    fun `requireLocal filters out CLOUD providers`() {
        val local = registered("local", ProviderType.LOCAL)
        val cloud = registered("cloud", ProviderType.CLOUD)
        val selector = DefaultAiProviderSelector(listOf(cloud, local))

        val selected = selector.selectProvider(simpleTask, ProviderPreferences(requireLocal = true))

        assertSame(local.provider, selected)
    }

    @Test
    fun `requireLocal excludes every provider when only CLOUD ones exist`() {
        val cloud = registered("cloud", ProviderType.CLOUD)
        val selector = DefaultAiProviderSelector(listOf(cloud))

        assertNull(selector.selectProvider(simpleTask, ProviderPreferences(requireLocal = true)))
    }

    @Test
    fun `context-length filtering excludes a provider whose maxContextTokens is below the task's real budget`() {
        // "x".repeat(3000) selects a FULL (16000-token) budget per DefaultTokenBudgetManager's real heuristic.
        val complexTask = Task("t2", "x".repeat(3000))
        val tooSmall = registered("small", ProviderType.LOCAL, maxContextTokens = 8000)
        val bigEnough = registered("big", ProviderType.CLOUD, maxContextTokens = 16000)
        val selector = DefaultAiProviderSelector(listOf(tooSmall, bigEnough))

        val selected = selector.selectProvider(complexTask)

        assertSame(bigEnough.provider, selected)
    }

    @Test
    fun `preferredProviderId wins when the preferred provider survives filtering`() {
        val cheaper = registered("cheaper", ProviderType.LOCAL, cost = Cost(perInputToken = 0.0001))
        val preferred = registered("preferred", ProviderType.CLOUD, cost = Cost(perInputToken = 0.01))
        val selector = DefaultAiProviderSelector(listOf(cheaper, preferred))

        val selected = selector.selectProvider(simpleTask, ProviderPreferences(preferredProviderId = "preferred"))

        assertSame(preferred.provider, selected)
    }

    @Test
    fun `an unknown preferredProviderId falls through to normal ranking rather than erroring`() {
        val local = registered("local", ProviderType.LOCAL)
        val cloud = registered("cloud", ProviderType.CLOUD)
        val selector = DefaultAiProviderSelector(listOf(cloud, local))

        val selected = selector.selectProvider(simpleTask, ProviderPreferences(preferredProviderId = "does-not-exist"))

        assertSame(local.provider, selected)
    }

    @Test
    fun `a preferredProviderId that was filtered out (unavailable) falls through to normal ranking`() {
        val filteredOut = registered("preferred", ProviderType.LOCAL, available = false)
        val fallback = registered("fallback", ProviderType.CLOUD)
        val selector = DefaultAiProviderSelector(listOf(filteredOut, fallback))

        val selected = selector.selectProvider(simpleTask, ProviderPreferences(preferredProviderId = "preferred"))

        assertSame(fallback.provider, selected)
    }

    @Test
    fun `local providers are ranked before cloud providers when no preference is given`() {
        val cloud = registered("cloud", ProviderType.CLOUD)
        val local = registered("local", ProviderType.LOCAL)
        val selfHosted = registered("self-hosted", ProviderType.SELF_HOSTED)
        val selector = DefaultAiProviderSelector(listOf(cloud, local, selfHosted))

        val selected = selector.selectProvider(simpleTask)

        assertTrue(selected === local.provider || selected === selfHosted.provider)
    }

    @Test
    fun `among same-locality candidates, ascending cost wins`() {
        val expensive = registered("expensive", ProviderType.LOCAL, cost = Cost(perInputToken = 0.01))
        val cheap = registered("cheap", ProviderType.LOCAL, cost = Cost(perInputToken = 0.0001))
        val selector = DefaultAiProviderSelector(listOf(expensive, cheap))

        val selected = selector.selectProvider(simpleTask)

        assertSame(cheap.provider, selected)
    }

    @Test
    fun `a null cost ranks last, never assumed cheap`() {
        val unknownCost = registered("unknown", ProviderType.LOCAL, cost = null)
        val knownCost = registered("known", ProviderType.LOCAL, cost = Cost(perInputToken = 100.0))
        val selector = DefaultAiProviderSelector(listOf(unknownCost, knownCost))

        val selected = selector.selectProvider(simpleTask)

        assertSame(knownCost.provider, selected)
    }

    @Test
    fun `null is returned when every candidate is filtered out`() {
        val selector = DefaultAiProviderSelector(listOf(registered("only", ProviderType.LOCAL, available = false)))
        assertNull(selector.selectProvider(simpleTask))
    }

    @Test
    fun `ProviderType toLocality maps all three values correctly`() {
        assertEquals(Locality.LOCAL, ProviderType.LOCAL.toLocality())
        assertEquals(Locality.LOCAL, ProviderType.SELF_HOSTED.toLocality())
        assertEquals(Locality.REMOTE, ProviderType.CLOUD.toLocality())
    }

    @Test
    fun `hasCredential reflects whether authToken returns a value`() {
        val withCredential = FakeSelectableLlmProvider("with", authToken = { "secret" })
        val withoutCredential = FakeSelectableLlmProvider("without", authToken = { null })

        assertTrue(withCredential.hasCredential())
        assertFalse(withoutCredential.hasCredential())
    }

    @Test
    fun `selectProvider is deterministic across repeated calls with identical input`() {
        val a = registered("a", ProviderType.LOCAL)
        val b = registered("b", ProviderType.CLOUD)
        val selector = DefaultAiProviderSelector(listOf(a, b))

        assertSame(selector.selectProvider(simpleTask), selector.selectProvider(simpleTask))
    }
}
