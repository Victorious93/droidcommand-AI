package ai.droidcommand.llm.factory

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.AgentState
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.DroidCommandSession
import ai.droidcommand.agent.Planner
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.config.ConfigKeys
import ai.droidcommand.config.MapConfigSource
import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import kotlin.test.Test
import kotlin.test.assertIs

private class EchoTool : Tool {
    override val spec = ToolSpec(name = "echo", description = "echoes its input")
    override fun execute(input: Map<String, String>) = ToolResult.Success(input["text"] ?: "")
}

/**
 * Scripted at the HTTP layer, not the [ai.droidcommand.llm.LlmProvider]
 * layer: every response is a real Anthropic Messages API JSON body, so the
 * real [ai.droidcommand.llm.anthropic.AnthropicLlmProvider] constructed by
 * [LlmProviderFactory] does its own real request encoding/response parsing
 * — the only thing not real here is the socket itself, matching this
 * module's own established no-real-network precedent.
 */
private class ScriptedHttpTransport(private val responses: MutableList<Pair<Int, String>>) : HttpTransport {
    override fun send(request: HttpRequestSpec): HttpResponseSpec {
        check(responses.isNotEmpty()) { "ScriptedHttpTransport ran out of scripted responses" }
        val (status, body) = responses.removeAt(0)
        return HttpResponseSpec(status, emptyMap(), body)
    }
}

private fun anthropicSource() = MapConfigSource(
    mapOf(
        ConfigKeys.LLM_PROVIDER_IDS to "anthropic-primary",
        "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_PROVIDER" to "anthropic",
        "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_MODEL" to "claude-test-model",
        "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_TYPE" to "CLOUD",
        "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_MAX_CONTEXT_TOKENS" to "200000",
        "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_ENDPOINT" to "https://example.invalid",
        "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_API_KEY" to "sk-test-key",
    ),
)

private fun newForgeSession(transport: HttpTransport): Pair<DroidCommandSession, Planner> {
    val registry = ToolRegistry().apply { register(EchoTool()) }
    val stateMachine = AgentStateMachine()
    val executor = ToolExecutor(registry, stateMachine, sleep = { })
    val session = DroidCommandSession(registry, executor, stateMachine)
    session.switchMode(AgentMode.FORGE)
    val planner = LlmProviderFactory.createPlanner(anthropicSource(), transport)
    return session to planner
}

/**
 * Proves the "wire LlmProviderFactory into ObjectiveEngine/DroidCommandSession"
 * gap named in `docs/AUDIT_2026-09-05.md`'s prior addenda is actually closed:
 * a real [DroidCommandSession.runForgeObjective] call, given a [ai.droidcommand.agent.Planner]
 * built entirely from a [ai.droidcommand.config.ConfigSource] via
 * [LlmProviderFactory.createPlanner], drives a real Forge Mode loop —
 * `core-agent` never references `core-llm`/`core-config`/`core-remote`/
 * `core-llm-anthropic` directly; every one of those is reached only through
 * the [ai.droidcommand.agent.Planner] seam `core-agent` already exposed for
 * exactly this purpose.
 */
class LlmProviderFactoryPlannerIntegrationTest {
    @Test
    fun `a config-driven planner drives a full Forge objective through DroidCommandSession`() {
        val transport = ScriptedHttpTransport(
            mutableListOf(
                200 to """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"echo","input":{"text":"hello"}}],"model":"claude-test-model","stop_reason":"tool_use"}""",
                200 to """{"id":"msg_2","type":"message","role":"assistant","content":[{"type":"text","text":"Objective complete: echoed 'hello'"}],"model":"claude-test-model","stop_reason":"end_turn"}""",
            ),
        )
        val (session, planner) = newForgeSession(transport)

        val outcome = session.runForgeObjective("Echo the word hello", planner)

        assertIs<AgentState.Completed>(outcome.finalState)
    }

    @Test
    fun `a real provider error surfaces as a Failed objective, not a crash`() {
        val transport = ScriptedHttpTransport(mutableListOf(401 to """{"error":{"message":"invalid x-api-key"}}"""))
        val (session, planner) = newForgeSession(transport)

        val outcome = session.runForgeObjective("Do something", planner)

        assertIs<AgentState.Failed>(outcome.finalState)
    }
}
