package ai.droidcommand.llm.openai

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.ToolParameterSchema
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
import kotlinx.serialization.json.JsonArray
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
    /**
     * When true, a stream that closes with neither a `finish_reason` nor `data: [DONE]` is reported as
     * [LlmError.Network] instead of being returned as a complete reply. Off by default, so existing hosted
     * providers behave as before; a self-hosted server that can be switched off mid-reply turns it on.
     */
    private val requireStreamEnd: Boolean = false,
    /**
     * When true, tool calls are handled in OpenAI's native structured form instead of the lossy default: every call
     * of a reply is returned (the first, with the rest in [LlmResponse.ToolCall.more]) with the server's own call id
     * and exact argument text; a call that cannot be run (no name, arguments that are not a JSON object) is returned
     * with [LlmResponse.ToolCall.problem] rather than failing the whole reply; messages that carry
     * [Message.toolCalls] / [Message.toolCallId] are sent as assistant `tool_calls` and `tool` results paired by id;
     * and a tool's declared parameter schema is sent. Off by default, so existing callers are unaffected.
     */
    private val structuredToolCalls: Boolean = false,
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
            messages = toOpenAiMessages(request.systemPrompt, request.messages, structuredToolCalls),
            temperature = request.temperature ?: config.temperature,
            maxTokens = request.maxOutputTokens ?: config.maxOutputTokens,
            tools = request.tools.takeIf { it.isNotEmpty() }?.map { it.toOpenAiToolDefinition(structuredToolCalls) },
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
        private val toolIds = mutableMapOf<Int, String>()
        private var sawChoice = false
        private var done = false
        private var finished = false
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
            val choice = chunk.choices.firstOrNull()?.also { sawChoice = true } ?: return
            if (choice.finishReason != null) finished = true
            val delta = choice.delta ?: return
            delta.content?.takeIf { it.isNotEmpty() }?.let {
                text.append(it)
                onTextDelta(it)
            }
            delta.toolCalls?.forEach { call ->
                // The id arrives once, on the fragment that opens the call; later fragments carry only the index.
                call.id?.takeIf { it.isNotEmpty() }?.let { toolIds.putIfAbsent(call.index, it) }
                if (structuredToolCalls) toolArguments.getOrPut(call.index) { StringBuilder() }
                if (structuredToolCalls) toolNames.getOrPut(call.index) { StringBuilder() }
                call.function?.name?.let { toolNames.getOrPut(call.index) { StringBuilder() }.append(it) }
                call.function?.arguments?.let { toolArguments.getOrPut(call.index) { StringBuilder() }.append(it) }
            }
        }

        fun result(): LlmResponse {
            error?.let { return LlmResponse.Error(it) }
            if (requireStreamEnd && !done && !finished) {
                return LlmResponse.Error(LlmError.Network(STREAM_ENDED_EARLY))
            }
            if (structuredToolCalls && toolNames.isNotEmpty()) {
                val calls = toolNames.entries.map { (index, name) ->
                    structuredCall(toolIds[index], name.toString(), toolArguments[index]?.toString().orEmpty())
                }
                return calls.first().copy(more = calls.drop(1))
            }
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

        if (structuredToolCalls && !message.toolCalls.isNullOrEmpty()) {
            val calls = message.toolCalls.map { structuredCall(it.id, it.function.name, it.function.arguments) }
            return calls.first().copy(more = calls.drop(1))
        }
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

    /** One call in structured form. Never throws and never drops a call: what cannot be run is marked with a problem. */
    private fun structuredCall(id: String?, name: String, rawArguments: String): LlmResponse.ToolCall {
        val raw = rawArguments.ifBlank { "{}" }
        val parsed = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (e: SerializationException) {
            null
        }
        val problem = when {
            name.isBlank() -> "the call named no tool"
            parsed == null -> "its arguments were not a JSON object"
            else -> null
        }
        return LlmResponse.ToolCall(
            toolName = name,
            input = parsed?.flatten().orEmpty(),
            id = id?.takeIf { it.isNotBlank() },
            argumentsJson = raw,
            problem = problem,
        )
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

        /** [LlmError.Network] message for a stream cut off before its end marker (see `requireStreamEnd`). */
        const val STREAM_ENDED_EARLY = "The response stream ended before the reply was complete"
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
private fun toOpenAiMessages(systemPrompt: String?, messages: List<Message>, structured: Boolean = false): List<OpenAiChatMessage> {
    val mapped = mutableListOf<OpenAiChatMessage>()
    systemPrompt?.let { mapped += OpenAiChatMessage("system", it) }

    for (message in messages) {
        // Structured form: only for a message that really carries the pairing, so a plain transcript is sent as before.
        if (structured && message.role == Role.ASSISTANT && message.toolCalls.isNotEmpty()) {
            mapped += OpenAiChatMessage(
                role = "assistant",
                content = message.content,
                toolCalls = message.toolCalls.map { OpenAiToolCall(id = it.id, type = "function", function = OpenAiFunctionCall(it.name, it.argumentsJson)) },
            )
            continue
        }
        if (structured && message.role == Role.TOOL && message.toolCallId != null) {
            mapped += OpenAiChatMessage(role = "tool", content = message.content, toolCallId = message.toolCallId)
            continue
        }
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

private fun ToolSpec.toOpenAiToolDefinition(structured: Boolean = false): OpenAiToolDefinition =
    OpenAiToolDefinition(
        function = OpenAiFunctionDefinition(
            name = name,
            description = description,
            parameters = parameterSchema?.takeIf { structured }?.toJsonSchema() ?: openObjectSchema(),
        ),
    )

private fun openObjectSchema(): JsonObject = buildJsonObject { put("type", JsonPrimitive("object")) }

/** The declared parameters as a JSON Schema object, so a model is told the argument names and types. */
private fun ToolParameterSchema.toJsonSchema(): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put(
        "properties",
        buildJsonObject {
            for (parameter in parameters) {
                put(
                    parameter.name,
                    buildJsonObject {
                        put("type", JsonPrimitive(parameter.type.name.lowercase()))
                        if (parameter.description.isNotBlank()) put("description", JsonPrimitive(parameter.description))
                    },
                )
            }
        },
    )
    val required = parameters.filter { it.required }.map { JsonPrimitive(it.name) }
    if (required.isNotEmpty()) put("required", JsonArray(required))
    if (!additionalProperties) put("additionalProperties", JsonPrimitive(false))
}

/** Same lossy-but-honest flattening as `core-llm-anthropic`'s tool_use.input handling. */
private fun JsonObject.flatten(): Map<String, String> = mapValues { (_, value) -> value.flattenToString() }

private fun JsonElement.flattenToString(): String = when (this) {
    is JsonPrimitive -> content
    else -> toString()
}
