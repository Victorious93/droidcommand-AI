package ai.droidcommand.llm.openai

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmProvider
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.ResponseFormat
import ai.droidcommand.llm.StreamingLlmProvider
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.RemoteClient
import ai.droidcommand.remote.RemoteEndpoint
import ai.droidcommand.remote.RemoteResult
import ai.droidcommand.remote.ServerSentEventParser
import ai.droidcommand.remote.isTimeoutFailure
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A real [LlmProvider] backed by the OpenAI Chat Completions API shape —
 * the shape OpenAI itself serves and the one most self-hosted
 * "OpenAI-compatible" servers implement too, so [config].endpoint pointing
 * at a local server (rather than [DEFAULT_BASE_URL]) is the common case,
 * not an edge case. Built on core-remote's [RemoteClient], the same
 * transport already proven in `core-llm-anthropic`.
 *
 * Unlike `core-llm-anthropic.AnthropicLlmProvider`, this provider *does*
 * use [RemoteClient]'s built-in bearer-token auth: OpenAI's own API and
 * every OpenAI-compatible server this was modeled against accept
 * `Authorization: Bearer <key>` for real. [config]'s `authToken` is still
 * read fresh on every call rather than cached. Unlike Anthropic's API, a
 * missing key is not rejected up front here — many self-hosted
 * OpenAI-compatible servers (a local Ollama/vLLM instance, for example)
 * accept requests with no key at all, so failing closed on an absent key
 * would be dishonest about what this shape actually requires.
 *
 * [stream] (ROADMAP-058) sends the same request with `"stream": true` and
 * reads the server-sent `data:` chunks until `[DONE]`: `delta.content`
 * goes to the caller as it arrives, and `delta.tool_calls` fragments are
 * accumulated into the first tool call's name and arguments.
 *
 * Structured output (ROADMAP-060) maps [LlmRequest.responseFormat] onto
 * the API's own `response_format`: [ResponseFormat.Json] becomes
 * `json_object` mode (which OpenAI rejects unless the word "JSON" appears
 * somewhere in the messages, so a caller's prompt must say it), and
 * [ResponseFormat.Schema] becomes `json_schema`. Self-hosted
 * OpenAI-compatible servers vary in which of the two they honor. The
 * returned text is checked to be a JSON object either way.
 */
class OpenAiLlmProvider(
    override val config: LlmConfig,
    transport: HttpTransport,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    requireHttps: Boolean = true,
) : StreamingLlmProvider {
    private val remoteClient = RemoteClient(
        RemoteEndpoint(config.endpoint ?: DEFAULT_BASE_URL, requireHttps = requireHttps),
        transport,
        authToken = config.authToken,
    )
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    override fun complete(request: LlmRequest): LlmResponse {
        val body = encodeRequest(request, stream = false).getOrElse { return it.asError() }

        val result = remoteClient.send(
            path = "v1/chat/completions",
            method = "POST",
            headers = mapOf("content-type" to "application/json"),
            body = body,
            retryPolicy = retryPolicy,
        )

        return when (result) {
            is RemoteResult.Success -> parseResponse(result.body).requireJsonObjectIf(request.responseFormat != null)
            is RemoteResult.Failure -> LlmResponse.Error(classify(result))
        }
    }

    override fun stream(request: LlmRequest, onTextDelta: (String) -> Unit): LlmResponse {
        val body = encodeRequest(request, stream = true).getOrElse { return it.asError() }

        val accumulator = StreamAccumulator(onTextDelta)
        val parser = ServerSentEventParser { event -> accumulator.accept(event.data) }
        val result = remoteClient.sendStreaming(
            path = "v1/chat/completions",
            method = "POST",
            headers = mapOf("content-type" to "application/json"),
            body = body,
            retryPolicy = retryPolicy,
            onLine = parser::feed,
        )
        parser.finish()

        return when (result) {
            is RemoteResult.Success -> accumulator.result().requireJsonObjectIf(request.responseFormat != null)
            is RemoteResult.Failure -> LlmResponse.Error(classify(result))
        }
    }

    private fun encodeRequest(request: LlmRequest, stream: Boolean): Result<String> {
        val format = request.responseFormat
        if (format != null && request.tools.isNotEmpty()) {
            return Result.failure(IllegalArgumentException("responseFormat cannot be combined with tools"))
        }
        val responseFormat = format?.let {
            toOpenAiResponseFormat(it) ?: return Result.failure(IllegalArgumentException("responseFormat schema is not a JSON object"))
        }

        val chatRequest = OpenAiChatRequest(
            model = config.model,
            messages = toOpenAiMessages(request.systemPrompt, request.messages),
            temperature = request.temperature ?: config.temperature,
            maxTokens = request.maxOutputTokens ?: config.maxOutputTokens,
            tools = request.tools.takeIf { it.isNotEmpty() }?.map { it.toOpenAiToolDefinition() },
            stream = stream.takeIf { it },
            responseFormat = responseFormat,
        )

        return try {
            Result.success(json.encodeToString(OpenAiChatRequest.serializer(), chatRequest))
        } catch (e: SerializationException) {
            Result.failure(e)
        }
    }

    private fun Throwable.asError(): LlmResponse = LlmResponse.Error(
        LlmError.InvalidResponse(if (this is IllegalArgumentException) "Invalid request: $message" else "Failed to encode request: $message"),
    )

    private fun toOpenAiResponseFormat(format: ResponseFormat): JsonObject? = when (format) {
        is ResponseFormat.Json -> buildJsonObject { put("type", JsonPrimitive("json_object")) }
        is ResponseFormat.Schema -> {
            val schema = try {
                json.parseToJsonElement(format.schema) as? JsonObject
            } catch (e: SerializationException) {
                null
            }
            schema?.let {
                buildJsonObject {
                    put("type", JsonPrimitive("json_schema"))
                    put(
                        "json_schema",
                        buildJsonObject {
                            put("name", JsonPrimitive(format.name))
                            put("schema", it)
                            put("strict", JsonPrimitive(format.strict))
                        },
                    )
                }
            }
        }
    }

    /** With structured output requested, a text answer that isn't a JSON object is an error, not a success. */
    private fun LlmResponse.requireJsonObjectIf(structured: Boolean): LlmResponse {
        if (!structured || this !is LlmResponse.Text) return this
        val parsed = try {
            json.parseToJsonElement(content) as? JsonObject
        } catch (e: SerializationException) {
            null
        }
        return if (parsed != null) this else LlmResponse.Error(LlmError.InvalidResponse("Structured output requested, but the response was not a JSON object"))
    }

    /** Builds the final [LlmResponse] from streamed chunks, mirroring [parseResponse]'s rules (a tool call wins over text). */
    private inner class StreamAccumulator(private val onTextDelta: (String) -> Unit) {
        private val text = StringBuilder()
        private val toolNames = sortedMapOf<Int, StringBuilder>()
        private val toolArguments = mutableMapOf<Int, StringBuilder>()
        private var sawChoice = false
        private var done = false
        private var error: LlmError? = null

        fun accept(data: String) {
            if (done || error != null) return
            if (data.trim() == "[DONE]") {
                done = true
                return
            }
            val chunk = try {
                json.decodeFromString(OpenAiStreamChunk.serializer(), data)
            } catch (e: SerializationException) {
                error = LlmError.InvalidResponse("Malformed stream chunk: ${e.message}")
                return
            }
            chunk.error?.let {
                error = LlmError.ModelUnavailable("Stream error ${it.type ?: "unknown"}: ${it.message ?: ""}")
                return
            }
            val delta = chunk.choices.firstOrNull()?.also { sawChoice = true }?.delta ?: return
            delta.content?.takeIf { it.isNotEmpty() }?.let {
                text.append(it)
                onTextDelta(it)
            }
            delta.toolCalls?.forEach { call ->
                call.function?.name?.let { toolNames.getOrPut(call.index) { StringBuilder() }.append(it) }
                call.function?.arguments?.let { toolArguments.getOrPut(call.index) { StringBuilder() }.append(it) }
            }
        }

        fun result(): LlmResponse {
            error?.let { return LlmResponse.Error(it) }
            toolNames.entries.firstOrNull()?.let { (index, name) ->
                val raw = toolArguments[index]?.toString().orEmpty().ifBlank { "{}" }
                val arguments = try {
                    json.parseToJsonElement(raw) as? JsonObject
                        ?: return LlmResponse.Error(LlmError.InvalidResponse("tool_call arguments was not a JSON object"))
                } catch (e: SerializationException) {
                    return LlmResponse.Error(LlmError.InvalidResponse("Malformed tool_call arguments: ${e.message}"))
                }
                return LlmResponse.ToolCall(name.toString(), arguments.flatten())
            }
            if (!sawChoice) return LlmResponse.Error(LlmError.InvalidResponse("Stream contained no choices"))
            return LlmResponse.Text(text.toString())
        }
    }

    private fun parseResponse(body: String): LlmResponse {
        val decoded = try {
            json.decodeFromString(OpenAiChatResponse.serializer(), body)
        } catch (e: SerializationException) {
            return LlmResponse.Error(LlmError.InvalidResponse("Malformed response body: ${e.message}"))
        }

        val message = decoded.choices.firstOrNull()?.message
            ?: return LlmResponse.Error(LlmError.InvalidResponse("Response contained no choices"))

        val toolCall = message.toolCalls?.firstOrNull()
        if (toolCall != null) {
            val arguments = try {
                json.parseToJsonElement(toolCall.function.arguments) as? JsonObject
                    ?: return LlmResponse.Error(LlmError.InvalidResponse("tool_call arguments was not a JSON object"))
            } catch (e: SerializationException) {
                return LlmResponse.Error(LlmError.InvalidResponse("Malformed tool_call arguments: ${e.message}"))
            }
            return LlmResponse.ToolCall(toolCall.function.name, arguments.flatten())
        }

        val content = message.content
            ?: return LlmResponse.Error(LlmError.InvalidResponse("Message had neither content nor tool_calls"))
        return LlmResponse.Text(content)
    }

    private fun classify(failure: RemoteResult.Failure): LlmError = when {
        failure.statusCode == 401 || failure.statusCode == 403 -> LlmError.Authentication(failure.reason)
        failure.statusCode == 429 || failure.statusCode in 500..599 -> LlmError.ModelUnavailable(failure.reason)
        failure.statusCode != null -> LlmError.InvalidResponse(failure.reason)
        failure.cause.isTimeoutFailure() -> LlmError.Timeout(failure.reason)
        else -> LlmError.Network(failure.reason)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com"
    }
}

/**
 * OpenAI's chat message roles (`system`/`user`/`assistant`/`tool`) map
 * directly from [Role] except [Role.TOOL]: OpenAI's native `tool` role
 * requires a `tool_call_id` referencing a preceding assistant message's
 * `tool_calls[].id`, which [Message] does not carry, so — matching
 * `core-llm-anthropic`'s documented choice for the same underlying gap —
 * it is mapped to `user` instead of fabricating an id.
 */
private fun toOpenAiMessages(systemPrompt: String?, messages: List<Message>): List<OpenAiChatMessage> {
    val mapped = mutableListOf<OpenAiChatMessage>()
    systemPrompt?.let { mapped += OpenAiChatMessage("system", it) }

    for (message in messages) {
        val role = when (message.role) {
            Role.SYSTEM -> "system"
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
            Role.TOOL -> "user"
        }
        mapped += OpenAiChatMessage(role, message.content)
    }

    return mapped
}

private fun ToolSpec.toOpenAiToolDefinition(): OpenAiToolDefinition =
    OpenAiToolDefinition(function = OpenAiFunctionDefinition(name = name, description = description, parameters = openObjectSchema()))

private fun openObjectSchema(): JsonObject = buildJsonObject { put("type", JsonPrimitive("object")) }

/** Same lossy-but-honest flattening as `core-llm-anthropic`'s tool_use.input handling. */
private fun JsonObject.flatten(): Map<String, String> = mapValues { (_, value) -> value.flattenToString() }

private fun JsonElement.flattenToString(): String = when (this) {
    is JsonPrimitive -> content
    else -> toString()
}
