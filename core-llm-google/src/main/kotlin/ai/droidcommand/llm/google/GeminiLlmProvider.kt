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
import ai.droidcommand.remote.isTimeoutFailure
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A real [StreamingLlmProvider] for Google's Gemini API
 * (`POST {base}/v1beta/models/{model}:generateContent`, and
 * `:streamGenerateContent?alt=sse` for [stream]).
 *
 * Auth is the `x-goog-api-key` header, read fresh from [config]'s
 * `authToken` on every call — never the `?key=` query form, which would put
 * the credential in URLs (and so in proxy/server logs). A missing key fails
 * closed before any request is sent, like `core-llm-anthropic`.
 *
 * Mapping notes: [Role.SYSTEM] messages are folded into `systemInstruction`
 * (Gemini has no system role in `contents`); [Role.ASSISTANT] becomes
 * `model`; [Role.TOOL] becomes `user` for the same reason as the other
 * providers (no tool-call id in [Message]). Tool declarations carry name and
 * description only. Structured output maps to `responseMimeType` +
 * `responseJsonSchema`; it cannot be combined with tools.
 *
 * Verified only against a local mock server speaking the documented JSON
 * shape — **not** against the live Gemini API, so field-level drift (for
 * example around `responseJsonSchema`) is possible until a live check is run.
 */
