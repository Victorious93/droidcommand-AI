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
import ai.droidcommand.llm.local.BackendKind
import ai.droidcommand.llm.local.GenerationRequest
import ai.droidcommand.llm.local.InferenceBackend
import ai.droidcommand.llm.local.LocalToolProtocol
import ai.droidcommand.llm.local.ModelMetadata
import ai.droidcommand.llm.local.ModelRepository
import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A helper bot's tools on an on-device model through [ChatSession.sendLocal]. FAKE backend (no llama.cpp): it replays
 * scripted text, so this covers the session's handling, not what a real model produces. No network is allowed.
 */
class ChatSessionLocalToolsTest {
    private class ScriptedBackend(private val replies: MutableList<String>, override val supportsGrammar: Boolean = true) : InferenceBackend {
        override val kind = BackendKind.CPU
        val seen = mutableListOf<GenerationRequest>()

        override fun load(modelPath: String, contextTokens: Int) = Unit

        override fun generate(request: GenerationRequest, onToken: (String) -> Boolean) {
            seen += request
            for (piece in replies.removeAt(0).chunked(7)) if (!onToken(piece)) return
        }

        override fun unload() = Unit
    }

    private class Runner(private val result: ToolResult = ToolResult.Success("12:00")) : ToolRunner {
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
            return result
        }
    }

    private val clock = ToolSpec(name = "clock", description = "Tells the time")
    private val dir: File = Files.createTempDirectory("chat-local-tools").toFile().also { it.deleteOnExit() }

    /** A GGUF header and key/value section with the given chat template: what the metadata reader needs, not a loadable model. */
    private fun gguf(template: String): ByteArray {
        val out = ByteArrayOutputStream()

        fun le(n: Int, v: Long) = repeat(n) { out.write(((v ushr (8 * it)) and 0xFF).toInt()) }

        fun str(s: String) {
            val b = s.toByteArray()
            le(8, b.size.toLong())
            out.write(b)
        }

        fun entry(key: String, value: String) {
            str(key)
            le(4, 8)
            str(value)
        }
        out.write("GGUF".toByteArray())
        le(4, 3)
        le(8, 1)
        le(8, 3)
        entry("general.architecture", "qwen2")
        entry("tokenizer.ggml.model", "gpt2")
        entry("tokenizer.chat_template", template)
        return out.toByteArray()
    }

    private fun session(backend: InferenceBackend, template: String = "{% if tools %}<tools></tools><tool_call>{% endif %}"): ChatSession {
        val bytes = gguf(template)
        File(dir, "m.gguf").writeBytes(bytes)
        val repo = ModelRepository(dir)
        repo.register(ModelMetadata("qwen", "Qwen", "m.gguf", ModelRepository.sha256Hex(bytes.inputStream()), 4096))
        val noNetwork = object : HttpTransport {
            override fun send(request: HttpRequestSpec): HttpResponseSpec = error("an on-device turn used the network: ${request.url}")
        }
        return ChatSession(InMemorySecretsVault(), InMemoryConversationStore(), transport = noNetwork, local = LocalProviderResources(repo) { backend })
    }

    @Test
    fun `a compatible model's tool call runs through the runner and the model answers from the result`() {
        val backend = ScriptedBackend(mutableListOf("<tool_call>\n{\"name\": \"clock\", \"arguments\": {\"zone\": \"UTC\"}}\n</tool_call>", "It is 12:00 UTC."))
        val chat = session(backend)
        val runner = Runner()
        chat.setToolAccess(runner, listOf(clock))
        assertTrue(chat.localToolSupport("qwen")!!.supported)

        val deltas = StringBuilder()
        val reply = assertIs<ChatResult.Reply>(chat.sendLocal("qwen", "what time is it?") { deltas.append(it) })

        assertEquals("It is 12:00 UTC.", reply.text)
        assertNull(reply.notice)
        assertEquals("It is 12:00 UTC.", deltas.toString(), "the call itself is never shown as reply text")
        assertEquals(listOf("clock" to mapOf("zone" to "UTC")), runner.calls)
        assertEquals(2, backend.seen.size)
        assertNotNull(backend.seen[0].grammar)
        val second = backend.seen[1].messages
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), second.map { it.role })
        assertTrue(second[1].content.contains("<tool_call>") && second[1].content.contains("\"clock\""), second[1].content)
        assertEquals("<tool_response>\n12:00\n</tool_response>", second[2].content)
        assertEquals(listOf(Role.USER to "what time is it?", Role.ASSISTANT to "It is 12:00 UTC."), chat.history().map { it.role to it.content })
    }

    @Test
    fun `an invalid call from the model runs nothing and saves nothing`() {
        val backend = ScriptedBackend(mutableListOf("<tool_call>\n{\"name\": \"factory_reset\", \"arguments\": {}}\n</tool_call>"))
        val chat = session(backend)
        val runner = Runner()
        chat.setToolAccess(runner, listOf(clock))

        val failure = assertIs<ChatResult.Failure>(chat.sendLocal("qwen", "reset my phone"))
        assertTrue(failure.message.contains("not offered") && failure.message.endsWith("Nothing was run."), failure.message)
        assertTrue(runner.calls.isEmpty())
        assertTrue(chat.history().isEmpty())
    }

    @Test
    fun `a model or runtime that cannot call tools answers without them and says exactly why`() {
        val plainTemplate = session(ScriptedBackend(mutableListOf("plain answer")), template = "{{ messages }}")
        val runner = Runner()
        plainTemplate.setToolAccess(runner, listOf(clock))
        val first = assertIs<ChatResult.Reply>(plainTemplate.sendLocal("qwen", "hi"))
        assertEquals("plain answer", first.text)
        assertEquals(LocalToolProtocol.NO_TEMPLATE_SUPPORT, first.notice)
        assertEquals(LocalToolProtocol.NO_TEMPLATE_SUPPORT, plainTemplate.localToolSupport("qwen")!!.reason)

        val backend = ScriptedBackend(mutableListOf("plain answer"), supportsGrammar = false)
        val noGrammar = session(backend)
        noGrammar.setToolAccess(runner, listOf(clock))
        assertEquals(LocalToolProtocol.NO_GRAMMAR, assertIs<ChatResult.Reply>(noGrammar.sendLocal("qwen", "hi")).notice)
        assertNull(backend.seen.single().grammar)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `plain conversation with a tool-capable model and no bot is unchanged`() {
        val backend = ScriptedBackend(mutableListOf("hello there"))
        val reply = assertIs<ChatResult.Reply>(session(backend).sendLocal("qwen", "hi"))
        assertEquals("hello there", reply.text)
        assertNull(reply.notice)
        assertNull(backend.seen.single().grammar)
        assertEquals(null, backend.seen.single().systemPrompt)
    }
}
