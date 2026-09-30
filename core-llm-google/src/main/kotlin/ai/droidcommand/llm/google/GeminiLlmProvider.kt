package ai.droidcommand.llm.google

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.ResponseFormat
import ai.droidcommand.llm.StreamingLlmProvider
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.RemoteClient
import ai.droidcommand.remote.RemoteEndpoint
import ai.droidcommand.remote.RemoteResult
import ai.droidcommand.remote.ServerSentEventParser
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.http.HttpTimeoutException

/**
 * A real [ai.droidcommand.llm.LlmProvider] backed by Google's Gemini
 * `generateContent` API (`v1beta/models/{model}:generateContent`), built on
 * core-remote's [RemoteClient] — the same transport already proven in
 * `core-llm-anthropic` and `core-llm-openai`.
 *
 * Gemini authenticates with an `x-goog-api-key` header, not the
 * `Authorization: Bearer` that [RemoteClient] would add from an `authToken`,
 * so the key is injected as an explicit request header here instead (same
 * approach as `AnthropicLlmProvider`'s `x-api-key`). A missing key is
 * rejected up front — unlike OpenAI-compatible self-hosted servers, Gemini's
 * production endpoint always requires one.
 *
 * [stream] sends to `:streamGenerateContent?alt=sse`. Each SSE event carries
 * a complete [GeminiResponse] (not a delta); text parts are concatenated
 * and forwarded to [onTextDelta] as they arrive, and the last `functionCall`
 * seen across all events wins.
 *
 * Structured output maps [ResponseFormat.Json] to
 * `generationConfig.responseMimeType = "application/json"` and
 * [ResponseFormat.Schema] additionally sets `generationConfig.responseSchema`.
 * Gemini rejects combining JSON mode with function declarations, so tools and
 * responseFormat are mutually exclusive here (same as the OpenAI provider).
 */
