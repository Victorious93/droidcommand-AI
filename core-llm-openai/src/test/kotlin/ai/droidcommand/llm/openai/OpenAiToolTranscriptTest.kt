package ai.droidcommand.llm.openai

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.ToolCallRequest
import ai.droidcommand.agent.ToolJsonType
import ai.droidcommand.agent.ToolParameter
import ai.droidcommand.agent.ToolParameterSchema
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.openai.testing.StrictChatServer
import ai.droidcommand.llm.openai.testing.StrictReply
import ai.droidcommand.llm.openai.testing.StrictTranscript
import ai.droidcommand.remote.JdkHttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tool-call exchange of [OpenAiLlmProvider] against [StrictChatServer], which answers HTTP 400 to a transcript
 * the Chat Completions API would not accept. A controlled server, never a model and never a live API.
 */
class OpenAiToolTranscriptTest {
    private val echo = ToolSpec(name = "echo", description = "Repeats text")
    private val clock = ToolSpec(name = "clock", description = "Tells the time")
    private val user = Message(Role.USER, "use the tools")

    private fun provider(server: StrictChatServer) = OpenAiLlmProvider(
        LlmConfig(provider = "openai", model = "test-model", endpoint = server.baseUrl),
        JdkHttpTransport(),
        requireHttps = false,
    )

    private fun request(vararg messages: Message, tools: List<ToolSpec> = listOf(echo, clock)) =
        LlmRequest(systemPrompt = "be brief", messages = messages.toList(), tools = tools)

    private fun JsonObject.text(key: String) = (this[key] as JsonPrimitive).content

    @Test
    fun `a call keeps its id and exact arguments, and the answered transcript is accepted`() {
        val arguments = """{"text":"hi","nested":{"n":1},"list":[1,2]}"""
        StrictChatServer { request ->
            if (request.roles().last() == "tool") StrictReply.Text("done") else StrictReply.ToolCalls(listOf(StrictReply.Call("call_abc123", "echo", arguments)))
        }.use { server ->
            val provider = provider(server)

            val call = assertIs<LlmResponse.ToolCall>(provider.complete(request(user)))
            assertEquals("call_abc123", call.id)
            assertEquals("echo", call.toolName)
            assertEquals(arguments, call.argumentsJson)
            assertEquals(mapOf("text" to "hi", "nested" to """{"n":1}""", "list" to "[1,2]"), call.input)
            assertEquals(1, call.calls.size)

            val followUp = request(
                user,
                Message(Role.ASSISTANT, "", toolCalls = listOf(ToolCallRequest(call.id!!, call.toolName, call.argumentsJson!!))),
                Message(Role.TOOL, "hi", toolCallId = call.id),
            )
            assertEquals(LlmResponse.Text("done"), provider.complete(followUp))

            assertTrue(server.rejections.isEmpty(), server.rejections.toString())
            val sent = server.requests.last().messages
            assertEquals(listOf("system", "user", "assistant", "tool"), server.requests.last().roles())
            val assistant = sent[2]
            assertEquals(JsonNull, assistant["content"], "an assistant message with only tool calls has null content")
            val sentCall = (assistant["tool_calls"] as JsonArray).single() as JsonObject
            assertEquals("call_abc123", sentCall.text("id"))
            assertEquals("function", sentCall.text("type"))
            assertEquals("echo", (sentCall["function"] as JsonObject).text("name"))
            assertEquals(arguments, (sentCall["function"] as JsonObject).text("arguments"), "arguments go back as the same text")
            assertEquals("tool", sent[3].text("role"))
            assertEquals("call_abc123", sent[3].text("tool_call_id"))
            assertEquals("hi", sent[3].text("content"))
        }
    }

    @Test
    fun `several calls in one response are all returned in order, streamed or not`() {
        val calls = listOf(
            StrictReply.Call("call_1", "echo", """{"text":"first one"}"""),
            StrictReply.Call("call_2", "clock", """{}"""),
            StrictReply.Call("call_3", "echo", """{"text":"third, with a longer argument"}"""),
        )
        StrictChatServer { StrictReply.ToolCalls(calls, text = "Let me check.") }.use { server ->
            val provider = provider(server)
            val deltas = StringBuilder()
            for (response in listOf(provider.complete(request(user)), provider.stream(request(user)) { deltas.append(it) })) {
                val first = assertIs<LlmResponse.ToolCall>(response)
                assertEquals(listOf("call_1", "call_2", "call_3"), first.calls.map { it.id })
                assertEquals(listOf("echo", "clock", "echo"), first.calls.map { it.toolName })
                assertEquals(calls.map { it.arguments }, first.calls.map { it.argumentsJson })
                assertEquals("Let me check.", first.text)
                assertTrue(first.calls.drop(1).all { it.additionalCalls.isEmpty() })
            }
            assertEquals("Let me check.", deltas.toString(), "only assistant text is streamed, never call arguments")
            assertTrue(server.requests.last().stream)
        }
    }

