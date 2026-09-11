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

class ProviderLocalityTest {
    @Test
    fun `all-local input stays in original relative order`() {
        val a = provider("local-a")
        val b = provider("local-b")

        val ordered = LocalFirstOrdering.order(
            listOf(
                LocatedProvider(ProviderLocality.LOCAL, a),
                LocatedProvider(ProviderLocality.LOCAL, b),
            ),
        )

        assertEquals(listOf(a, b), ordered)
    }

    @Test
    fun `all-remote input stays in original relative order`() {
        val a = provider("remote-a")
        val b = provider("remote-b")

        val ordered = LocalFirstOrdering.order(
            listOf(
                LocatedProvider(ProviderLocality.REMOTE, a),
                LocatedProvider(ProviderLocality.REMOTE, b),
            ),
        )

        assertEquals(listOf(a, b), ordered)
    }

    @Test
    fun `mixed input puts every local provider before every remote one, preserving relative order within each tier`() {
        val remote1 = provider("remote-1")
        val local1 = provider("local-1")
        val remote2 = provider("remote-2")
        val local2 = provider("local-2")

        val ordered = LocalFirstOrdering.order(
            listOf(
                LocatedProvider(ProviderLocality.REMOTE, remote1),
                LocatedProvider(ProviderLocality.LOCAL, local1),
                LocatedProvider(ProviderLocality.REMOTE, remote2),
                LocatedProvider(ProviderLocality.LOCAL, local2),
            ),
        )

        assertEquals(listOf(local1, local2, remote1, remote2), ordered)
    }

    @Test
    fun `empty input produces empty output`() {
        assertEquals(emptyList(), LocalFirstOrdering.order(emptyList()))
    }

    @Test
    fun `ordering is deterministic across repeated calls on identical input`() {
        val located = listOf(
            LocatedProvider(ProviderLocality.REMOTE, provider("remote")),
            LocatedProvider(ProviderLocality.LOCAL, provider("local")),
        )

        val first = LocalFirstOrdering.order(located)
        val second = LocalFirstOrdering.order(located)

        assertEquals(first, second)
    }

    @Test
    fun `feeding the ordered list into a real ModelRouter tries the local provider first and falls back to remote`() {
        val local = LabeledLlmProvider(LlmResponse.Error(LlmError.ModelUnavailable("overloaded")), "local")
        val remote = LabeledLlmProvider(LlmResponse.Text("remote answer"), "remote")

        val router = ModelRouter(
            LocalFirstOrdering.order(
                listOf(
                    LocatedProvider(ProviderLocality.REMOTE, remote),
                    LocatedProvider(ProviderLocality.LOCAL, local),
                ),
            ),
        )

        val result = router.complete(request)

        assertEquals(LlmResponse.Text("remote answer"), result)
        assertEquals(1, local.calls)
        assertEquals(1, remote.calls)
    }
}
