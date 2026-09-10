package ai.droidcommand.llm

import kotlin.test.Test
import kotlin.test.assertEquals

private class NamedLlmProvider(name: String) : LlmProvider {
    override val config = LlmConfig(provider = name, model = "$name-1")

    override fun complete(request: LlmRequest): LlmResponse = LlmResponse.Text(config.provider)

    override fun toString(): String = config.provider
}

class LocalFirstProviderOrderingTest {
    @Test
    fun `locals are sorted before remotes`() {
        val local = NamedLlmProvider("local")
        val remote = NamedLlmProvider("remote")

        val ordered = localFirstOrder(
            listOf(
                LocalityAwareProvider(remote, Locality.REMOTE),
                LocalityAwareProvider(local, Locality.LOCAL),
            ),
        )

        assertEquals(listOf(local, remote), ordered)
    }

    @Test
    fun `relative order is preserved within each locality group`() {
        val local1 = NamedLlmProvider("local1")
        val local2 = NamedLlmProvider("local2")
        val remote1 = NamedLlmProvider("remote1")
        val remote2 = NamedLlmProvider("remote2")

        val ordered = localFirstOrder(
            listOf(
                LocalityAwareProvider(remote1, Locality.REMOTE),
                LocalityAwareProvider(local1, Locality.LOCAL),
                LocalityAwareProvider(remote2, Locality.REMOTE),
                LocalityAwareProvider(local2, Locality.LOCAL),
            ),
        )

        assertEquals(listOf(local1, local2, remote1, remote2), ordered)
    }

    @Test
    fun `an all-local input is a no-op reordering`() {
        val local1 = NamedLlmProvider("local1")
        val local2 = NamedLlmProvider("local2")

        val ordered = localFirstOrder(
            listOf(LocalityAwareProvider(local1, Locality.LOCAL), LocalityAwareProvider(local2, Locality.LOCAL)),
        )

        assertEquals(listOf(local1, local2), ordered)
    }

    @Test
    fun `an all-remote input is a no-op reordering`() {
        val remote1 = NamedLlmProvider("remote1")
        val remote2 = NamedLlmProvider("remote2")

        val ordered = localFirstOrder(
            listOf(LocalityAwareProvider(remote1, Locality.REMOTE), LocalityAwareProvider(remote2, Locality.REMOTE)),
        )

        assertEquals(listOf(remote1, remote2), ordered)
    }

    @Test
    fun `an empty input returns empty`() {
        assertEquals(emptyList(), localFirstOrder(emptyList()))
    }

    @Test
    fun `the result composes directly with ModelRouter, trying the local provider first`() {
        val remote = NamedLlmProvider("remote")
        val local = NamedLlmProvider("local")

        val router = ModelRouter(
            localFirstOrder(
                listOf(
                    LocalityAwareProvider(remote, Locality.REMOTE),
                    LocalityAwareProvider(local, Locality.LOCAL),
                ),
            ),
        )

        val result = router.complete(LlmRequest(systemPrompt = null, messages = emptyList()))

        assertEquals(LlmResponse.Text("local"), result)
    }
}
