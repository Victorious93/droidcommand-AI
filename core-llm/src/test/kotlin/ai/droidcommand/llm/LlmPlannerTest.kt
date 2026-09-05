package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class FakeLlmProvider(private val response: LlmResponse) : LlmProvider {
    override val config = LlmConfig(provider = "fake", model = "fake-1")
    var lastRequest: LlmRequest? = null

    override fun complete(request: LlmRequest): LlmResponse {
        lastRequest = request
        return response
    }
}

class LlmPlannerTest {
    @Test
    fun `translates a tool-call response into InvokeTool`() {
        val provider = FakeLlmProvider(LlmResponse.ToolCall("read_file", mapOf("path" to "a.txt")))
        val decision = LlmPlanner(provider).decide("objective", ConversationContext(), emptyList(), null)

        val invoke = assertIs<ai.droidcommand.agent.PlannerDecision.InvokeTool>(decision)
        assertEquals("read_file", invoke.toolName)
        assertEquals(mapOf("path" to "a.txt"), invoke.input)
    }

    @Test
    fun `translates a text response into Complete`() {
        val provider = FakeLlmProvider(LlmResponse.Text("all done"))
        val decision = LlmPlanner(provider).decide("objective", ConversationContext(), emptyList(), null)

        val complete = assertIs<ai.droidcommand.agent.PlannerDecision.Complete>(decision)
        assertEquals("all done", complete.summary)
    }

    @Test
    fun `translates a provider error into Abort instead of throwing`() {
        val provider = FakeLlmProvider(LlmResponse.Error(LlmError.Authentication("invalid API key")))
        val decision = LlmPlanner(provider).decide("objective", ConversationContext(), emptyList(), null)

        val abort = assertIs<ai.droidcommand.agent.PlannerDecision.Abort>(decision)
        assertEquals("invalid API key", abort.reason)
    }

    @Test
    fun `forwards the conversation and system prompt into the request`() {
        val provider = FakeLlmProvider(LlmResponse.Text("done"))
        val context = ConversationContext(systemPrompt = "You are DroidCommand AI").apply {
            append(Role.USER, "do the thing")
        }

        LlmPlanner(provider).decide("objective", context, emptyList(), null)

        val request = provider.lastRequest!!
        assertEquals("You are DroidCommand AI", request.systemPrompt)
        assertEquals(listOf(Role.USER to "do the thing"), request.messages.map { it.role to it.content })
    }
}
