package ai.droidcommand.llm.openai

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.ToolArgumentCodec
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
 * joined per call index into every tool call of the response, each with
 * its id, name and arguments.
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

    /**
     * Builds the final [LlmResponse] from streamed chunks, mirroring [parseResponse]'s rules (tool calls win over
     * text). Call fragments are joined per `index`. A fragment that carries a different `id` than the call already
     * collected at its index starts a new call instead of being glued onto the old one: some self-hosted servers
     * send every call whole with the same index.
     */
    private inner class StreamAccumulator(private val onTextDelta: (String) -> Unit) {
        private inner class PendingCall(var id: String?) {
            val name = StringBuilder()
            val arguments = StringBuilder()
        }

        private val text = StringBuilder()
        private val calls = mutableListOf<PendingCall>()
        private val callAtIndex = mutableMapOf<Int, PendingCall>()
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
            delta.toolCalls?.forEach { fragment ->
                val id = fragment.id?.takeIf { it.isNotEmpty() }
                var call = callAtIndex[fragment.index]
                if (call == null || (id != null && call.id != null && call.id != id)) {
                    call = PendingCall(id).also {
                        calls += it
                        callAtIndex[fragment.index] = it
                    }
                } else if (call.id == null) {
                    call.id = id
                }
                fragment.function?.name?.let { call.name.append(it) }
                fragment.function?.arguments?.let { call.arguments.append(it) }
            }
        }

        fun result(): LlmResponse {
            error?.let { return LlmResponse.Error(it) }
            if (requireStreamEnd && !done && !finished) {
                return LlmResponse.Error(LlmError.Network(STREAM_ENDED_EARLY))
            }
            if (calls.isNotEmpty()) {
                return toolCalls(calls.map { Triple(it.id, it.name.toString(), it.arguments.toString()) }, text.toString())
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

        val calls = message.toolCalls.orEmpty()
        if (calls.isNotEmpty()) {
            return toolCalls(calls.map { Triple(it.id, it.function.name, it.function.arguments) }, message.content.orEmpty())
        }

        val content = message.content
            ?: return LlmResponse.Error(LlmError.InvalidResponse("Message had neither content nor tool_calls"))
        return LlmResponse.Text(content)
    }

    /**
     * Every call of one response as (id, name, raw arguments), in order. The whole response is rejected if any call
     * has no name or arguments that are not a JSON object: the API documents that a model "does not always generate
     * valid JSON", and a caller must never run the well-formed half of a response it cannot answer in full.
     */
    private fun toolCalls(raw: List<Triple<String?, String, String>>, text: String): LlmResponse {
        val parsed = raw.map { (id, name, arguments) ->
            if (name.isBlank()) return LlmResponse.Error(LlmError.InvalidResponse("tool_call had no function name"))
            val argumentsJson = arguments.ifBlank { "{}" }
            val json = ToolArgumentCodec.parseStrictObject(argumentsJson)
                ?: return LlmResponse.Error(LlmError.InvalidResponse("Malformed tool_call arguments for '$name': not a JSON object"))
            LlmResponse.ToolCall(name, json.flatten(), id = id?.takeIf { it.isNotEmpty() }, argumentsJson = argumentsJson)
        }
        return LlmResponse.ToolCall.of(parsed, text)
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
 * OpenAI's chat message roles (`system`/`user`/`assistant`/`tool`) map directly from [Role].
 *
 * An assistant [Message] that asked for tools is sent with its `tool_calls` (id, name and the arguments text exactly
 * as received), and a [Role.TOOL] message that names the call it answers is sent as a `tool` message with that
 * `tool_call_id`. That is the shape the API requires: every `tool` message must follow an assistant message whose
 * `tool_calls` contains its id.
 *
 * A [Role.TOOL] message with no `toolCallId` (written by a caller that predates call ids, such as
 * `core-agent.ObjectiveEngine`) cannot be sent as `tool` without inventing an id, so it is still sent as `user`,
 * as before.
 */
private fun toOpenAiMessages(systemPrompt: String?, messages: List<Message>): List<OpenAiChatMessage> {
    val mapped = mutableListOf<OpenAiChatMessage>()
    systemPrompt?.let { mapped += OpenAiChatMessage("system", it) }

    for (message in messages) {
        mapped += when (message.role) {
            Role.SYSTEM -> OpenAiChatMessage("system", message.content)
            Role.USER -> OpenAiChatMessage("user", message.content)
            Role.ASSISTANT ->
                if (message.toolCalls.isEmpty()) {
                    OpenAiChatMessage("assistant", message.content)
                } else {
                    OpenAiChatMessage(
                        role = "assistant",
                        content = message.content.ifEmpty { null },
                        toolCalls = message.toolCalls.map {
                            OpenAiToolCall(id = it.id, type = "function", function = OpenAiFunctionCall(it.name, it.argumentsJson))
                        },
                    )
                }
            Role.TOOL ->
                if (message.toolCallId == null) {
                    OpenAiChatMessage("user", message.content)
                } else {
                    OpenAiChatMessage("tool", message.content, toolCallId = message.toolCallId)
                }
        }
    }

    return mapped
}

private fun ToolSpec.toOpenAiToolDefinition(): OpenAiToolDefinition =
    OpenAiToolDefinition(function = OpenAiFunctionDefinition(name = name, description = description, parameters = parametersSchema()))

/** JSON Schema for the tool's arguments: its declared parameters when it has a schema, any object otherwise. */
private fun ToolSpec.parametersSchema(): JsonObject {
    val schema = parameterSchema ?: return buildJsonObject { put("type", JsonPrimitive("object")) }
    return buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                for (parameter in schema.parameters) {
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
        put("required", JsonArray(schema.parameters.filter { it.required }.map { JsonPrimitive(it.name) }))
        if (!schema.additionalProperties) put("additionalProperties", JsonPrimitive(false))
    }
}

/** Same lossy-but-honest flattening as `core-llm-anthropic`'s tool_use.input handling. */
private fun JsonObject.flatten(): Map<String, String> = mapValues { (_, value) -> value.flattenToString() }

private fun JsonElement.flattenToString(): String = when (this) {
    is JsonPrimitive -> content
    else -> toString()
}
