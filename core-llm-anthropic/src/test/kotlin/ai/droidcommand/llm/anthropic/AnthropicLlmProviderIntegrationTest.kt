package ai.droidcommand.llm.anthropic

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.remote.JdkHttpTransport
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Exercises [AnthropicLlmProvider] against a real local [HttpServer]
 * speaking the Anthropic Messages API's JSON shape — a real HTTP round
 * trip and real JSON parsing, never a live call to api.anthropic.com and
 * never a real credential, matching the pattern already proven for
 * [ai.droidcommand.remote.RemoteClient] in `RemoteClientIntegrationTest`.
 */
class AnthropicLlmProviderIntegrationTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun startServer(respond: (requestBody: String) -> Pair<Int, String>): HttpServer {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        httpServer.createContext("/v1/messages") { exchange ->
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

    private fun providerFor(httpServer: HttpServer, apiKey: String? = "sk-test-key"): AnthropicLlmProvider {
        val config = LlmConfig(
            provider = "anthropic",
            model = "claude-test-model",
            endpoint = "http://127.0.0.1:${httpServer.address.port}",
            authToken = { apiKey },
        )
        return AnthropicLlmProvider(config, JdkHttpTransport(), requireHttps = false)
    }

    @Test
    fun `a real text response round-trips into LlmResponse Text`() {
        val capturedRequest = AtomicReference<String>()
        val httpServer = startServer { requestBody ->
            capturedRequest.set(requestBody)
            200 to """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"hello there"}],"model":"claude-test-model","stop_reason":"end_turn"}"""
        }

        val response = providerFor(httpServer).complete(
            LlmRequest(systemPrompt = "Be terse.", messages = listOf(Message(Role.USER, "hi"))),
        )

        val text = assertIs<LlmResponse.Text>(response)
        assertEquals("hello there", text.content)
        assertTrue(capturedRequest.get().contains("\"system\":\"Be terse.\""))
        assertTrue(capturedRequest.get().contains("\"role\":\"user\""))
    }

    @Test
    fun `a real tool_use response round-trips into LlmResponse ToolCall with flattened input`() {
        val httpServer = startServer { _ ->
            200 to """{"type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"run_shell","input":{"command":"ls","count":3,"nested":{"a":1}}}]}"""
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
        val httpServer = startServer { _ -> 401 to """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""" }

        val response = providerFor(httpServer).complete(LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, "hi"))))

