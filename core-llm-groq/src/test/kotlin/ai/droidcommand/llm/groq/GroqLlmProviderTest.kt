package ai.droidcommand.llm.groq

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.remote.JdkHttpTransport
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [GroqLlmProvider] against a local server — verifies delegation to
 * [ai.droidcommand.llm.openai.OpenAiLlmProvider] with the correct base URL
 * and that the full streaming / completion round-trips work. Never a live call.
 */
class GroqLlmProviderTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun sse(vararg chunks: String): String = chunks.joinToString("") { "data: $it\n\n" }

    private fun startServer(
        path: String = "/v1/chat/completions",
        status: Int = 200,
        body: String,
    ): HttpServer {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        httpServer.createContext(path) { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()
        return httpServer
    }

    /**
     * Endpoint is set to the loopback address so the test server is reached.
     * The Groq-specific `/openai` prefix is omitted here — a caller supplying
     * their own endpoint owns the full URL prefix, and the test verifies that
     * the delegation works regardless of prefix.
     */
    private fun providerFor(httpServer: HttpServer) = GroqLlmProvider(
        LlmConfig(
            provider = "groq",
            model = "llama-3-test",
            endpoint = "http://127.0.0.1:${httpServer.address.port}",
            authToken = { "test-groq-key" },
        ),
        JdkHttpTransport(),
        requireHttps = false,
    )

    private val simpleRequest = LlmRequest(
        systemPrompt = null,
        messages = listOf(Message(Role.USER, "hello")),
    )

    @Test
    fun `complete returns Text from OpenAI-shape response`() {
        val httpServer = startServer(
            body = """{"choices":[{"message":{"role":"assistant","content":"Hi from Groq!"}}]}""",
        )
        val response = providerFor(httpServer).complete(simpleRequest)
        assertEquals("Hi from Groq!", assertIs<LlmResponse.Text>(response).content)
    }

    @Test
    fun `stream delivers deltas and returns concatenated Text`() {
        val httpServer = startServer(
            body = sse(
                """{"choices":[{"delta":{"role":"assistant"}}]}""",
                """{"choices":[{"delta":{"content":"Gr"}}]}""",
                """{"choices":[{"delta":{"content":"oq"}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        val deltas = mutableListOf<String>()
        val response = providerFor(httpServer).stream(simpleRequest) { deltas += it }

        assertEquals("Groq", assertIs<LlmResponse.Text>(response).content)
        assertEquals(listOf("Gr", "oq"), deltas)
    }

    @Test
    fun `blank authToken returns Authentication error without network call`() {
        val response = GroqLlmProvider(
            LlmConfig(provider = "groq", model = "llama-3-test", authToken = { "" }),
            JdkHttpTransport(),
        ).complete(simpleRequest)
        assertIs<LlmResponse.Error>(response).also {
            assertIs<LlmError.Authentication>(it.error)
        }
    }

    @Test
    fun `HTTP 401 maps to Authentication error`() {
        val httpServer = startServer(status = 401, body = """{"error":{"message":"invalid key"}}""")
        val response = providerFor(httpServer).complete(simpleRequest)
        assertIs<LlmResponse.Error>(response).also {
            assertIs<LlmError.Authentication>(it.error)
        }
    }

    @Test
    fun `default base URL contains api_groq_com`() {
        assertTrue(GroqLlmProvider.DEFAULT_BASE_URL.contains("api.groq.com"))
    }

    @Test
    fun `default OpenAI-compat URL contains openai path segment`() {
        assertTrue(GroqLlmProvider.DEFAULT_OPENAI_COMPAT_URL.contains("/openai"))
        assertContains(GroqLlmProvider.DEFAULT_OPENAI_COMPAT_URL, GroqLlmProvider.DEFAULT_BASE_URL)
    }
}
