package ai.droidcommand.llm.google

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.ResponseFormat
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
 * [GeminiLlmProvider] against a real local server sending Gemini-shaped JSON
 * and SSE responses. Never a live call to Google's API.
 */
class GeminiLlmProviderTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun sse(vararg chunks: String): String = chunks.joinToString("") { "data: $it\n\n" }

    private fun startServer(
        path: String,
        status: Int = 200,
        capturedRequest: AtomicReference<String>? = null,
        body: String,
    ): HttpServer {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        httpServer.createContext(path) { exchange ->
            capturedRequest?.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()
        return httpServer
    }

    private fun providerFor(httpServer: HttpServer, model: String = "gemini-test"): GeminiLlmProvider =
        GeminiLlmProvider(
            LlmConfig(
                provider = "google",
                model = model,
                endpoint = "http://127.0.0.1:${httpServer.address.port}",
                authToken = { "test-gemini-key" },
            ),
            JdkHttpTransport(),
            requireHttps = false,
        )

    private val simpleRequest = LlmRequest(
        systemPrompt = null,
        messages = listOf(Message(Role.USER, "hello")),
    )

    // ---------- complete() ----------

    @Test
    fun `complete sends to generateContent path and returns Text`() {
        val captured = AtomicReference<String>()
        val httpServer = startServer(
            path = "/v1beta/models/gemini-test:generateContent",
            capturedRequest = captured,
            body = """{"candidates":[{"content":{"role":"model","parts":[{"text":"Hello there!"}]}}]}""",
        )
        val response = providerFor(httpServer).complete(simpleRequest)
        assertEquals("Hello there!", assertIs<LlmResponse.Text>(response).content)
        assertTrue(captured.get().contains("\"parts\""))
    }

    @Test
    fun `complete maps system prompt to systemInstruction`() {
        val captured = AtomicReference<String>()
        val httpServer = startServer(
            path = "/v1beta/models/gemini-test:generateContent",
            capturedRequest = captured,
            body = """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}]}""",
        )
        providerFor(httpServer).complete(
            LlmRequest(
                systemPrompt = "You are helpful.",
                messages = listOf(Message(Role.USER, "hi")),
            ),
        )
        assertTrue(captured.get().contains("systemInstruction"), "Expected systemInstruction in body")
        assertTrue(captured.get().contains("You are helpful."))
    }

    @Test
    fun `complete returns Authentication error when API key is blank`() {
        val response = GeminiLlmProvider(
            LlmConfig(provider = "google", model = "m", authToken = { "" }),
            JdkHttpTransport(),
        ).complete(simpleRequest)
        assertIs<LlmResponse.Error>(response).also { assertIs<LlmError.Authentication>(it.error) }
    }

    @Test
    fun `complete maps 401 to Authentication error`() {
        val httpServer = startServer(
            path = "/v1beta/models/gemini-test:generateContent",
            status = 401,
            body = """{"error":{"code":401,"message":"API_KEY_INVALID","status":"UNAUTHENTICATED"}}""",
        )
        val response = providerFor(httpServer).complete(simpleRequest)
        assertIs<LlmResponse.Error>(response).also { assertIs<LlmError.Authentication>(it.error) }
    }

    @Test
    fun `complete maps 429 to ModelUnavailable error`() {
        val httpServer = startServer(
            path = "/v1beta/models/gemini-test:generateContent",
            status = 429,
            body = """{"error":{"code":429,"message":"quota exceeded"}}""",
        )
        val response = providerFor(httpServer).complete(simpleRequest)
        assertIs<LlmResponse.Error>(response).also { assertIs<LlmError.ModelUnavailable>(it.error) }
    }

    @Test
    fun `complete returns ToolCall when candidate has functionCall part`() {
        val httpServer = startServer(
            path = "/v1beta/models/gemini-test:generateContent",
            body = """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"run_shell","args":{"cmd":"ls"}}}]}}]}""",
        )
        val request = LlmRequest(
            systemPrompt = null,
            messages = listOf(Message(Role.USER, "run ls")),
            tools = listOf(ToolSpec("run_shell", "executes shell commands")),
        )
        val response = providerFor(httpServer).complete(request)
        assertIs<LlmResponse.ToolCall>(response).also {
            assertEquals("run_shell", it.toolName)
            assertEquals("ls", it.input["cmd"])
        }
    }

    @Test
    fun `complete with ResponseFormat_Json sends responseMimeType`() {
        val captured = AtomicReference<String>()
        val httpServer = startServer(
            path = "/v1beta/models/gemini-test:generateContent",
            capturedRequest = captured,
            body = """{"candidates":[{"content":{"role":"model","parts":[{"text":"{\"key\":\"value\"}"}]}}]}""",
        )
        val response = providerFor(httpServer).complete(
            simpleRequest.copy(responseFormat = ResponseFormat.Json),
        )
        assertIs<LlmResponse.Text>(response)
        assertTrue(captured.get().contains("application/json"), "Expected responseMimeType in body")
    }

    @Test
    fun `complete rejects tools combined with responseFormat`() {
        val httpServer = startServer(
            path = "/v1beta/models/gemini-test:generateContent",
            body = "should not be reached",
        )
        val response = providerFor(httpServer).complete(
            LlmRequest(
                systemPrompt = null,
                messages = listOf(Message(Role.USER, "hi")),
                tools = listOf(ToolSpec("shell", "run shell")),
                responseFormat = ResponseFormat.Json,
            ),
        )
        assertIs<LlmResponse.Error>(response).also { assertIs<LlmError.InvalidResponse>(it.error) }
    }

    // ---------- stream() ----------

    @Test
    fun `stream delivers text deltas and concatenates them`() {
        val httpServer = startServer(
            path = "/v1beta/models/gemini-test:streamGenerateContent",
            body = sse(
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"Ge"}]}}]}""",
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"mi"}]}}]}""",
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"ni"}]},"finishReason":"STOP"}]}""",
            ),
        )
        val deltas = mutableListOf<String>()
        val response = providerFor(httpServer).stream(simpleRequest) { deltas += it }

        assertEquals("Gemini", assertIs<LlmResponse.Text>(response).content)
        assertEquals(listOf("Ge", "mi", "ni"), deltas)
    }

    @Test
    fun `stream sends to streamGenerateContent path with alt=sse query`() {
        // The query param ?alt=sse is appended to the path; RemoteEndpoint.resolve does
        // pure string concat so it ends up in the full URL. The local HttpServer routes
        // on the path only (strips query), so the same context handler fires.
        val httpServer = startServer(
            path = "/v1beta/models/gemini-test:streamGenerateContent",
            body = sse("""{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}]}"""),
        )
        val response = providerFor(httpServer).stream(simpleRequest) {}
        assertIs<LlmResponse.Text>(response)
    }

    @Test
    fun `stream returns Authentication error when API key is blank`() {
        val response = GeminiLlmProvider(
            LlmConfig(provider = "google", model = "m", authToken = { null }),
            JdkHttpTransport(),
        ).stream(simpleRequest) {}
        assertIs<LlmResponse.Error>(response).also { assertIs<LlmError.Authentication>(it.error) }
    }

    @Test
    fun `stream returns ToolCall from functionCall part`() {
        val httpServer = startServer(
            path = "/v1beta/models/gemini-test:streamGenerateContent",
            body = sse(
                """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"search","args":{"q":"hello"}}}]}}]}""",
            ),
        )
        val request = LlmRequest(
            systemPrompt = null,
            messages = listOf(Message(Role.USER, "search")),
            tools = listOf(ToolSpec("search", "web search")),
        )
        val response = providerFor(httpServer).stream(request) {}
        assertIs<LlmResponse.ToolCall>(response).also {
            assertEquals("search", it.toolName)
            assertEquals("hello", it.input["q"])
        }
    }

    @Test
    fun `stream error event propagates as ModelUnavailable`() {
        val httpServer = startServer(
            path = "/v1beta/models/gemini-test:streamGenerateContent",
            body = sse("""{"error":{"code":503,"message":"overloaded","status":"UNAVAILABLE"}}"""),
        )
        val response = providerFor(httpServer).stream(simpleRequest) {}
        assertIs<LlmResponse.Error>(response).also { assertIs<LlmError.ModelUnavailable>(it.error) }
    }

    @Test
    fun `model name is embedded in path for different models`() {
        val httpServer = startServer(
            path = "/v1beta/models/gemini-1-5-pro:generateContent",
            body = """{"candidates":[{"content":{"role":"model","parts":[{"text":"pro answer"}]}}]}""",
        )
        val response = GeminiLlmProvider(
            LlmConfig(
                provider = "google",
                model = "gemini-1-5-pro",
                endpoint = "http://127.0.0.1:${httpServer.address.port}",
                authToken = { "key" },
            ),
            JdkHttpTransport(),
            requireHttps = false,
        ).complete(simpleRequest)
        assertEquals("pro answer", assertIs<LlmResponse.Text>(response).content)
    }
}
