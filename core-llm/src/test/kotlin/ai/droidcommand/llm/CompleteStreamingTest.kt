package ai.droidcommand.llm

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompleteStreamingTest {
    private val request = LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, "hi")))

    private class PlainProvider(private val response: LlmResponse) : LlmProvider {
        override val config = LlmConfig(provider = "fake", model = "fake-1")

        override fun complete(request: LlmRequest): LlmResponse = response
    }

    private class FakeStreamingProvider : StreamingLlmProvider {
        override val config = LlmConfig(provider = "fake", model = "fake-1")
        var streamed = false

        override fun complete(request: LlmRequest): LlmResponse = error("stream should be preferred")

        override fun stream(request: LlmRequest, onTextDelta: (String) -> Unit): LlmResponse {
            streamed = true
            listOf("a", "b").forEach(onTextDelta)
            return LlmResponse.Text("ab")
        }
    }

    @Test
    fun `a streaming provider streams`() {
        val provider = FakeStreamingProvider()
        val deltas = mutableListOf<String>()

        val response = provider.completeStreaming(request) { deltas += it }

        assertTrue(provider.streamed)
        assertEquals(listOf("a", "b"), deltas)
        assertEquals(LlmResponse.Text("ab"), response)
    }

    @Test
    fun `a non-streaming provider's text arrives as one delta`() {
        val deltas = mutableListOf<String>()

        val response = PlainProvider(LlmResponse.Text("whole")).completeStreaming(request) { deltas += it }

        assertEquals(listOf("whole"), deltas)
        assertEquals(LlmResponse.Text("whole"), response)
    }

    @Test
    fun `a non-streaming tool call sends no delta`() {
        val deltas = mutableListOf<String>()

        PlainProvider(LlmResponse.ToolCall("shell", emptyMap())).completeStreaming(request) { deltas += it }

        assertTrue(deltas.isEmpty())
    }
}
