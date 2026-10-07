package ai.droidcommand.llm.groq

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.remote.InsecureEndpointRejected
import ai.droidcommand.remote.JdkHttpTransport
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** [GroqLlmProvider] against a local mock server only — never a live API or real key. */
class GroqLlmProviderTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private val request = LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, "hi")))

    private fun start(status: Int = 200, auth: AtomicReference<String?>? = null, body: String): HttpServer {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = s
        // Mounted under /openai to prove the Groq path prefix is applied.
        s.createContext("/openai/v1/chat/completions") { ex ->
            auth?.set(ex.requestHeaders.getFirst("Authorization"))
            ex.requestBody.readBytes()
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        return s
    }

    private fun provider(s: HttpServer) = GroqLlmProvider(
        LlmConfig(
            provider = "groq",
            model = "llama-test",
            endpoint = "http://127.0.0.1:${s.address.port}/openai",
            authToken = { "gsk-test" },
        ),
        JdkHttpTransport(),
        requireHttps = false,
    )

    @Test
    fun `complete hits the openai-prefixed path with a bearer key`() {
        val auth = AtomicReference<String?>()
        val s = start(auth = auth, body = """{"choices":[{"message":{"role":"assistant","content":"hey"}}]}""")

        assertEquals("hey", assertIs<LlmResponse.Text>(provider(s).complete(request)).content)
        assertEquals("Bearer gsk-test", auth.get())
    }

    @Test
    fun `stream delegates to the openai chunk parser`() {
        val s = start(
            body = "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"b\"}}]}\n\ndata: [DONE]\n\n",
        )
        val deltas = mutableListOf<String>()

        assertEquals("ab", assertIs<LlmResponse.Text>(provider(s).stream(request) { deltas += it }).content)
        assertEquals(listOf("a", "b"), deltas)
    }

    @Test
    fun `401 is an Authentication error`() {
        val s = start(status = 401, body = """{"error":{"message":"bad key"}}""")

        assertIs<LlmError.Authentication>(assertIs<LlmResponse.Error>(provider(s).complete(request)).error)
    }

    @Test
    fun `default endpoint is the hosted Groq base and https is enforced`() {
        val p = GroqLlmProvider(LlmConfig(provider = "groq", model = "m"), JdkHttpTransport())
        assertEquals(GroqLlmProvider.DEFAULT_BASE_URL, p.config.endpoint)

        assertFailsWith<InsecureEndpointRejected> {
            GroqLlmProvider(LlmConfig(provider = "groq", model = "m", endpoint = "http://example.com"), JdkHttpTransport())
        }
    }
}
