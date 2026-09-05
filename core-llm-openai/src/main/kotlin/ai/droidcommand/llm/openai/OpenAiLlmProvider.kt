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
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.RemoteClient
import ai.droidcommand.remote.RemoteEndpoint
import ai.droidcommand.remote.RemoteResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.http.HttpTimeoutException

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
 */
class OpenAiLlmProvider(
    override val config: LlmConfig,
    transport: HttpTransport,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    requireHttps: Boolean = true,
) : LlmProvider {
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
        val chatRequest = OpenAiChatRequest(
            model = config.model,
            messages = toOpenAiMessages(request.systemPrompt, request.messages),
            temperature = request.temperature ?: config.temperature,
            maxTokens = request.maxOutputTokens ?: config.maxOutputTokens,
            tools = request.tools.takeIf { it.isNotEmpty() }?.map { it.toOpenAiToolDefinition() },
        )

        val body = try {
            json.encodeToString(OpenAiChatRequest.serializer(), chatRequest)
        } catch (e: SerializationException) {
            return LlmResponse.Error(LlmError.InvalidResponse("Failed to encode request: ${e.message}"))
        }

        val result = remoteClient.send(
            path = "v1/chat/completions",
            method = "POST",
            headers = mapOf("content-type" to "application/json"),
            body = body,
            retryPolicy = retryPolicy,
        )

        return when (result) {
            is RemoteResult.Success -> parseResponse(result.body)
            is RemoteResult.Failure -> LlmResponse.Error(classify(result))
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
        failure.cause is HttpTimeoutException -> LlmError.Timeout(failure.reason)
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
