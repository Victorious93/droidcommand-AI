package ai.droidcommand.llm.openai

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.ToolSpec
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
 * Exercises [OpenAiLlmProvider] against a real local [HttpServer] speaking
 * the OpenAI Chat Completions API's JSON shape — a real HTTP round trip
 * and real JSON parsing, never a live call to api.openai.com and never a
 * real credential, matching the pattern already proven for
 * `core-llm-anthropic.AnthropicLlmProvider`.
 */
class OpenAiLlmProviderIntegrationTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun startServer(respond: (requestBody: String) -> Pair<Int, String>): HttpServer {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        httpServer.createContext("/v1/chat/completions") { exchange ->
            val requestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val (status, responseBody) = respond(requestBody)
            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()
        return httpServer
    }

    private fun providerFor(httpServer: HttpServer, apiKey: String? = "sk-test-key"): OpenAiLlmProvider {
        val config = LlmConfig(
            provider = "openai",
            model = "gpt-test-model",
            endpoint = "http://127.0.0.1:${httpServer.address.port}",
            authToken = { apiKey },
        )
        return OpenAiLlmProvider(config, JdkHttpTransport(), requireHttps = false)
    }

    @Test
    fun `a real text response round-trips into LlmResponse Text`() {
        val capturedRequest = AtomicReference<String>()
        val httpServer = startServer { requestBody ->
            capturedRequest.set(requestBody)
            200 to """{"choices":[{"message":{"role":"assistant","content":"hello there"},"finish_reason":"stop"}]}"""
        }

        val response = providerFor(httpServer).complete(
            LlmRequest(systemPrompt = "Be terse.", messages = listOf(Message(Role.USER, "hi"))),
        )

        val text = assertIs<LlmResponse.Text>(response)
        assertEquals("hello there", text.content)
        assertTrue(capturedRequest.get().contains(""""messages":[{"role":"system","content":"Be terse."},{"role":"user","content":"hi"}]"""))
    }

    @Test
    fun `a real tool_calls response round-trips into LlmResponse ToolCall with flattened arguments`() {
        val httpServer = startServer { _ ->
            200 to """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"run_shell","arguments":"{\"command\":\"ls\",\"count\":3,\"nested\":{\"a\":1}}"}}]}}]}"""
        }

        val response = providerFor(httpServer).complete(
            LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, "list files"))),
        )

        val toolCall = assertIs<LlmResponse.ToolCall>(response)
        assertEquals("run_shell", toolCall.toolName)
        assertEquals("ls", toolCall.input["command"])
        assertEquals("3", toolCall.input["count"])
        assertEquals("""{"a":1}""", toolCall.input["nested"])
    }

    @Test
    fun `a 401 response becomes LlmError Authentication`() {
        val httpServer = startServer { _ -> 401 to """{"error":{"message":"invalid api key","type":"invalid_request_error"}}""" }

        val response = providerFor(httpServer).complete(LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, "hi"))))

        val error = assertIs<LlmResponse.Error>(response)
        assertIs<LlmError.Authentication>(error.error)
    }

    @Test
    fun `repeated 503 responses become LlmError ModelUnavailable after retries are exhausted`() {
        val httpServer = startServer { _ -> 503 to """{"error":{"message":"model is overloaded"}}""" }

        val response = providerFor(httpServer).complete(LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, "hi"))))

        val error = assertIs<LlmResponse.Error>(response)
        assertIs<LlmError.ModelUnavailable>(error.error)
    }

    @Test
    fun `a malformed response body becomes LlmError InvalidResponse rather than throwing`() {
        val httpServer = startServer { _ -> 200 to "not json at all" }

        val response = providerFor(httpServer).complete(LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, "hi"))))

        val error = assertIs<LlmResponse.Error>(response)
        assertIs<LlmError.InvalidResponse>(error.error)
    }

    @Test
    fun `a missing API key does not block the request, unlike the Anthropic provider`() {
        val httpServer = startServer { _ -> 200 to """{"choices":[{"message":{"role":"assistant","content":"ok, no key needed"}}]}""" }

        val response = providerFor(httpServer, apiKey = null).complete(
            LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, "hi"))),
        )

        val text = assertIs<LlmResponse.Text>(response)
        assertEquals("ok, no key needed", text.content)
    }

    @Test
    fun `the API key is sent as an Authorization Bearer header`() {
        val capturedHeaders = AtomicReference<Map<String, List<String>>>()
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        httpServer.createContext("/v1/chat/completions") { exchange ->
            capturedHeaders.set(exchange.requestHeaders)
            exchange.requestBody.readBytes()
            val body = """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        httpServer.start()

        providerFor(httpServer).complete(LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, "hi"))))

        val headers = capturedHeaders.get()
        assertEquals(listOf("Bearer sk-test-key"), headers["Authorization"])
    }

    @Test
    fun `tools without a modeled parameter schema are advertised with an open object schema`() {
        val capturedRequest = AtomicReference<String>()
        val httpServer = startServer { requestBody ->
            capturedRequest.set(requestBody)
            200 to """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
        }

        providerFor(httpServer).complete(
            LlmRequest(
                systemPrompt = null,
                messages = listOf(Message(Role.USER, "hi")),
                tools = listOf(ToolSpec(name = "run_shell", description = "runs a shell command")),
            ),
        )

        assertTrue(
            capturedRequest.get().contains(
                """"tools":[{"type":"function","function":{"name":"run_shell","description":"runs a shell command","parameters":{"type":"object"}}}]""",
            ),
        )
    }

    @Test
    fun `a TOOL role message is mapped to a user message since OpenAI's tool role needs a tool_call_id this repo does not track`() {
        val capturedRequest = AtomicReference<String>()
        val httpServer = startServer { requestBody ->
            capturedRequest.set(requestBody)
            200 to """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
        }

        providerFor(httpServer).complete(
            LlmRequest(systemPrompt = null, messages = listOf(Message(Role.TOOL, "exit code 0"))),
        )

        assertTrue(capturedRequest.get().contains(""""messages":[{"role":"user","content":"exit code 0"}]"""))
    }
}
