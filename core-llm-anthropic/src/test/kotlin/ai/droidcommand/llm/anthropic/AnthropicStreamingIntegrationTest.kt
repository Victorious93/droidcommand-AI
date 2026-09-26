package ai.droidcommand.llm.anthropic

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
 * [AnthropicLlmProvider.stream] against a real local server sending the
 * Messages API's server-sent-event stream shape. Never a live call.
 */
class AnthropicStreamingIntegrationTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun sse(vararg events: Pair<String, String>): String =
        events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" }

    private fun startServer(status: Int = 200, captured: AtomicReference<String>? = null, body: String): HttpServer {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        httpServer.createContext("/v1/messages") { exchange ->
            captured?.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", if (status == 200) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()
        return httpServer
    }

    private fun providerFor(httpServer: HttpServer) = AnthropicLlmProvider(
        LlmConfig(
            provider = "anthropic",
            model = "claude-test-model",
            endpoint = "http://127.0.0.1:${httpServer.address.port}",
            authToken = { "sk-test-key" },
        ),
        JdkHttpTransport(),
        requireHttps = false,
    )

    private val request = LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, "hi")))

    @Test
    fun `text deltas stream to the caller and add up to the final Text`() {
        val captured = AtomicReference<String>()
        val httpServer = startServer(
            captured = captured,
            body = sse(
                "message_start" to """{"type":"message_start","message":{"id":"msg_1"}}""",
                "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                "ping" to """{"type":"ping"}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                "message_stop" to """{"type":"message_stop"}""",
            ),
        )
        val deltas = mutableListOf<String>()

        val response = providerFor(httpServer).stream(request) { deltas += it }

        assertEquals("Hello", assertIs<LlmResponse.Text>(response).content)
        assertEquals(listOf("Hel", "lo"), deltas)
        assertTrue(captured.get().contains("\"stream\":true"))
    }

    @Test
    fun `a streamed tool_use block becomes a ToolCall and is never sent as text`() {
        val httpServer = startServer(
            body = sse(
                "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Let me check."}}""",
                "content_block_start" to
                    """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t1","name":"shell","input":{}}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"command\":"}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"ls\"}"}}""",
                "message_stop" to """{"type":"message_stop"}""",
            ),
        )
        val deltas = mutableListOf<String>()

        val response = providerFor(httpServer).stream(request) { deltas += it }

        val call = assertIs<LlmResponse.ToolCall>(response)
        assertEquals("shell", call.toolName)
        assertEquals(mapOf("command" to "ls"), call.input)
        assertEquals(listOf("Let me check."), deltas)
    }

    @Test
    fun `an in-stream error event becomes an Error even after partial text`() {
        val httpServer = startServer(
            body = sse(
                "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"par"}}""",
                "error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
            ),
        )

        val response = providerFor(httpServer).stream(request) { }

        val error = assertIs<LlmResponse.Error>(response).error
        assertIs<LlmError.ModelUnavailable>(error)
        assertTrue(error.message.contains("Overloaded"))
    }

    @Test
    fun `a 401 before streaming is an Authentication error`() {
        val httpServer = startServer(status = 401, body = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")

        val response = providerFor(httpServer).stream(request) { error("no deltas expected") }

        assertIs<LlmError.Authentication>(assertIs<LlmResponse.Error>(response).error)
    }

    @Test
    fun `a stream with no content blocks is an InvalidResponse`() {
        val httpServer = startServer(body = sse("message_stop" to """{"type":"message_stop"}"""))

        assertIs<LlmError.InvalidResponse>(assertIs<LlmResponse.Error>(providerFor(httpServer).stream(request) { }).error)
    }
}
