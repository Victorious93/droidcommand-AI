package ai.droidforge.llm

import ai.droidforge.agent.AgentState
import ai.droidforge.agent.AgentStateMachine
import ai.droidforge.agent.ObjectiveEngine
import ai.droidforge.agent.Role
import ai.droidforge.agent.Tool
import ai.droidforge.agent.ToolExecutor
import ai.droidforge.agent.ToolRegistry
import ai.droidforge.agent.ToolResult
import ai.droidforge.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class EchoTool : Tool {
    override val spec = ToolSpec(name = "echo", description = "echoes its input")
    override fun execute(input: Map<String, String>) = ToolResult.Success(input["text"] ?: "")
}

private class ScriptedLlmProvider(private val responses: MutableList<LlmResponse>) : LlmProvider {
    override val config = LlmConfig(provider = "scripted", model = "scripted-1")
    val requests = mutableListOf<LlmRequest>()

    override fun complete(request: LlmRequest): LlmResponse {
        requests += request
        check(responses.isNotEmpty()) { "ScriptedLlmProvider ran out of scripted responses" }
        return responses.removeAt(0)
    }
}

/**
 * Proves the full Forge Mode loop (objective -> planner -> tool -> observation
 * -> planner -> completion) works end to end using core-agent's real
 * ObjectiveEngine/ToolExecutor and this module's real LlmPlanner, driven by a
 * scripted fake provider since no real LLM credentials exist in this
 * environment. This is the honest substitute for a live-model test.
 */
class ObjectiveEngineIntegrationTest {
    @Test
    fun `objective loop calls a tool through an LLM planner then completes`() {
        val registry = ToolRegistry().apply { register(EchoTool()) }
        val stateMachine = AgentStateMachine()
        val executor = ToolExecutor(registry, stateMachine, sleep = { })
        val provider = ScriptedLlmProvider(
            mutableListOf(
                LlmResponse.ToolCall("echo", mapOf("text" to "hello")),
                LlmResponse.Text("Objective complete: echoed 'hello'"),
            ),
        )
        val engine = ObjectiveEngine(registry, executor, stateMachine, LlmPlanner(provider), maxIterations = 5)

        val outcome = engine.run("Echo the word hello")

        assertIs<AgentState.Completed>(outcome.finalState)
        assertEquals(2, outcome.iterations)
        assertEquals(2, provider.requests.size)
        assertTrue(provider.requests[1].messages.any { it.role == Role.TOOL && it.content.contains("hello") })
    }

    @Test
    fun `a provider error aborts the objective instead of crashing`() {
        val registry = ToolRegistry()
        val stateMachine = AgentStateMachine()
        val executor = ToolExecutor(registry, stateMachine, sleep = { })
        val provider = ScriptedLlmProvider(
            mutableListOf(LlmResponse.Error(LlmError.Authentication("invalid API key"))),
        )
        val engine = ObjectiveEngine(registry, executor, stateMachine, LlmPlanner(provider))

        val outcome = engine.run("Do something")

        assertIs<AgentState.Failed>(outcome.finalState)
    }
}