    @Test
    fun `streamed calls sent whole at the same index are told apart by id`() {
        val calls = listOf(StrictReply.Call("call_a", "echo", """{"text":"a"}"""), StrictReply.Call("call_b", "echo", """{"text":"b"}"""))
        StrictChatServer { StrictReply.ToolCalls(calls, sameIndex = true) }.use { server ->
            val first = assertIs<LlmResponse.ToolCall>(provider(server).stream(request(user)) {})
            assertEquals(listOf("call_a", "call_b"), first.calls.map { it.id })
            assertEquals(listOf("""{"text":"a"}""", """{"text":"b"}"""), first.calls.map { it.argumentsJson })
        }
    }

    @Test
    fun `all results of a multi-call turn are accepted, and a missing one is refused by the server`() {
        StrictChatServer { StrictReply.Text("ok") }.use { server ->
            val provider = provider(server)
            val assistant = Message(
                Role.ASSISTANT,
                "Checking.",
                toolCalls = listOf(ToolCallRequest("c1", "echo", """{"text":"x"}"""), ToolCallRequest("c2", "clock", "{}")),
            )
            val complete = request(user, assistant, Message(Role.TOOL, "x", toolCallId = "c1"), Message(Role.TOOL, "12:00", toolCallId = "c2"))
            assertEquals(LlmResponse.Text("ok"), provider.complete(complete))
            assertTrue(server.rejections.isEmpty())

            val missing = request(user, assistant, Message(Role.TOOL, "x", toolCallId = "c1"))
            val error = assertIs<LlmResponse.Error>(provider.complete(missing))
            assertIs<LlmError.InvalidResponse>(error.error)
            assertTrue(server.rejections.single().contains("never answered: c2"), server.rejections.toString())
        }
    }

    @Test
    fun `the server refuses the transcripts the old mapping could not express`() {
        StrictChatServer { StrictReply.Text("ok") }.use { server ->
            val provider = provider(server)

            // A result filed under an id no assistant message asked for.
            assertIs<LlmResponse.Error>(provider.complete(request(user, Message(Role.ASSISTANT, "Called tool echo"), Message(Role.TOOL, "x", toolCallId = "made_up"))))
            assertTrue(server.rejections.last().contains("must be a response to a preceding message with 'tool_calls'"), server.rejections.last())

            // A result for the wrong call.
            val assistant = Message(Role.ASSISTANT, "", toolCalls = listOf(ToolCallRequest("c1", "echo", "{}")))
            assertIs<LlmResponse.Error>(provider.complete(request(user, assistant, Message(Role.TOOL, "x", toolCallId = "c9"))))
            assertTrue(server.rejections.last().contains("does not match"), server.rejections.last())

            // Arguments that are not a JSON object.
            val broken = Message(Role.ASSISTANT, "", toolCalls = listOf(ToolCallRequest("c1", "echo", "{not json")))
            assertIs<LlmResponse.Error>(provider.complete(request(user, broken, Message(Role.TOOL, "x", toolCallId = "c1"))))
            assertTrue(server.rejections.last().contains("must hold a JSON object"), server.rejections.last())

            assertEquals(3, server.rejections.size)
            assertTrue(server.requests.isEmpty(), "none of them reached the script")
        }
    }

    @Test
    fun `a tool message from a caller without call ids is still sent as user text`() {
        StrictChatServer { StrictReply.Text("ok") }.use { server ->
            assertEquals(LlmResponse.Text("ok"), provider(server).complete(request(user, Message(Role.ASSISTANT, "Called tool echo"), Message(Role.TOOL, "exit code 0"))))
            assertEquals(listOf("system", "user", "assistant", "user"), server.requests.single().roles())
        }
    }

    @Test
    fun `malformed arguments reject the whole response, including its well-formed calls`() {
        // Cut off, a bare word as a value (which a lenient JSON parser lets through), and trailing text.
        for (malformed in listOf("""{"text": oops""", """{"text": oops}""", """{"text":"a"} and more""")) {
            val calls = listOf(StrictReply.Call("c1", "echo", """{"text":"fine"}"""), StrictReply.Call("c2", "echo", malformed))
            StrictChatServer { StrictReply.ToolCalls(calls) }.use { server ->
                val provider = provider(server)
                for (response in listOf(provider.complete(request(user)), provider.stream(request(user)) {})) {
                    val error = assertIs<LlmResponse.Error>(response, malformed).error
                    assertIs<LlmError.InvalidResponse>(error)
                    assertTrue(error.message.contains("Malformed tool_call arguments for 'echo'"), error.message)
                }
            }
        }
        StrictChatServer { StrictReply.ToolCalls(listOf(StrictReply.Call("c1", "echo", "[1,2]"))) }.use { server ->
            val error = assertIs<LlmResponse.Error>(provider(server).complete(request(user))).error
            assertTrue(error.message.contains("not a JSON object"), error.message)
        }
    }

