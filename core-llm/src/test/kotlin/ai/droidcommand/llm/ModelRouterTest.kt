package ai.droidcommand.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private class RoutedLlmProvider(private val response: LlmResponse, name: String = "scripted") : LlmProvider {
    override val config = LlmConfig(provider = name, model = "$name-1")
    var calls = 0
        private set

    override fun complete(request: LlmRequest): LlmResponse {
        calls++
        return response
    }
}

private val request = LlmRequest(systemPrompt = null, messages = emptyList())

class ModelRouterTest {
    @Test
    fun `requires at least one provider`() {
        assertFailsWith<IllegalArgumentException> { ModelRouter(emptyList()) }
    }

    @Test
    fun `a successful first provider is used, and the second is never called`() {
        val first = RoutedLlmProvider(LlmResponse.Text("first answer"), "first")
        val second = RoutedLlmProvider(LlmResponse.Text("second answer"), "second")
        val router = ModelRouter(listOf(first, second))

        val result = router.complete(request)

        assertEquals(LlmResponse.Text("first answer"), result)
        assertEquals(1, first.calls)
        assertEquals(0, second.calls)
    }

    @Test
    fun `falls back to the next provider on a transient failure`() {
        val first = RoutedLlmProvider(LlmResponse.Error(LlmError.ModelUnavailable("overloaded")), "first")
        val second = RoutedLlmProvider(LlmResponse.Text("fallback answer"), "second")
        val router = ModelRouter(listOf(first, second))

        val result = router.complete(request)

        assertEquals(LlmResponse.Text("fallback answer"), result)
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
    }

    @Test
    fun `does not fall back on an authentication failure`() {
        val first = RoutedLlmProvider(LlmResponse.Error(LlmError.Authentication("bad key")), "first")
        val second = RoutedLlmProvider(LlmResponse.Text("should never be seen"), "second")
        val router = ModelRouter(listOf(first, second))

        val result = router.complete(request)

        val error = assertIs<LlmResponse.Error>(result)
        assertIs<LlmError.Authentication>(error.error)
        assertEquals(0, second.calls)
    }

    @Test
    fun `does not fall back on an invalid-response failure`() {
        val first = RoutedLlmProvider(LlmResponse.Error(LlmError.InvalidResponse("malformed json")), "first")
        val second = RoutedLlmProvider(LlmResponse.Text("should never be seen"), "second")
        val router = ModelRouter(listOf(first, second))

        router.complete(request)

        assertEquals(0, second.calls)
    }

    @Test
    fun `returns the last provider's error once every provider has failed transiently`() {
        val first = RoutedLlmProvider(LlmResponse.Error(LlmError.Network("dns failure")), "first")
        val second = RoutedLlmProvider(LlmResponse.Error(LlmError.Timeout("deadline exceeded")), "second")
        val router = ModelRouter(listOf(first, second))

        val result = router.complete(request)

        val error = assertIs<LlmResponse.Error>(result)
        assertIs<LlmError.Timeout>(error.error)
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
    }

    @Test
    fun `exposes the first provider's config as its own`() {
        val first = RoutedLlmProvider(LlmResponse.Text("x"), "primary")
        val second = RoutedLlmProvider(LlmResponse.Text("y"), "secondary")
        val router = ModelRouter(listOf(first, second))

        assertEquals("primary", router.config.provider)
    }
}