class GeminiLlmProvider(
    override val config: LlmConfig,
    transport: HttpTransport,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    requireHttps: Boolean = true,
) : StreamingLlmProvider {
    private val remoteClient = RemoteClient(
        RemoteEndpoint(config.endpoint ?: DEFAULT_BASE_URL, requireHttps = requireHttps),
        transport,
    )
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    override fun complete(request: LlmRequest): LlmResponse {
        val apiKey = config.authToken()
        if (apiKey.isNullOrBlank()) {
            return LlmResponse.Error(LlmError.Authentication("No Gemini API key configured"))
        }
        val body = encodeRequest(request).getOrElse { return it.asError() }
        val result = remoteClient.send(
            path = "v1beta/models/${config.model}:generateContent",
            method = "POST",
            headers = apiKeyHeaders(apiKey),
            body = body,
            retryPolicy = retryPolicy,
        )
        return when (result) {
            is RemoteResult.Success -> parseResponse(result.body).requireJsonObjectIf(request.responseFormat != null)
            is RemoteResult.Failure -> LlmResponse.Error(classify(result))
        }
    }

    override fun stream(request: LlmRequest, onTextDelta: (String) -> Unit): LlmResponse {
        val apiKey = config.authToken()
        if (apiKey.isNullOrBlank()) {
            return LlmResponse.Error(LlmError.Authentication("No Gemini API key configured"))
        }
        val body = encodeRequest(request).getOrElse { return it.asError() }
        val accumulator = StreamAccumulator(onTextDelta)
        val parser = ServerSentEventParser { event -> accumulator.accept(event.data) }
        val result = remoteClient.sendStreaming(
            path = "v1beta/models/${config.model}:streamGenerateContent?alt=sse",
            method = "POST",
            headers = apiKeyHeaders(apiKey),
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

    private fun encodeRequest(request: LlmRequest): Result<String> {
        val format = request.responseFormat
        if (format != null && request.tools.isNotEmpty()) {
            return Result.failure(IllegalArgumentException("responseFormat cannot be combined with tools"))
        }

        val responseMimeType: String?
        val responseSchema: JsonObject?
        when (format) {
            null -> { responseMimeType = null; responseSchema = null }
            is ResponseFormat.Json -> { responseMimeType = "application/json"; responseSchema = null }
            is ResponseFormat.Schema -> {
                responseMimeType = "application/json"
                responseSchema = try {
                    json.parseToJsonElement(format.schema) as? JsonObject
                } catch (e: SerializationException) {
                    null
                } ?: return Result.failure(
                    IllegalArgumentException("responseFormat schema is not a JSON object"),
                )
            }
        }

        val temperature = request.temperature ?: config.temperature
        val maxTokens = request.maxOutputTokens ?: config.maxOutputTokens
        val generationConfig = if (temperature != null || maxTokens != null || responseMimeType != null) {
            GeminiGenerationConfig(
                temperature = temperature,
                maxOutputTokens = maxTokens,
                responseMimeType = responseMimeType,
                responseSchema = responseSchema,
            )
        } else null

        val (contents, systemInstruction) = toGeminiMessages(request.systemPrompt, request.messages)
        val geminiRequest = GeminiRequest(
            contents = contents,
            systemInstruction = systemInstruction,
            tools = request.tools.takeIf { it.isNotEmpty() }
                ?.let { listOf(GeminiTool(it.map(ToolSpec::toGeminiFunctionDeclaration))) },
            generationConfig = generationConfig,
        )
        return try {
            Result.success(json.encodeToString(GeminiRequest.serializer(), geminiRequest))
        } catch (e: SerializationException) {
            Result.failure(e)
        }
    }

    private fun parseResponse(body: String): LlmResponse {
        val response = try {
            json.decodeFromString(GeminiResponse.serializer(), body)
        } catch (e: SerializationException) {
            return LlmResponse.Error(LlmError.InvalidResponse("Malformed response body: ${e.message}"))
        }
        response.error?.let {
            return LlmResponse.Error(LlmError.ModelUnavailable("API error ${it.code ?: "unknown"}: ${it.message ?: ""}"))
        }
        val candidate = response.candidates.firstOrNull()
            ?: return LlmResponse.Error(LlmError.InvalidResponse("Response contained no candidates"))
        val parts = candidate.content?.parts
            ?: return LlmResponse.Error(LlmError.InvalidResponse("Candidate had no content"))

        val functionCall = parts.mapNotNull { it.functionCall }.lastOrNull()
        if (functionCall != null) {
            return LlmResponse.ToolCall(functionCall.name, (functionCall.args ?: JsonObject(emptyMap())).flatten())
        }

        val content = parts.mapNotNull { it.text }.joinToString("")
        if (content.isEmpty()) {
            return LlmResponse.Error(LlmError.InvalidResponse("Candidate had no text content"))
        }
        return LlmResponse.Text(content)
    }

    private fun Throwable.asError(): LlmResponse = LlmResponse.Error(
        LlmError.InvalidResponse(if (this is IllegalArgumentException) "Invalid request: $message" else "Failed to encode request: $message"),
    )

    private fun LlmResponse.requireJsonObjectIf(structured: Boolean): LlmResponse {
        if (!structured || this !is LlmResponse.Text) return this
        val parsed = try {
            json.parseToJsonElement(content) as? JsonObject
        } catch (e: SerializationException) {
            null
        }
        return if (parsed != null) this
        else LlmResponse.Error(LlmError.InvalidResponse("Structured output requested, but the response was not a JSON object"))
    }

    private fun apiKeyHeaders(apiKey: String): Map<String, String> = mapOf(
        "content-type" to "application/json",
        "x-goog-api-key" to apiKey,
    )

    private fun classify(failure: RemoteResult.Failure): LlmError = when {
        failure.statusCode == 401 || failure.statusCode == 403 -> LlmError.Authentication(failure.reason)
        failure.statusCode == 429 || failure.statusCode in 500..599 -> LlmError.ModelUnavailable(failure.reason)
        failure.statusCode != null -> LlmError.InvalidResponse(failure.reason)
        failure.cause is HttpTimeoutException -> LlmError.Timeout(failure.reason)
        else -> LlmError.Network(failure.reason)
    }

    private inner class StreamAccumulator(private val onTextDelta: (String) -> Unit) {
        private val text = StringBuilder()
        private var toolCall: GeminiFunctionCall? = null
        private var sawCandidate = false
        private var error: LlmError? = null

        fun accept(data: String) {
            if (error != null) return
            val response = try {
                json.decodeFromString(GeminiResponse.serializer(), data)
            } catch (e: SerializationException) {
                error = LlmError.InvalidResponse("Malformed stream chunk: ${e.message}")
                return
            }
            response.error?.let {
                error = LlmError.ModelUnavailable("Stream error ${it.status ?: "unknown"}: ${it.message ?: ""}")
                return
            }
            val candidate = response.candidates.firstOrNull() ?: return
            sawCandidate = true
            val parts = candidate.content?.parts ?: return
            for (part in parts) {
                part.text?.takeIf { it.isNotEmpty() }?.let {
                    text.append(it)
                    onTextDelta(it)
                }
                part.functionCall?.let { toolCall = it }
            }
        }

        fun result(): LlmResponse {
            error?.let { return LlmResponse.Error(it) }
            toolCall?.let { fc ->
                return LlmResponse.ToolCall(fc.name, (fc.args ?: JsonObject(emptyMap())).flatten())
            }
            if (!sawCandidate) return LlmResponse.Error(LlmError.InvalidResponse("Stream contained no candidates"))
            return LlmResponse.Text(text.toString())
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
    }
}

private fun toGeminiMessages(
    systemPrompt: String?,
    messages: List<Message>,
): Pair<List<GeminiContent>, GeminiSystemInstruction?> {
    val systemInstruction = systemPrompt?.let {
        GeminiSystemInstruction(parts = listOf(GeminiPart(text = it)))
    }
    val contents = messages.map { message ->
        val role = when (message.role) {
            Role.SYSTEM -> "user"
            Role.USER -> "user"
            Role.ASSISTANT -> "model"
            Role.TOOL -> "user"
        }
        GeminiContent(role = role, parts = listOf(GeminiPart(text = message.content)))
    }
    return contents to systemInstruction
}

private fun ToolSpec.toGeminiFunctionDeclaration(): GeminiFunctionDeclaration =
    GeminiFunctionDeclaration(
        name = name,
        description = description,
        parameters = buildJsonObject { put("type", JsonPrimitive("object")) },
    )

private fun JsonObject.flatten(): Map<String, String> = mapValues { (_, v) -> v.flattenToString() }

private fun JsonElement.flattenToString(): String = when (this) {
    is JsonPrimitive -> content
    else -> toString()
}
