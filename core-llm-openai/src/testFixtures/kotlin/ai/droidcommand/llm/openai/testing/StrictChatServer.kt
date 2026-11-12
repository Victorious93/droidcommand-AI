package ai.droidcommand.llm.openai.testing

import ai.droidcommand.agent.ToolArgumentCodec
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** What the controlled server answers one accepted request with. */
sealed class StrictReply {
    data class Text(val text: String) : StrictReply()

    /** [text] is assistant text sent along with the calls. With [sameIndex], every streamed call is sent whole at index 0. */
    data class ToolCalls(val calls: List<Call>, val text: String? = null, val sameIndex: Boolean = false) : StrictReply()

    /** An HTTP error with [body] as the response body. */
    data class Http(val status: Int, val body: String) : StrictReply()

    data class Call(val id: String?, val name: String, val arguments: String)
}

/** One accepted request: the parsed body, and the views tests assert on. */
class StrictRequest(val body: JsonObject) {
    val messages: List<JsonObject> = (body["messages"] as JsonArray).map { it as JsonObject }
    val toolNames: List<String> =
        (body["tools"] as? JsonArray).orEmpty().map { ((it as JsonObject)["function"] as JsonObject)["name"]!!.let { n -> (n as JsonPrimitive).content } }
    val stream: Boolean = (body["stream"] as? JsonPrimitive)?.content == "true"

    fun roles(): List<String> = messages.map { (it["role"] as JsonPrimitive).content }
}

/**
 * A controlled `POST /v1/chat/completions` server (loopback, JDK `HttpServer`) for tests. Not a model: [script]
 * decides each reply. What makes it worth using is that it **checks the request first** and answers HTTP 400, the way
 * the real API does, when the conversation is not a valid tool transcript:
 *
 * - every message has a known `role`; `user`/`system` content is a string;
 * - an `assistant` message has string content, or `tool_calls` (then content may be null or absent);
 * - each `tool_calls` entry has a non-empty string `id` unique in its message, `type` `"function"`, a
 *   non-empty `function.name` and `function.arguments` that is a **string** holding a JSON object;
 * - a `tool` message has string `content` and a `tool_call_id` naming a call of the assistant message directly
 *   before its run of `tool` messages, answered at most once;
 * - every call is answered before the next non-`tool` message and before the end of the conversation;
 * - `tools`, when present, is a non-empty list of `{"type":"function","function":{"name", "parameters": object}}`.
 *
 * These are the Chat Completions rules from OpenAI's API reference (`ChatCompletionRequestAssistantMessage`,
 * `ChatCompletionMessageToolCall`: `id`, `type`, `function` required; `ChatCompletionRequestToolMessage`: `role`,
 * `content`, `tool_call_id` required) plus the ordering rule the API enforces. A test that passes against this
 * server has therefore sent a valid exchange; [rejections] lists every request it refused and why.
 *
 * Streaming requests get server-sent chunks: a tool call's id and name in its first fragment, its arguments split
 * across later fragments, fragments of different calls interleaved by `index`, then `finish_reason` and `[DONE]`.
 */