        val error = assertIs<LlmResponse.Error>(response)
        assertIs<LlmError.Authentication>(error.error)
    }

    @Test
    fun `repeated 529 overload responses become LlmError ModelUnavailable after retries are exhausted`() {
        val httpServer = startServer { _ -> 529 to """{"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}""" }

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
    fun `a missing API key fails closed without ever making a network call`() {
        val httpServer = startServer { _ -> 200 to """{"content":[{"type":"text","text":"should never be seen"}]}""" }

        val response = providerFor(httpServer, apiKey = null).complete(
            LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, "hi"))),
        )

        val error = assertIs<LlmResponse.Error>(response)
        assertIs<LlmError.Authentication>(error.error)
    }

    @Test
    fun `the API key is sent as x-api-key, not an Authorization Bearer header`() {
        val capturedHeaders = AtomicReference<Map<String, List<String>>>()
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        httpServer.createContext("/v1/messages") { exchange ->
            capturedHeaders.set(exchange.requestHeaders)
            exchange.requestBody.readBytes()
            val body = """{"content":[{"type":"text","text":"ok"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        httpServer.start()

        providerFor(httpServer).complete(LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, "hi"))))

        val headers = capturedHeaders.get()
        assertEquals(listOf("sk-test-key"), headers["x-api-key"])
    }

    @Test
    fun `tools without a modeled parameter schema are advertised with an open object schema`() {
        val capturedRequest = AtomicReference<String>()
        val httpServer = startServer { requestBody ->
            capturedRequest.set(requestBody)
            200 to """{"content":[{"type":"text","text":"ok"}]}"""
        }

        providerFor(httpServer).complete(
            LlmRequest(
                systemPrompt = null,
                messages = listOf(Message(Role.USER, "hi")),
                tools = listOf(ToolSpec(name = "run_shell", description = "runs a shell command")),
            ),
        )

        assertTrue(capturedRequest.get().contains(""""tools":[{"name":"run_shell","description":"runs a shell command","input_schema":{"type":"object"}}]"""))
    }

    @Test
    fun `a SYSTEM message found inside the conversation is folded into the top-level system field, not dropped`() {
        val capturedRequest = AtomicReference<String>()
        val httpServer = startServer { requestBody ->
            capturedRequest.set(requestBody)
            200 to """{"content":[{"type":"text","text":"ok"}]}"""
        }

        providerFor(httpServer).complete(
            LlmRequest(
                systemPrompt = "top-level system",
                messages = listOf(Message(Role.SYSTEM, "mid-conversation system note"), Message(Role.USER, "hi")),
            ),
        )

        val body = capturedRequest.get()
        assertTrue(body.contains("top-level system"))
        assertTrue(body.contains("mid-conversation system note"))
        assertTrue(body.contains(""""messages":[{"role":"user","content":"hi"}]"""))
    }

    @Test
    fun `a TOOL role message is mapped to a user message since Anthropic's tool_result needs a tool_use_id this repo does not track`() {
        val capturedRequest = AtomicReference<String>()
        val httpServer = startServer { requestBody ->
            capturedRequest.set(requestBody)
            200 to """{"content":[{"type":"text","text":"ok"}]}"""
        }

        providerFor(httpServer).complete(
            LlmRequest(systemPrompt = null, messages = listOf(Message(Role.TOOL, "exit code 0"))),
        )

        assertTrue(capturedRequest.get().contains(""""messages":[{"role":"user","content":"exit code 0"}]"""))
    }

    @Test
    fun `a tool exchange is sent as tool_use and tool_result blocks with the ids the API gave`() {
        var captured = ""
        val httpServer = startServer { body ->
            captured = body
            200 to """{"content":[{"type":"text","text":"done"}]}"""
        }
        val messages = listOf(
            Message(Role.USER, "time and echo"),
            Message(
                Role.ASSISTANT,
                "Checking.",
                toolCalls = listOf(
                    ai.droidcommand.agent.ToolCallRequest("toolu_1", "clock", """{"zone":"UTC"}"""),
                    ai.droidcommand.agent.ToolCallRequest("toolu_2", "echo", "{}"),
                ),
            ),
            Message(Role.TOOL, "12:00", toolCallId = "toolu_1"),
            Message(Role.TOOL, "ERROR: nothing", toolCallId = "toolu_2"),
        )
        assertEquals(LlmResponse.Text("done"), providerFor(httpServer).complete(LlmRequest(systemPrompt = null, messages = messages)))

        val sent = kotlinx.serialization.json.Json.parseToJsonElement(captured).jsonObject["messages"]!!.jsonArray
        assertEquals(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """[
                  {"role":"user","content":"time and echo"},
                  {"role":"assistant","content":[
                    {"type":"text","text":"Checking."},
                    {"type":"tool_use","id":"toolu_1","name":"clock","input":{"zone":"UTC"}},
                    {"type":"tool_use","id":"toolu_2","name":"echo","input":{}}]},
                  {"role":"user","content":[
                    {"type":"tool_result","tool_use_id":"toolu_1","content":"12:00"},
                    {"type":"tool_result","tool_use_id":"toolu_2","content":"ERROR: nothing"}]}
                ]""",
            ),
            sent,
        )
    }

    @Test
    fun `every tool_use block of a response is returned with its id`() {
        val httpServer = startServer {
            200 to """{"content":[{"type":"text","text":"Two things."},{"type":"tool_use","id":"toolu_a","name":"clock","input":{"zone":"UTC"}},{"type":"tool_use","id":"toolu_b","name":"echo","input":{"n":2}}]}"""
        }
        val call = assertIs<LlmResponse.ToolCall>(
            providerFor(httpServer).complete(LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, "go")), tools = listOf(ToolSpec("clock", "d"), ToolSpec("echo", "d")))),
        )
        assertEquals(listOf("toolu_a", "toolu_b"), call.calls.map { it.id })
        assertEquals(listOf("""{"zone":"UTC"}""", """{"n":2}"""), call.calls.map { it.argumentsJson })
        assertEquals("Two things.", call.text)
    }
}
