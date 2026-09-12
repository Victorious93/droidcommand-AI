package ai.droidcommand.cli

import ai.droidcommand.config.ConfigKeys
import ai.droidcommand.config.MapConfigSource
import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import kotlin.test.Test
import kotlin.test.assertEquals

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

class MainTest {
    @Test
    fun `buildSession registers the echo tool`() {
        val cliSession = buildSession()
        val specs = cliSession.registry.list()
        assertEquals(listOf("echo"), specs.map { it.name })
    }

    @Test
    fun `pilot with no arguments fails with a usage message, not a crash`() {
        val exitCode = runPilot(buildSession(), emptyList())
        assertEquals(1, exitCode)
    }

    @Test
    fun `pilot echo returns success and exit code 0`() {
        val exitCode = runPilot(buildSession(), listOf("echo", "text=hello"))
        assertEquals(0, exitCode)
    }

    @Test
    fun `pilot with an invalid key=value pair fails cleanly`() {
        val exitCode = runPilot(buildSession(), listOf("echo", "not-a-pair"))
        assertEquals(1, exitCode)
    }

    @Test
    fun `pilot against an unregistered tool fails cleanly rather than throwing`() {
        val exitCode = runPilot(buildSession(), listOf("does-not-exist"))
        assertEquals(1, exitCode)
    }

    @Test
    fun `forge with no objective fails with a usage message`() {
        val exitCode = runForge(buildSession(), emptyList())
        assertEquals(1, exitCode)
    }

    @Test
    fun `forge without any configured LLM provider fails cleanly, not with a stack trace`() {
        val exitCode = runForge(
            buildSession(),
            listOf("do", "something"),
            configSource = MapConfigSource(emptyMap()),
            transport = ScriptedHttpTransport(mutableListOf()),
        )
        assertEquals(1, exitCode)
    }

    @Test
    fun `forge drives a real DroidCommandSession Forge loop end to end via a config-built planner`() {
        val transport = ScriptedHttpTransport(
            mutableListOf(
                200 to """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"echo","input":{"text":"hello"}}],"model":"claude-test-model","stop_reason":"tool_use"}""",
                200 to """{"id":"msg_2","type":"message","role":"assistant","content":[{"type":"text","text":"Objective complete: echoed 'hello'"}],"model":"claude-test-model","stop_reason":"end_turn"}""",
            ),
        )

        val exitCode = runForge(
            buildSession(),
            listOf("Echo", "the", "word", "hello"),
            configSource = anthropicSource(),
            transport = transport,
        )

        assertEquals(0, exitCode)
    }

    @Test
    fun `forge surfaces a real provider error as a failed run, not a crash`() {
        val transport = ScriptedHttpTransport(mutableListOf(401 to """{"error":{"message":"invalid x-api-key"}}"""))

        val exitCode = runForge(
            buildSession(),
            listOf("do", "something"),
            configSource = anthropicSource(),
            transport = transport,
        )

        assertEquals(1, exitCode)
    }
}
