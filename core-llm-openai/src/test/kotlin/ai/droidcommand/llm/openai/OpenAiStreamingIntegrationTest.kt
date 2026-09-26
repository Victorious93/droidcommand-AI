package ai.droidcommand.llm.openai

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.remote.JdkHttpTransport
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [OpenAiLlmProvider.stream] against a real local server sending the Chat
 * Completions `data:` chunk stream. Never a live call.
 */
class OpenAiStreamingIntegrationTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun sse(vararg chunks: String): String = chunks.joinToString("") { "data: $it\n\n" }

    private fun startServer(status: Int = 200, captured: AtomicReference<String>? = null, body: String): HttpServer {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        httpServer.createContext("/v1/chat/completions") { exchange ->
            captured?.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()
        return httpServer
    }

    private fun providerFor(httpServer: HttpServer) = OpenAiLlmProvider(
        LlmConfig(provider = "openai", model = "gpt-test-model", endpoint = "http://127.0.0.1:${httpServer.address.port}"),
        JdkHttpTransport(),
        requireHttps = false,
    )

    private val request = LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, "hi")))

    @Test
    fun `content deltas stream to the caller and add up to the final Text`() {
        val captured = AtomicReference<String>()
        val httpServer = startServer(
            captured = captured,
            body = sse(
                """{"choices":[{"delta":{"role":"assistant"}}]}""",
                """{"choices":[{"delta":{"content":"Hel"}}]}""",
                """{"choices":[{"delta":{"content":"lo"}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        val deltas = mutableListOf<String>()

        val response = providerFor(httpServer).stream(request) { deltas += it }

        assertEquals("Hello", assertIs<LlmResponse.Text>(response).content)
        assertEquals(listOf("Hel", "lo"), deltas)
        assertTrue(captured.get().contains("\"stream\":true"))
    }

    @Test
    fun `streamed tool_call fragments become one ToolCall`() {
        val httpServer = startServer(
            body = sse(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"shell","arguments":""}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"command\":"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"ls\"}"}}]}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                "[DONE]",
            ),
        )

        val call = assertIs<LlmResponse.ToolCall>(providerFor(httpServer).stream(request) { error("no text expected") })
        assertEquals("shell", call.toolName)
        assertEquals(mapOf("command" to "ls"), call.input)
    }

    @Test
    fun `an in-stream error chunk becomes an Error`() {
        val httpServer = startServer(
            body = sse("""{"choices":[{"delta":{"content":"par"}}]}""", """{"error":{"message":"model crashed","type":"server_error"}}"""),
        )

        val error = assertIs<LlmResponse.Error>(providerFor(httpServer).stream(request) { }).error
        assertIs<LlmError.ModelUnavailable>(error)
        assertTrue(error.message.contains("model crashed"))
    }

    @Test
    fun `chunks after DONE are ignored`() {
        val httpServer = startServer(body = sse("""{"choices":[{"delta":{"content":"a"}}]}""", "[DONE]", """{"choices":[{"delta":{"content":"b"}}]}"""))

        assertEquals("a", assertIs<LlmResponse.Text>(providerFor(httpServer).stream(request) { }).content)
    }

    @Test
    fun `a 429 before streaming is ModelUnavailable`() {
        val httpServer = startServer(status = 429, body = """{"error":{"message":"slow down"}}""")

        assertIs<LlmError.ModelUnavailable>(assertIs<LlmResponse.Error>(providerFor(httpServer).stream(request) { }).error)
    }
}
