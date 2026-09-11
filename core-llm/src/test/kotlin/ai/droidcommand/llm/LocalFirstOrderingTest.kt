package ai.droidcommand.llm

import kotlin.test.Test
import kotlin.test.assertEquals

private class LabeledLlmProvider(private val response: LlmResponse, name: String) : LlmProvider {
    override val config = LlmConfig(provider = name, model = "$name-1")
    var calls = 0
        private set

    override fun complete(request: LlmRequest): LlmResponse {
        calls++
        return response
    }
}

private val request = LlmRequest(systemPrompt = null, messages = emptyList())

private fun provider(name: String, response: LlmResponse = LlmResponse.Text(name)) =
    LabeledLlmProvider(response, name)

private fun registered(type: ProviderType, provider: LabeledLlmProvider, id: String = provider.config.provider) =
    RegisteredProvider(AiProviderInfo(id = id, name = id, type = type, maxContextTokens = 8000), provider)

class LocalFirstOrderingTest {
    @Test
    fun `all-local input stays in original relative order`() {
        val a = provider("local-a")
        val b = provider("local-b")

        val ordered = LocalFirstOrdering.order(
            listOf(
                registered(ProviderType.LOCAL, a),
                registered(ProviderType.LOCAL, b),
            ),
        )

        assertEquals(listOf(a, b), ordered)
    }

    @Test
    fun `all-cloud input stays in original relative order`() {
        val a = provider("cloud-a")
        val b = provider("cloud-b")

        val ordered = LocalFirstOrdering.order(
            listOf(
                registered(ProviderType.CLOUD, a),
                registered(ProviderType.CLOUD, b),
            ),
        )

        assertEquals(listOf(a, b), ordered)
    }

    @Test
    fun `mixed input puts local before self-hosted before cloud, preserving relative order within each tier`() {
        val cloud1 = provider("cloud-1")
        val local1 = provider("local-1")
        val selfHosted1 = provider("self-hosted-1")
        val cloud2 = provider("cloud-2")
        val local2 = provider("local-2")

        val ordered = LocalFirstOrdering.order(
            listOf(
                registered(ProviderType.CLOUD, cloud1),
                registered(ProviderType.LOCAL, local1),
                registered(ProviderType.SELF_HOSTED, selfHosted1),
                registered(ProviderType.CLOUD, cloud2),
                registered(ProviderType.LOCAL, local2),
            ),
        )

        assertEquals(listOf(local1, local2, selfHosted1, cloud1, cloud2), ordered)
    }

    @Test
    fun `a self-hosted provider sits between local and cloud`() {
        val cloud = provider("cloud")
        val local = provider("local")
        val selfHosted = provider("self-hosted")

        val ordered = LocalFirstOrdering.order(
            listOf(
                registered(ProviderType.CLOUD, cloud),
                registered(ProviderType.SELF_HOSTED, selfHosted),
                registered(ProviderType.LOCAL, local),
            ),
        )

        assertEquals(listOf(local, selfHosted, cloud), ordered)
    }

    @Test
    fun `empty input produces empty output`() {
        assertEquals(emptyList(), LocalFirstOrdering.order(emptyList()))
    }

    @Test
    fun `ordering is deterministic across repeated calls on identical input`() {
        val located = listOf(
            registered(ProviderType.CLOUD, provider("cloud")),
            registered(ProviderType.LOCAL, provider("local")),
        )

        val first = LocalFirstOrdering.order(located)
        val second = LocalFirstOrdering.order(located)

        assertEquals(first, second)
    }

    @Test
    fun `feeding the ordered list into a real ModelRouter tries the local provider first and falls back to cloud`() {
        val local = LabeledLlmProvider(LlmResponse.Error(LlmError.ModelUnavailable("overloaded")), "local")
        val cloud = LabeledLlmProvider(LlmResponse.Text("cloud answer"), "cloud")

        val router = ModelRouter(
            LocalFirstOrdering.order(
                listOf(
                    registered(ProviderType.CLOUD, cloud),
                    registered(ProviderType.LOCAL, local),
                ),
            ),
        )

        val result = router.complete(request)

        assertEquals(LlmResponse.Text("cloud answer"), result)
        assertEquals(1, local.calls)
        assertEquals(1, cloud.calls)
    }
}