class StrictChatServer(private val script: (StrictRequest) -> StrictReply) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "strict-chat-server").apply { isDaemon = true } }

    val requests = CopyOnWriteArrayList<StrictRequest>()
    val rejections = CopyOnWriteArrayList<String>()

    val port: Int get() = server.address.port
    val baseUrl: String get() = "http://127.0.0.1:$port"

    init {
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange -> handle(exchange) }
        server.start()
    }

    /** Serves another path, for the metadata endpoints a test needs next to chat. */
    fun route(path: String, status: Int = 200, body: () -> String) {
        server.createContext(path) { exchange ->
            exchange.requestBody.readBytes()
            respond(exchange, status, body(), "application/json")
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val text = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val body = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            val problem = if (body == null) "body is not a JSON object" else StrictTranscript.problem(body)
            if (body == null || problem != null) {
                rejections += problem!!
                respond(exchange, 400, error(problem), "application/json")
                return
            }
            val request = StrictRequest(body)
            requests += request
            when (val reply = script(request)) {
                is StrictReply.Http -> respond(exchange, reply.status, reply.body, "application/json")
                is StrictReply.Text ->
                    if (request.stream) {
                        respond(exchange, 200, sse(textChunks(reply.text)), "text/event-stream")
                    } else {
                        respond(exchange, 200, completion(reply.text, emptyList()), "application/json")
                    }
                is StrictReply.ToolCalls ->
                    if (request.stream) {
                        respond(exchange, 200, sse(callChunks(reply)), "text/event-stream")
                    } else {
                        respond(exchange, 200, completion(reply.text, reply.calls), "application/json")
                    }
            }
        } catch (e: Exception) {
            runCatching { respond(exchange, 500, error("test server script failed: ${e.message}"), "application/json") }
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, type: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun error(message: String): String =
        buildJsonObject {
            put(
                "error",
                buildJsonObject {
                    put("message", JsonPrimitive(message))
                    put("type", JsonPrimitive("invalid_request_error"))
                },
            )
        }.toString()

    private fun completion(text: String?, calls: List<StrictReply.Call>): String = buildJsonObject {
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", JsonPrimitive(0))
                        put(
                            "message",
                            buildJsonObject {
                                put("role", JsonPrimitive("assistant"))
                                put("content", text?.let(::JsonPrimitive) ?: JsonNull)
                                if (calls.isNotEmpty()) {
                                    put(
                                        "tool_calls",
                                        buildJsonArray {
                                            for (call in calls) {
                                                add(
                                                    buildJsonObject {
                                                        call.id?.let { put("id", JsonPrimitive(it)) }
                                                        put("type", JsonPrimitive("function"))
                                                        put(
                                                            "function",
                                                            buildJsonObject {
                                                                put("name", JsonPrimitive(call.name))
                                                                put("arguments", JsonPrimitive(call.arguments))
                                                            },
                                                        )
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        )
                        put("finish_reason", JsonPrimitive(if (calls.isEmpty()) "stop" else "tool_calls"))
                    },
                )
            },
        )
    }.toString()

    private fun sse(chunks: List<String>): String = (chunks + "[DONE]").joinToString("") { "data: $it\n\n" }

    private fun chunk(delta: JsonObject, finish: String? = null): String = buildJsonObject {
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", JsonPrimitive(0))
                        put("delta", delta)
                        put("finish_reason", finish?.let(::JsonPrimitive) ?: JsonNull)
                    },
                )
            },
        )
    }.toString()

    private fun textChunks(text: String): List<String> =
        listOf(chunk(buildJsonObject { put("role", JsonPrimitive("assistant")) })) +
            text.chunked(TEXT_PIECE).map { chunk(buildJsonObject { put("content", JsonPrimitive(it)) }) } +
            chunk(JsonObject(emptyMap()), "stop")

    private fun callChunks(reply: StrictReply.ToolCalls): List<String> {
        val out = mutableListOf(chunk(buildJsonObject { put("role", JsonPrimitive("assistant")) }))
        reply.text?.chunked(TEXT_PIECE)?.forEach { out += chunk(buildJsonObject { put("content", JsonPrimitive(it)) }) }

        fun fragment(index: Int, id: String?, name: String?, arguments: String) = chunk(
            buildJsonObject {
                put(
                    "tool_calls",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("index", JsonPrimitive(index))
                                id?.let { put("id", JsonPrimitive(it)) }
                                if (name != null) put("type", JsonPrimitive("function"))
                                put(
                                    "function",
                                    buildJsonObject {
                                        name?.let { put("name", JsonPrimitive(it)) }
                                        put("arguments", JsonPrimitive(arguments))
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        if (reply.sameIndex) {
            reply.calls.forEach { out += fragment(0, it.id, it.name, it.arguments) }
        } else {
            reply.calls.forEachIndexed { index, call -> out += fragment(index, call.id, call.name, "") }
            // Arguments in small pieces, one piece per call in turn, so fragments of different calls interleave.
            val pieces = reply.calls.map { it.arguments.chunked(ARGUMENT_PIECE) }
            for (round in 0 until (pieces.maxOfOrNull { it.size } ?: 0)) {
                pieces.forEachIndexed { index, list -> list.getOrNull(round)?.let { out += fragment(index, null, null, it) } }
            }
        }
        out += chunk(JsonObject(emptyMap()), "tool_calls")
        return out
    }

    private companion object {
        const val TEXT_PIECE = 4
        const val ARGUMENT_PIECE = 3
    }
}

/** The transcript rules [StrictChatServer] enforces, usable on their own. */
object StrictTranscript {
    private val ROLES = setOf("system", "developer", "user", "assistant", "tool")

    /** Null when [body] is a valid Chat Completions request as far as its messages and tools go; otherwise what is wrong. */
    fun problem(body: JsonObject): String? {
        if ((body["model"] as? JsonPrimitive)?.isString != true) return "model must be a string"
        val messages = body["messages"] as? JsonArray ?: return "messages must be an array"
        if (messages.isEmpty()) return "messages must not be empty"
        body["tools"]?.let { tools -> toolsProblem(tools)?.let { return it } }

        var open = LinkedHashSet<String>() // calls of the last assistant message not answered yet
        var answering = false // inside the run of tool messages that follows that assistant message
        for ((i, element) in messages.withIndex()) {
            val message = element as? JsonObject ?: return "messages[$i] must be an object"
            val role = (message["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return "messages[$i].role must be a string"
            if (role !in ROLES) return "messages[$i].role '$role' is not a valid role"
            if (role != "tool") {
                if (open.isNotEmpty()) {
                    return "messages[$i]: an assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'; not answered: ${open.joinToString()}"
                }
                answering = false
            }
            when (role) {
                "assistant" -> {
                    val calls = message["tool_calls"]
                    if (calls == null || calls is JsonNull) {
                        if (!isString(message["content"])) return "messages[$i]: assistant content must be a string when there are no tool_calls"
                    } else {
                        val content = message["content"]
                        if (content != null && content !is JsonNull && !isString(content)) return "messages[$i]: assistant content must be a string or null"
                        val list = calls as? JsonArray ?: return "messages[$i].tool_calls must be an array"
                        if (list.isEmpty()) return "messages[$i].tool_calls must not be empty"
                        open = LinkedHashSet()
                        for ((j, c) in list.withIndex()) {
                            val at = "messages[$i].tool_calls[$j]"
                            val call = c as? JsonObject ?: return "$at must be an object"
                            val id = (call["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                            if (id.isNullOrEmpty()) return "$at.id must be a non-empty string"
                            if (id in open) return "$at.id '$id' is used twice in one message"
                            if ((call["type"] as? JsonPrimitive)?.content != "function") return "$at.type must be 'function'"
                            val function = call["function"] as? JsonObject ?: return "$at.function must be an object"
                            if ((function["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.isNullOrEmpty()) return "$at.function.name must be a non-empty string"
                            val arguments = (function["arguments"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                                ?: return "$at.function.arguments must be a string"
                            if (ToolArgumentCodec.parseStrictObject(arguments) == null) {
                                return "$at.function.arguments must hold a JSON object"
                            }
                            open += id
                        }
                        answering = true
                    }
                }
                "tool" -> {
                    if (!isString(message["content"])) return "messages[$i]: tool content must be a string"
                    val id = (message["tool_call_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: return "messages[$i]: tool messages need a string 'tool_call_id'"
                    if (!answering) return "messages[$i]: messages with role 'tool' must be a response to a preceding message with 'tool_calls'"
                    if (!open.remove(id)) return "messages[$i]: tool_call_id '$id' does not match an unanswered call of the preceding assistant message"
                }
                else -> if (!isString(message["content"])) return "messages[$i]: $role content must be a string"
            }
        }
        if (open.isNotEmpty()) return "the conversation ends with tool calls that were never answered: ${open.joinToString()}"
        return null
    }

    private fun toolsProblem(tools: JsonElement): String? {
        val list = tools as? JsonArray ?: return "tools must be an array"
        if (list.isEmpty()) return "tools must not be empty when present"
        for ((i, t) in list.withIndex()) {
            val tool = t as? JsonObject ?: return "tools[$i] must be an object"
            if ((tool["type"] as? JsonPrimitive)?.content != "function") return "tools[$i].type must be 'function'"
            val function = tool["function"] as? JsonObject ?: return "tools[$i].function must be an object"
            if ((function["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.isNullOrEmpty()) return "tools[$i].function.name must be a non-empty string"
            if (function["parameters"] !is JsonObject) return "tools[$i].function.parameters must be an object"
        }
        return null
    }

    private fun isString(element: JsonElement?): Boolean = (element as? JsonPrimitive)?.isString == true
}
