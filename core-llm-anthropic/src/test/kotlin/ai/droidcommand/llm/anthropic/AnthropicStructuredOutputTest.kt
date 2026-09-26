package ai.droidcommand.llm.anthropic

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Structured output (ROADMAP-060) through a forced tool call, against a real local server. */
class AnthropicStructuredOutputTest {
    private var server: HttpServer? = null
    private val captured = AtomicReference<String>()
    private val hits = AtomicInteger()

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun providerReturning(body: String): AnthropicLlmProvider {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        httpServer.createContext("/v1/messages") { exchange ->
            hits.incrementAndGet()
            captured.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()
        return AnthropicLlmProvider(
            LlmConfig(
                provider = "anthropic",
                model = "claude-test-model",
                endpoint = "http://127.0.0.1:${httpServer.address.port}",
                authToken = { "sk-test-key" },
            ),
            JdkHttpTransport(),
            requireHttps = false,
        )
    }

    private val schema = """{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}"""

    private fun request(format: ResponseFormat, tools: List<ToolSpec> = emptyList()) =
        LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, "hi")), tools = tools, responseFormat = format)

    @Test
    fun `a schema is sent as one forced tool and its input comes back as JSON text`() {
        val provider = providerReturning(
            """{"content":[{"type":"tool_use","id":"t1","name":"answer_schema","input":{"answer":"42","n":1}}],"stop_reason":"tool_use"}""",
        )

        val response = provider.complete(request(ResponseFormat.Schema("answer_schema", schema)))

        val text = assertIs<LlmResponse.Text>(response)
        assertEquals("""{"answer":"42","n":1}""", text.content)
        val sent = Json.parseToJsonElement(captured.get()).jsonObject
        val tool = sent.getValue("tools").jsonArray.single().jsonObject
        assertEquals("answer_schema", tool.getValue("name").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(schema), tool.getValue("input_schema"))
        val choice = sent.getValue("tool_choice").jsonObject
        assertEquals("tool", choice.getValue("type").jsonPrimitive.content)
        assertEquals("answer_schema", choice.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `a plain text answer to a structured request is an InvalidResponse`() {
        val provider = providerReturning("""{"content":[{"type":"text","text":"sure"}],"stop_reason":"end_turn"}""")

        assertIs<LlmError.InvalidResponse>(assertIs<LlmResponse.Error>(provider.complete(request(ResponseFormat.Json))).error)
    }

    @Test
    fun `structured output combined with tools is rejected before sending`() {
        val provider = providerReturning("{}")
        val tools = listOf(ToolSpec("shell", "run a command"))

        val error = assertIs<LlmResponse.Error>(provider.complete(request(ResponseFormat.Json, tools))).error

        assertIs<LlmError.InvalidResponse>(error)
        assertEquals(0, hits.get())
    }

    @Test
    fun `a schema that is not a JSON object is rejected before sending`() {
        val provider = providerReturning("{}")

        assertIs<LlmResponse.Error>(provider.complete(request(ResponseFormat.Schema("bad", "[1,2]"))))
        assertEquals(0, hits.get())
    }

    @Test
    fun `streamed structured output sends the JSON fragments as deltas`() {
        val provider = providerReturning(
            listOf(
                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1","name":"json_output","input":{}}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"answer\":"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"42\"}"}}""",
                """{"type":"message_stop"}""",
            ).joinToString("") { "data: $it\n\n" },
        )
        val deltas = mutableListOf<String>()

        val response = provider.stream(request(ResponseFormat.Json)) { deltas += it }

        assertEquals("""{"answer":"42"}""", assertIs<LlmResponse.Text>(response).content)
        assertEquals("""{"answer":"42"}""", deltas.joinToString(""))
        assertTrue(captured.get().contains("\"name\":\"json_output\""))
    }
}