class GeminiLlmProvider(
    override val config: LlmConfig,
    transport: HttpTransport,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    requireHttps: Boolean = true,
) : StreamingLlmProvider {
    private val remoteClient = RemoteClient(RemoteEndpoint(config.endpoint ?: DEFAULT_BASE_URL, requireHttps = requireHttps), transport)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val modelPath = "v1beta/models/" + config.model.removePrefix("models/")

    override fun complete(request: LlmRequest): LlmResponse {
        val apiKey = config.authToken()
        if (apiKey.isNullOrBlank()) return LlmResponse.Error(LlmError.Authentication("No Gemini API key configured"))
        val body = encodeRequest(request).getOrElse { return it.asError() }

        val result = remoteClient.send(
            path = "$modelPath:generateContent",
            method = "POST",
            headers = headers(apiKey),
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
        if (apiKey.isNullOrBlank()) return LlmResponse.Error(LlmError.Authentication("No Gemini API key configured"))
        val body = encodeRequest(request).getOrElse { return it.asError() }

        val accumulator = StreamAccumulator(onTextDelta)
        val parser = ServerSentEventParser { event -> accumulator.accept(event.data) }
        val result = remoteClient.sendStreaming(
            path = "$modelPath:streamGenerateContent?alt=sse",
            method = "POST",
            headers = headers(apiKey),
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

    private fun headers(apiKey: String) = mapOf("content-type" to "application/json", "x-goog-api-key" to apiKey)

    private fun encodeRequest(request: LlmRequest): Result<String> {
        val format = request.responseFormat
        if (format != null && request.tools.isNotEmpty()) {
            return Result.failure(IllegalArgumentException("responseFormat cannot be combined with tools"))
        }
        val schema: JsonElement? = (format as? ResponseFormat.Schema)?.let {
            try {
                json.parseToJsonElement(it.schema) as? JsonObject
            } catch (e: SerializationException) {
                null
            } ?: return Result.failure(IllegalArgumentException("responseFormat schema is not a JSON object"))
        }

        val system = buildList {
            request.systemPrompt?.let { add(it) }
            request.messages.filter { it.role == Role.SYSTEM }.forEach { add(it.content) }
        }
        val contents = request.messages.filter { it.role != Role.SYSTEM }.map { it.toGeminiContent() }
        if (contents.isEmpty()) return Result.failure(IllegalArgumentException("at least one non-system message is required"))

        val temperature = request.temperature ?: config.temperature
        val maxTokens = request.maxOutputTokens ?: config.maxOutputTokens
        val generationConfig = if (temperature != null || maxTokens != null || format != null) {
            GeminiGenerationConfig(
                temperature = temperature,
                maxOutputTokens = maxTokens,
                responseMimeType = format?.let { "application/json" },
                responseJsonSchema = schema,
            )
        } else {
            null
        }

        val geminiRequest = GeminiRequest(
            contents = contents,
            systemInstruction = system.takeIf { it.isNotEmpty() }?.let { GeminiContent(parts = listOf(GeminiPart(text = it.joinToString("\n\n")))) },
            generationConfig = generationConfig,
            tools = request.tools.takeIf { it.isNotEmpty() }?.let { specs -> listOf(GeminiTool(specs.map { it.toDeclaration() })) },
        )
        return try {
            Result.success(json.encodeToString(GeminiRequest.serializer(), geminiRequest))
        } catch (e: SerializationException) {
            Result.failure(e)
        }
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
        return if (parsed != null) this else LlmResponse.Error(LlmError.InvalidResponse("Structured output requested, but the response was not a JSON object"))
    }

    /** Streamed chunks are each a full response object; text is concatenated, and a function call (which arrives whole) wins over text. */
    private inner class StreamAccumulator(private val onTextDelta: (String) -> Unit) {
        private val text = StringBuilder()
        private var toolCall: LlmResponse.ToolCall? = null
        private var sawCandidate = false
        private var error: LlmError? = null

        fun accept(data: String) {
            if (error != null) return
            val chunk = try {
                json.decodeFromString(GeminiResponse.serializer(), data)
            } catch (e: SerializationException) {
                error = LlmError.InvalidResponse("Malformed stream chunk: ${e.message}")
                return
            }
            chunk.error?.let {
                error = LlmError.ModelUnavailable("Stream error ${it.status ?: "unknown"}: ${it.message ?: ""}")
                return
            }
            chunk.promptFeedback?.blockReason?.let {
                error = LlmError.InvalidResponse("Prompt blocked: $it")
                return
            }
            val candidate = chunk.candidates.firstOrNull() ?: return
            sawCandidate = true
            candidate.content?.parts?.forEach { part ->
                part.functionCall?.let { if (toolCall == null) toolCall = it.toToolCall() }
                if (part.thought != true) {
                    part.text?.takeIf { it.isNotEmpty() }?.let {
                        text.append(it)
                        onTextDelta(it)
                    }
                }
            }
        }

        fun result(): LlmResponse {
            error?.let { return LlmResponse.Error(it) }
            toolCall?.let { return it }
            if (!sawCandidate) return LlmResponse.Error(LlmError.InvalidResponse("Stream contained no candidates"))
            return LlmResponse.Text(text.toString())
        }
    }

    private fun parseResponse(body: String): LlmResponse {
        val decoded = try {
            json.decodeFromString(GeminiResponse.serializer(), body)
        } catch (e: SerializationException) {
            return LlmResponse.Error(LlmError.InvalidResponse("Malformed response body: ${e.message}"))
        }
        decoded.error?.let { return LlmResponse.Error(LlmError.InvalidResponse("API error ${it.status ?: ""}: ${it.message ?: ""}")) }
        decoded.promptFeedback?.blockReason?.let { return LlmResponse.Error(LlmError.InvalidResponse("Prompt blocked: $it")) }

        val candidate = decoded.candidates.firstOrNull()
            ?: return LlmResponse.Error(LlmError.InvalidResponse("Response contained no candidates"))
        val parts = candidate.content?.parts.orEmpty()

        parts.firstNotNullOfOrNull { it.functionCall }?.let { return it.toToolCall() }

        val text = parts.filter { it.thought != true }.mapNotNull { it.text }.joinToString("")
        if (text.isEmpty()) {
            return LlmResponse.Error(LlmError.InvalidResponse("Candidate had no text or function call (finishReason=${candidate.finishReason})"))
        }
        return LlmResponse.Text(text)
    }

    /** Gemini reports a bad API key as HTTP 400 `API_KEY_INVALID`, not 401, so that body is also an authentication failure. */
    private fun classify(failure: RemoteResult.Failure): LlmError = when {
        failure.statusCode == 401 || failure.statusCode == 403 -> LlmError.Authentication(failure.reason)
        failure.statusCode == 400 && (failure.reason.contains("API_KEY_INVALID") || failure.reason.contains("API key not valid")) ->
            LlmError.Authentication(failure.reason)
        failure.statusCode == 429 || failure.statusCode in 500..599 -> LlmError.ModelUnavailable(failure.reason)
        failure.statusCode != null -> LlmError.InvalidResponse(failure.reason)
        failure.cause.isTimeoutFailure() -> LlmError.Timeout(failure.reason)
        else -> LlmError.Network(failure.reason)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
    }
}

private fun Message.toGeminiContent() = GeminiContent(
    role = if (role == Role.ASSISTANT) "model" else "user",
    parts = listOf(GeminiPart(text = content)),
)

private fun ToolSpec.toDeclaration() = GeminiFunctionDeclaration(name = name, description = description)

private fun GeminiFunctionCall.toToolCall() =
    LlmResponse.ToolCall(name, args.orEmpty().mapValues { (_, v) -> if (v is JsonPrimitive) v.content else v.toString() })
