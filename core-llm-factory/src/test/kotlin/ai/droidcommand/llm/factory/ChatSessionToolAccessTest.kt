package ai.droidcommand.llm.factory

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmProvider
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.remote.JdkHttpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Scripted-provider tests for optional helper-bot tool access in Chat. Never hits a live API. */
class ChatSessionToolAccessTest {
    private val groq = CloudProviderCatalog.byId("groq")!!
    private val key = "gsk-test-key-1234567890"
    private val echo = ToolSpec(name = "echo", description = "Repeats text")

    private fun session(script: ScriptedProvider): ChatSession {
        val vault = InMemorySecretsVault()
        vault.putSecret(groq.secretId, key)
        return ChatSession(
            vault,
            InMemoryConversationStore(),
            transport = JdkHttpTransport(),
            providerFor = { _, config ->
                script.configOverride = config
                script
            },
        )
    }

    @Test
    fun `a tool call without tool access is a failure and is not saved`() {
        val script = ScriptedProvider(listOf { LlmResponse.ToolCall("echo", mapOf("text" to "hi")) })
        val chat = session(script)

        val result = chat.send(groq, "m", "hi")

        val failure = assertIs<ChatResult.Failure>(result)
        assertTrue(failure.message.contains("does not support"), failure.message)
        assertTrue(chat.history().isEmpty())
        assertEquals(1, script.requests.size)
        assertTrue(script.requests[0].tools.isEmpty())
    }

    @Test
    fun `an allowed tool call is run and the final text is saved`() {
        val script = ScriptedProvider(
            listOf(
                { req ->
                    assertEquals(listOf("echo"), req.tools.map { it.name })
                    LlmResponse.ToolCall("echo", mapOf("text" to "hi"))
                },
                { req ->
                    assertTrue(req.messages.any { it.role == Role.TOOL && it.content.contains("pong") }, req.messages.toString())
                    LlmResponse.Text("done")
                },
            ),
        )
        val runner = RecordingRunner(mapOf("echo" to ToolResult.Success("pong")))
        val chat = session(script)
        chat.setToolAccess(runner, listOf(echo))

        val result = chat.send(groq, "m", "please echo")

        assertEquals(ChatResult.Reply("done"), result)
        assertEquals(listOf("echo" to mapOf("text" to "hi")), runner.calls)
        assertEquals(listOf(Role.USER to "please echo", Role.ASSISTANT to "done"), chat.history().map { it.role to it.content })
    }

    @Test
    fun `clearing tool access refuses later tool calls`() {
        val script = ScriptedProvider(
            listOf(
                { LlmResponse.Text("ok") },
                { LlmResponse.ToolCall("echo", emptyMap()) },
            ),
        )
        val chat = session(script)
        chat.setToolAccess(RecordingRunner(), listOf(echo))
        assertIs<ChatResult.Reply>(chat.send(groq, "m", "first"))
        chat.setToolAccess(null)
        val failure = assertIs<ChatResult.Failure>(chat.send(groq, "m", "second"))
        assertTrue(failure.message.contains("does not support"), failure.message)
        assertTrue(script.requests.last().tools.isEmpty())
    }

    @Test
    fun `too many tool-call rounds in one turn fail`() {
        val script = ScriptedProvider(List(10) { { LlmResponse.ToolCall("echo", emptyMap()) } })
        val chat = session(script)
        chat.setToolAccess(RecordingRunner(mapOf("echo" to ToolResult.Success("ok"))), listOf(echo))

        val failure = assertIs<ChatResult.Failure>(chat.send(groq, "m", "loop"))

        assertTrue(failure.message.contains("too many tool calls"), failure.message)
        // Initial complete plus four tool rounds (the fifth ToolCall is refused before another complete).
        assertEquals(5, script.requests.size)
        assertTrue(chat.history().isEmpty())
    }

    private class ScriptedProvider(
        replies: List<(LlmRequest) -> LlmResponse>,
    ) : LlmProvider {
        private val queue = replies.toMutableList()
        val requests = mutableListOf<LlmRequest>()
        var configOverride: LlmConfig? = null
        override val config: LlmConfig
            get() = configOverride ?: LlmConfig(provider = "groq", model = "m")

        override fun complete(request: LlmRequest): LlmResponse {
            requests += request
            check(queue.isNotEmpty()) { "unexpected extra complete()" }
            return queue.removeAt(0).invoke(request)
        }
    }

    private class RecordingRunner(
        private val results: Map<String, ToolResult> = mapOf("echo" to ToolResult.Success("pong")),
    ) : ToolRunner {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        override fun run(
            toolName: String,
            input: Map<String, String>,
            retryPolicy: RetryPolicy,
            isCancelled: () -> Boolean,
            mode: AgentMode?,
            initiator: Initiator?,
            grantId: String?,
        ): ToolResult {
            calls += toolName to input
            return results[toolName] ?: ToolResult.Failure("unknown $toolName")
        }
    }
}
