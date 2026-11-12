package ai.droidcommand.llm.local

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.ToolCallRequest
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * On-device tool calling in [LocalLlmProvider] and [LocalToolProtocol], against FAKE backends: no llama.cpp runs here,
 * so the grammar is handed over but not enforced, which is exactly why the Kotlin-side validation is tested on its own.
 */
class LocalToolCallingTest {
    private class GrammarBackend(private val tokens: List<String>, override val supportsGrammar: Boolean = true) : InferenceBackend {
        override val kind = BackendKind.CPU
        val seen = mutableListOf<GenerationRequest>()

        override fun load(modelPath: String, contextTokens: Int) = Unit

        override fun generate(request: GenerationRequest, onToken: (String) -> Boolean) {
            seen += request
            for (t in tokens) if (!onToken(t)) return
        }

        override fun unload() = Unit
    }

    private val echo = ToolSpec(name = "echo", description = "Repeats text")
    private val clock = ToolSpec(name = "clock", description = "Tells the time")
    private val tools = listOf(echo, clock)
    private val toolTemplate = "{%- if tools %}<tools>{{ tools }}</tools> use <tool_call></tool_call>{% endif %}{{ messages }}"

    private val dir: File = Files.createTempDirectory("local-tools").toFile().also { it.deleteOnExit() }

    private fun provider(backend: InferenceBackend, template: String? = toolTemplate): LocalLlmProvider {
        val bytes = if (template == null) "not a gguf".toByteArray() else ggufModelBytes(chatTemplate = false) { string("tokenizer.chat_template", template) }
        File(dir, "m.gguf").writeBytes(bytes)
        val repo = ModelRepository(dir)
        repo.register(ModelMetadata("m", "M", "m.gguf", ModelRepository.sha256Hex(bytes.inputStream()), 4096))
        return LocalLlmProvider("m", repo, backend)
    }

    private fun request(vararg messages: Message = arrayOf(Message(Role.USER, "what time is it?"))) =
        LlmRequest("Be brief.", messages.toList(), tools = tools)

    @Test
    fun `tool support needs a grammar-capable backend and a template that defines tool calls`() {
        assertTrue(provider(GrammarBackend(emptyList())).toolSupport().supported)

        val noGrammar = provider(GrammarBackend(emptyList(), supportsGrammar = false)).toolSupport()
        assertFalse(noGrammar.supported)
        assertEquals(LocalToolProtocol.NO_GRAMMAR, noGrammar.reason)

        val plainTemplate = provider(GrammarBackend(emptyList()), template = "{{ messages }}").toolSupport()
        assertFalse(plainTemplate.supported)
        assertEquals(LocalToolProtocol.NO_TEMPLATE_SUPPORT, plainTemplate.reason)

        val unreadable = provider(GrammarBackend(emptyList()), template = null).toolSupport()
        assertFalse(unreadable.supported)
        assertEquals(LocalToolProtocol.UNREADABLE, unreadable.reason)
    }

    @Test
    fun `an unsupported combination still refuses a request with tools, and plain chat still works`() {
        val backend = GrammarBackend(listOf("Hel", "lo"), supportsGrammar = false)
        val provider = provider(backend)
        val refused = assertIs<LlmResponse.Error>(provider.complete(request()))
        assertIs<LlmError.ModelUnavailable>(refused.error)
        assertTrue(backend.seen.isEmpty())

        assertEquals(LlmResponse.Text("Hello"), provider.complete(LlmRequest("sys", listOf(Message(Role.USER, "hi")))))
        assertNull(backend.seen.single().grammar, "no grammar without tools")
    }

