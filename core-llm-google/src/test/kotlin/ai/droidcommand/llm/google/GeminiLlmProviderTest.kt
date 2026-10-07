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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [GeminiLlmProvider] against a local mock server speaking the documented Gemini JSON shape. Never a live API or real key. */
class GeminiLlmProviderTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private class Captured {
        val path = AtomicReference<String>()
        val key = AtomicReference<String?>()
        val body = AtomicReference<String>()
    }

    private fun start(captured: Captured = Captured(), status: Int = 200, body: String): HttpServer {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = s
        s.createContext("/") { ex ->
            captured.path.set(ex.requestURI.toString())
            captured.key.set(ex.requestHeaders.getFirst("x-goog-api-key"))
            captured.body.set(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        return s
    }

    private fun provider(s: HttpServer, key: String? = "gem-test", model: String = "gemini-test") = GeminiLlmProvider(
        LlmConfig(provider = "google", model = model, endpoint = "http://127.0.0.1:${s.address.port}", authToken = { key }),
        JdkHttpTransport(),
        requireHttps = false,
    )

    private val hi = LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, "hi")))
    private fun text(t: String) = """{"candidates":[{"content":{"role":"model","parts":[{"text":"$t"}]},"finishReason":"STOP"}]}"""
    private fun sse(vararg chunks: String) = chunks.joinToString("") { "data: $it\r\n\r\n" }

    @Test
    fun `text round trip uses model path header key and maps roles`() {
        val c = Captured()
        val s = start(c, body = text("hello"))

        val response = provider(s, model = "models/gemini-test").complete(
            LlmRequest(
                systemPrompt = "Be terse.",
                messages = listOf(Message(Role.USER, "a"), Message(Role.ASSISTANT, "b"), Message(Role.SYSTEM, "extra"), Message(Role.USER, "c")),
                temperature = 0.2,
                maxOutputTokens = 50,
            ),
        )

        assertEquals("hello", assertIs<LlmResponse.Text>(response).content)
        assertEquals("/v1beta/models/gemini-test:generateContent", c.path.get())
        assertEquals("gem-test", c.key.get())
        val body = c.body.get()
        assertTrue(body.contains(""""systemInstruction":{"parts":[{"text":"Be terse.\n\nextra"}]}"""), body)
        assertTrue(body.contains(""""contents":[{"role":"user","parts":[{"text":"a"}]},{"role":"model","parts":[{"text":"b"}]},{"role":"user","parts":[{"text":"c"}]}]"""), body)
        assertTrue(body.contains(""""generationConfig":{"temperature":0.2,"maxOutputTokens":50}"""), body)
    }

    @Test
    fun `functionCall becomes a ToolCall with flattened args and tools are declared`() {
        val c = Captured()
        val s = start(c, body = """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"run_shell","args":{"command":"ls","n":3,"o":{"a":1}}}}]}}]}""")

        val response = provider(s).complete(hi.copy(tools = listOf(ToolSpec("run_shell", "runs a command"))))

        val call = assertIs<LlmResponse.ToolCall>(response)
        assertEquals("run_shell", call.toolName)
        assertEquals(mapOf("command" to "ls", "n" to "3", "o" to """{"a":1}"""), call.input)
        assertTrue(c.body.get().contains(""""tools":[{"functionDeclarations":[{"name":"run_shell","description":"runs a command"}]}]"""))
    }

    @Test
    fun `thought parts are excluded from the answer`() {
        val s = start(body = """{"candidates":[{"content":{"parts":[{"text":"thinking","thought":true},{"text":"answer"}]}}]}""")

        assertEquals("answer", assertIs<LlmResponse.Text>(provider(s).complete(hi)).content)
    }

    @Test
    fun `missing key fails closed without sending`() {
        val c = Captured()
        val s = start(c, body = text("x"))

        assertIs<LlmError.Authentication>(assertIs<LlmResponse.Error>(provider(s, key = " ").complete(hi)).error)
        assertNull(c.path.get())
    }

    @Test
    fun `403 and 400 API_KEY_INVALID are Authentication, 429 is ModelUnavailable, other 400 is InvalidResponse`() {
        fun errorFor(status: Int, body: String) =
            assertIs<LlmResponse.Error>(provider(start(status = status, body = body)).also { }.complete(hi)).error

        assertIs<LlmError.Authentication>(errorFor(403, """{"error":{"status":"PERMISSION_DENIED"}}"""))
        server?.stop(0)
        assertIs<LlmError.Authentication>(errorFor(400, """{"error":{"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""))
        server?.stop(0)
        assertIs<LlmError.ModelUnavailable>(errorFor(429, """{"error":{"status":"RESOURCE_EXHAUSTED"}}"""))
        server?.stop(0)
        assertIs<LlmError.InvalidResponse>(errorFor(400, """{"error":{"status":"INVALID_ARGUMENT","message":"bad field"}}"""))
    }

    @Test
    fun `blocked prompt and empty candidate are InvalidResponse`() {
        val blocked = start(body = """{"promptFeedback":{"blockReason":"SAFETY"}}""")
        val e1 = assertIs<LlmResponse.Error>(provider(blocked).complete(hi)).error
        assertIs<LlmError.InvalidResponse>(e1)
        assertTrue(e1.message.contains("SAFETY"))
        server?.stop(0)

        val empty = start(body = """{"candidates":[{"finishReason":"MAX_TOKENS"}]}""")
        assertIs<LlmError.InvalidResponse>(assertIs<LlmResponse.Error>(provider(empty).complete(hi)).error)
    }

    @Test
    fun `structured output sets mime type and schema and rejects non-object text`() {
        val c = Captured()
        val s = start(c, body = text("""{\"a\":1}"""))
        val format = ResponseFormat.Schema("thing", """{"type":"object","properties":{"a":{"type":"integer"}}}""")

        assertIs<LlmResponse.Text>(provider(s).complete(hi.copy(responseFormat = format)))
        assertTrue(c.body.get().contains(""""responseMimeType":"application/json","responseJsonSchema":{"type":"object""""), c.body.get())
        server?.stop(0)

        val notJson = start(body = text("plain"))
        assertIs<LlmError.InvalidResponse>(assertIs<LlmResponse.Error>(provider(notJson).complete(hi.copy(responseFormat = ResponseFormat.Json))).error)
    }

    @Test
    fun `responseFormat with tools is rejected before sending`() {
        val c = Captured()
        val s = start(c, body = text("x"))

        val r = provider(s).complete(hi.copy(tools = listOf(ToolSpec("t", "d")), responseFormat = ResponseFormat.Json))
        assertIs<LlmError.InvalidResponse>(assertIs<LlmResponse.Error>(r).error)
        assertNull(c.path.get())
    }

    @Test
    fun `stream accumulates text deltas via streamGenerateContent`() {
        val c = Captured()
        val s = start(c, body = sse(text("par"), text("tial")))
        val deltas = mutableListOf<String>()

        val r = provider(s).stream(hi) { deltas += it }

        assertEquals("partial", assertIs<LlmResponse.Text>(r).content)
        assertEquals(listOf("par", "tial"), deltas)
        assertEquals("/v1beta/models/gemini-test:streamGenerateContent?alt=sse", c.path.get())
    }

    @Test
    fun `stream functionCall wins and in-stream error becomes ModelUnavailable`() {
        val s = start(body = sse(text("x"), """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"t","args":{"k":"v"}}}]}}]}"""))
        assertEquals(mapOf("k" to "v"), assertIs<LlmResponse.ToolCall>(provider(s).stream(hi) { }).input)
        server?.stop(0)

        val bad = start(body = sse(text("x"), """{"error":{"status":"UNAVAILABLE","message":"overloaded"}}"""))
        val e = assertIs<LlmResponse.Error>(provider(bad).stream(hi) { }).error
        assertIs<LlmError.ModelUnavailable>(e)
        assertTrue(e.message.contains("overloaded"))
    }
}
