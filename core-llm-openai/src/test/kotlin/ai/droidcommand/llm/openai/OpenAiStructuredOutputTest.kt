package ai.droidcommand.llm.openai

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Structured output (ROADMAP-060) through `response_format`, against a real local server. */
class OpenAiStructuredOutputTest {
    private var server: HttpServer? = null
    private val captured = AtomicReference<String>()
    private val hits = AtomicInteger()

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun providerReturning(body: String): OpenAiLlmProvider {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        httpServer.createContext("/v1/chat/completions") { exchange ->
            hits.incrementAndGet()
            captured.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()
        return OpenAiLlmProvider(
            LlmConfig(provider = "openai", model = "gpt-test-model", endpoint = "http://127.0.0.1:${httpServer.address.port}"),
            JdkHttpTransport(),
            requireHttps = false,
        )
    }

    private fun answer(content: String) = """{"choices":[{"message":{"role":"assistant","content":${JsonPrimitive(content)}}}]}"""

    private val schema = """{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}"""

    private fun request(format: ResponseFormat, tools: List<ToolSpec> = emptyList()) =
        LlmRequest(systemPrompt = "Reply in JSON.", messages = listOf(Message(Role.USER, "hi")), tools = tools, responseFormat = format)

    @Test
    fun `a schema is sent as json_schema and a JSON answer comes back as Text`() {
        val provider = providerReturning(answer("""{"answer":"42"}"""))

        val response = provider.complete(request(ResponseFormat.Schema("answer_schema", schema)))

        assertEquals("""{"answer":"42"}""", assertIs<LlmResponse.Text>(response).content)
        val format = Json.parseToJsonElement(captured.get()).jsonObject.getValue("response_format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        val jsonSchema = format.getValue("json_schema").jsonObject
        assertEquals("answer_schema", jsonSchema.getValue("name").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(schema), jsonSchema.getValue("schema"))
        assertFalse(jsonSchema.getValue("strict").jsonPrimitive.boolean)
    }

    @Test
    fun `Json mode sends json_object and rejects a non-JSON answer`() {
        val provider = providerReturning(answer("sure thing"))

        val error = assertIs<LlmResponse.Error>(provider.complete(request(ResponseFormat.Json))).error

        assertIs<LlmError.InvalidResponse>(error)
        val format = Json.parseToJsonElement(captured.get()).jsonObject.getValue("response_format").jsonObject
        assertEquals("json_object", format.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `no responseFormat sends no response_format field`() {
        val provider = providerReturning(answer("plain"))

        assertEquals("plain", assertIs<LlmResponse.Text>(provider.complete(LlmRequest(null, listOf(Message(Role.USER, "hi"))))).content)
        assertFalse(captured.get().contains("response_format"))
    }

    @Test
    fun `structured output combined with tools is rejected before sending`() {
        val provider = providerReturning(answer("{}"))

        assertIs<LlmResponse.Error>(provider.complete(request(ResponseFormat.Json, listOf(ToolSpec("shell", "run")))))
        assertEquals(0, hits.get())
    }

    @Test
    fun `streamed structured output is validated at the end`() {
        val provider = providerReturning(
            listOf("""{"choices":[{"delta":{"content":"{\"answer\":"}}]}""", """{"choices":[{"delta":{"content":"\"42\"}"}}]}""", "[DONE]")
                .joinToString("") { "data: $it\n\n" },
        )
        val deltas = mutableListOf<String>()

        val response = provider.stream(request(ResponseFormat.Json)) { deltas += it }

        assertEquals("""{"answer":"42"}""", assertIs<LlmResponse.Text>(response).content)
        assertEquals(2, deltas.size)
    }
}