    @Test
    fun `a tool call is returned as a call, never streamed as text, and the backend got the grammar and the tool list`() {
        val backend = GrammarBackend(listOf("<tool", "_call>\n{\"name\": \"clo", "ck\", \"arguments\": {\"zone\": \"UTC\", \"n\": 2}}\n</tool_call>"))
        val deltas = mutableListOf<String>()
        val call = assertIs<LlmResponse.ToolCall>(provider(backend).stream(request()) { deltas += it })

        assertEquals("clock", call.toolName)
        assertEquals(mapOf("zone" to "UTC", "n" to "2"), call.input)
        assertEquals("""{"zone":"UTC","n":2}""", call.argumentsJson)
        assertNull(call.id, "the model gives no id; the caller assigns one")
        assertTrue(deltas.isEmpty(), deltas.toString())

        val sent = backend.seen.single()
        val grammar = assertNotNull(sent.grammar)
        assertTrue(grammar.contains("""name ::= "\"echo\"" | "\"clock\"""""), grammar)
        assertTrue(grammar.contains("call{1,${LocalToolProtocol.MAX_CALLS}}"), grammar)
        val system = assertNotNull(sent.systemPrompt)
        assertTrue(system.startsWith("Be brief.\n\n# Tools"), system)
        assertTrue(system.contains(""""name":"clock""""), system)
    }

    @Test
    fun `an ordinary reply streams normally while tools are offered`() {
        val backend = GrammarBackend(listOf(" It", " is", " noon."))
        val deltas = mutableListOf<String>()
        assertEquals(LlmResponse.Text(" It is noon."), provider(backend).stream(request()) { deltas += it })
        assertEquals(" It is noon.", deltas.joinToString(""))

        // A reply that starts like a tag but is not a call is released as soon as that is known.
        val angle = GrammarBackend(listOf("<", "b>bold</b>"))
        val seen = mutableListOf<String>()
        assertEquals(LlmResponse.Text("<b>bold</b>"), provider(angle).stream(request()) { seen += it })
        assertEquals("<b>bold</b>", seen.joinToString(""))
    }

    @Test
    fun `several calls are returned in order and more than the limit is refused`() {
        fun block(name: String) = "<tool_call>\n{\"name\": \"$name\", \"arguments\": {}}\n</tool_call>\n"
        val two = assertIs<LlmResponse.ToolCall>(provider(GrammarBackend(listOf(block("echo"), block("clock")))).complete(request()))
        assertEquals(listOf("echo", "clock"), two.calls.map { it.toolName })

        val many = provider(GrammarBackend(List(LocalToolProtocol.MAX_CALLS + 1) { block("echo") })).complete(request())
        assertTrue(assertIs<LlmResponse.Error>(many).error.message.contains("more than ${LocalToolProtocol.MAX_CALLS}"))
    }

    @Test
    fun `a call that is not valid is an error and nothing can be run from it`() {
        val cases = mapOf(
            "<tool_call>{\"name\": \"rm_rf\", \"arguments\": {}}</tool_call>" to "not offered",
            "<tool_call>{\"name\": \"echo\", \"arguments\": \"text\"}</tool_call>" to "were not a JSON object",
            "<tool_call>{\"name\": \"echo\", \"arguments\": {\"text\": }}</tool_call>" to "was not a JSON object",
            "<tool_call>{\"arguments\": {}}</tool_call>" to "no name",
            "<tool_call>{\"name\": \"echo\", \"arguments\": {\"text\": \"cut" to "cut off",
            "<tool_call>{\"name\": \"echo\", \"arguments\": {}}</tool_call> and then some text" to "text followed",
        )
        for ((output, expected) in cases) {
            val error = assertIs<LlmResponse.Error>(provider(GrammarBackend(listOf(output))).complete(request()), output).error
            assertIs<LlmError.InvalidResponse>(error)
            assertTrue(error.message.contains(expected), "$output -> ${error.message}")
            assertTrue(error.message.endsWith("Nothing was run."), error.message)
        }
    }

    @Test
    fun `earlier calls and their results reach the backend as plain turns the model's template can render`() {
        val backend = GrammarBackend(listOf("It is noon."))
        val history = arrayOf(
            Message(Role.USER, "what time is it?"),
            Message(Role.ASSISTANT, "", toolCalls = listOf(ToolCallRequest("c1", "clock", """{"zone":"UTC"}"""), ToolCallRequest("c2", "echo", "{}"))),
            Message(Role.TOOL, "12:00", toolCallId = "c1"),
            Message(Role.TOOL, "ERROR: nothing to repeat", toolCallId = "c2"),
        )
        assertEquals(LlmResponse.Text("It is noon."), provider(backend).complete(request(*history)))

        val turns = backend.seen.single().messages
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), turns.map { it.role })
        assertEquals("<tool_call>\n{\"name\": \"clock\", \"arguments\": {\"zone\":\"UTC\"}}\n</tool_call>\n<tool_call>\n{\"name\": \"echo\", \"arguments\": {}}\n</tool_call>", turns[1].content)
        assertEquals("<tool_response>\n12:00\n</tool_response>\n<tool_response>\nERROR: nothing to repeat\n</tool_response>", turns[2].content)
        assertTrue(turns.all { it.toolCalls.isEmpty() && it.toolCallId == null })
    }

    @Test
    fun `a tool name that cannot be written into the grammar refuses the request`() {
        val odd = ToolSpec(name = "bad\"name", description = "d")
        val backend = GrammarBackend(listOf("x"))
        val error = assertIs<LlmResponse.Error>(provider(backend).complete(LlmRequest(null, listOf(Message(Role.USER, "hi")), tools = listOf(odd)))).error
        assertTrue(error.message.contains("bad\"name"), error.message)
        assertTrue(backend.seen.isEmpty())
    }

    @Test
    fun `the metadata reader records whether the chat template defines tool calls`() {
        fun read(template: String?) =
            (GgufMetadataReader.read(ggufModelBytes(chatTemplate = false) { template?.let { string("tokenizer.chat_template", it) } }.inputStream()) as GgufMetadataResult.Valid).metadata
        assertTrue(read(toolTemplate).chatTemplateDeclaresToolCalls)
        assertFalse(read("{{ messages }}").chatTemplateDeclaresToolCalls)
        assertFalse(read(null).chatTemplateDeclaresToolCalls)
    }
}