    @Test
    fun `a server that sends no call id yields a call without one, and empty arguments mean an empty object`() {
        StrictChatServer { StrictReply.ToolCalls(listOf(StrictReply.Call(null, "clock", ""))) }.use { server ->
            for (response in listOf(provider(server).complete(request(user)), provider(server).stream(request(user)) {})) {
                val call = assertIs<LlmResponse.ToolCall>(response)
                assertNull(call.id)
                assertEquals("{}", call.argumentsJson)
                assertEquals(emptyMap(), call.input)
            }
        }
    }

    @Test
    fun `a tool's declared parameters are sent as its JSON schema`() {
        val typed = ToolSpec(
            name = "search",
            description = "Searches notes",
            parameterSchema = ToolParameterSchema(
                listOf(ToolParameter("query", ToolJsonType.STRING, "What to look for", required = true), ToolParameter("limit", ToolJsonType.NUMBER)),
                additionalProperties = false,
            ),
        )
        StrictChatServer { StrictReply.Text("ok") }.use { server ->
            provider(server).complete(request(user, tools = listOf(typed, echo)))
            val tools = server.requests.single().body["tools"] as JsonArray
            val parameters = ((tools[0] as JsonObject)["function"] as JsonObject)["parameters"] as JsonObject
            assertEquals(
                Json.parseToJsonElement(
                    """{"type":"object","properties":{"query":{"type":"string","description":"What to look for"},"limit":{"type":"number"}},"required":["query"],"additionalProperties":false}""",
                ),
                parameters,
            )
            assertEquals(Json.parseToJsonElement("""{"type":"object"}"""), ((tools[1] as JsonObject)["function"] as JsonObject)["parameters"])
        }
    }

    @Test
    fun `the transcript check itself accepts valid exchanges and names what is wrong with invalid ones`() {
        fun body(messages: String, tools: String? = null) =
            Json.parseToJsonElement("""{"model":"m","messages":$messages${tools?.let { ""","tools":$it""" }.orEmpty()}}""") as JsonObject
        val call = """{"id":"c1","type":"function","function":{"name":"echo","arguments":"{}"}}"""

        assertNull(StrictTranscript.problem(body("""[{"role":"user","content":"hi"}]""")))
        assertNull(
            StrictTranscript.problem(
                body("""[{"role":"user","content":"hi"},{"role":"assistant","content":null,"tool_calls":[$call]},{"role":"tool","tool_call_id":"c1","content":"x"},{"role":"assistant","content":"done"}]"""),
            ),
        )
        val invalid = mapOf(
            """[{"role":"user","content":"hi"},{"role":"tool","content":"x"}]""" to "tool_call_id",
            """[{"role":"user","content":"hi"},{"role":"assistant","content":null,"tool_calls":[$call]}]""" to "never answered",
            """[{"role":"user","content":"hi"},{"role":"assistant","content":null,"tool_calls":[$call]},{"role":"user","content":"and?"}]""" to "must be followed by tool messages",
            """[{"role":"assistant","content":null,"tool_calls":[$call]},{"role":"tool","tool_call_id":"c1","content":"x"},{"role":"tool","tool_call_id":"c1","content":"again"}]""" to "does not match",
            """[{"role":"assistant","content":null,"tool_calls":[{"type":"function","function":{"name":"echo","arguments":"{}"}}]}]""" to "id must be a non-empty string",
            """[{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"echo","arguments":{}}}]}]""" to "arguments must be a string",
            """[{"role":"assistant","content":null}]""" to "content must be a string when there are no tool_calls",
            """[{"role":"function","content":"x"}]""" to "not a valid role",
        )
        for ((messages, expected) in invalid) {
            val problem = assertNotNull(StrictTranscript.problem(body(messages)), "should be refused: $messages")
            assertTrue(problem.contains(expected), "$messages -> $problem")
        }
        assertTrue(StrictTranscript.problem(body("""[{"role":"user","content":"hi"}]""", tools = """[{"type":"function","function":{"name":"echo"}}]"""))!!.contains("parameters"))
    }
}
