package ai.droidcommand.llm.anthropic

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
 * A real [LlmProvider] backed by Anthropic's Messages API, built on
 * core-remote's [RemoteClient] rather than a bespoke HTTP call — the same
 * transport already proven against a real local server in
 * `RemoteClientIntegrationTest`. [config]'s `authToken` is read fresh on
 * every [complete] call and never stored, matching the credential-handling
 * rule already used by `LlmConfig` and `core-config`'s loaders.
 *
 * Anthropic authenticates with an `x-api-key` header, not the `Authorization:
 * Bearer` header [RemoteClient] would attach from an `authToken` — so the
 * key is passed as an explicit request header here instead of relying on
 * that built-in mechanism.
 */
class AnthropicLlmProvider(
    override val config: LlmConfig,
    transport: HttpTransport,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val apiVersion: String = DEFAULT_API_VERSION,
    requireHttps: Boolean = true,
) : LlmProvider {
    private val remoteClient = RemoteClient(RemoteEndpoint(config.endpoint ?: DEFAULT_BASE_URL, requireHttps = requireHttps), transport)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    override fun complete(request: LlmRequest): LlmResponse {
        val apiKey = config.authToken()
        if (apiKey.isNullOrBlank()) {
            return LlmResponse.Error(LlmError.Authentication("No Anthropic API key configured"))
        }

        val (systemPrompt, messages) = toAnthropicMessages(request.systemPrompt, request.messages)
        val anthropicRequest = AnthropicRequest(
            model = config.model,
            maxTokens = request.maxOutputTokens ?: config.maxOutputTokens ?: DEFAULT_MAX_TOKENS,
            system = systemPrompt,
            messages = messages,
            temperature = request.temperature ?: config.temperature,
            tools = request.tools.takeIf { it.isNotEmpty() }?.map { it.toAnthropicToolDefinition() },
        )

        val body = try {
            json.encodeToString(AnthropicRequest.serializer(), anthropicRequest)
        } catch (e: SerializationException) {
            return LlmResponse.Error(LlmError.InvalidResponse("Failed to encode request: ${e.message}"))
        }

        val result = remoteClient.send(
            path = "v1/messages",
            method = "POST",
            headers = mapOf(
                "content-type" to "application/json",
                "anthropic-version" to apiVersion,
                "x-api-key" to apiKey,
            ),
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
            json.decodeFromString(AnthropicResponse.serializer(), body)
        } catch (e: SerializationException) {
            return LlmResponse.Error(LlmError.InvalidResponse("Malformed response body: ${e.message}"))
        }

        val toolUse = decoded.content.firstOrNull { it.type == "tool_use" }
        if (toolUse != null) {
            val name = toolUse.name
                ?: return LlmResponse.Error(LlmError.InvalidResponse("tool_use content block missing 'name'"))
            return LlmResponse.ToolCall(name, toolUse.input?.flatten() ?: emptyMap())
        }

        if (decoded.content.isEmpty()) {
            return LlmResponse.Error(LlmError.InvalidResponse("Response contained no content blocks"))
        }
        val text = decoded.content.filter { it.type == "text" }.joinToString("") { it.text ?: "" }
        return LlmResponse.Text(text)
    }

    private fun classify(failure: RemoteResult.Failure): LlmError = when {
        failure.statusCode == 401 || failure.statusCode == 403 -> LlmError.Authentication(failure.reason)
        failure.statusCode == 429 || failure.statusCode in 500..599 -> LlmError.ModelUnavailable(failure.reason)
        failure.statusCode != null -> LlmError.InvalidResponse(failure.reason)
        failure.cause is HttpTimeoutException -> LlmError.Timeout(failure.reason)
        else -> LlmError.Network(failure.reason)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val DEFAULT_API_VERSION = "2023-06-01"

        /**
         * Anthropic's API rejects a request with no `max_tokens`; nothing
         * elsewhere in this repository configures one, so this is an
         * explicit, documented fallback rather than a silent invented value.
         */
        const val DEFAULT_MAX_TOKENS = 1024
    }
}

/**
 * Anthropic's Messages API carries the system prompt in a single top-level
 * `system` field and only accepts `user`/`assistant` roles inside
 * `messages` — so a [Role.SYSTEM] message found mid-conversation (never
 * produced by [ai.droidcommand.agent.ConversationContext] today, but not
 * ruled out by the [Message] type) is folded into that field instead of
 * being dropped. [Role.TOOL] is mapped to a `user` message: Anthropic's
 * native `tool_result` block requires a `tool_use_id` back-reference that
 * [Message] does not carry, so a full multi-turn tool-calling transcript
 * cannot be replayed faithfully yet — this is a known, documented gap, not
 * a fabricated id.
 */
private fun toAnthropicMessages(systemPrompt: String?, messages: List<Message>): Pair<String?, List<AnthropicMessage>> {
    val systemParts = mutableListOf<String>()
    systemPrompt?.let { systemParts += it }
    val mapped = mutableListOf<AnthropicMessage>()

    for (message in messages) {
        when (message.role) {
            Role.SYSTEM -> systemParts += message.content
            Role.USER -> mapped += AnthropicMessage("user", message.content)
            Role.ASSISTANT -> mapped += AnthropicMessage("assistant", message.content)
            Role.TOOL -> mapped += AnthropicMessage("user", message.content)
        }
    }

    return systemParts.joinToString("\n\n").ifBlank { null } to mapped
}

private fun ToolSpec.toAnthropicToolDefinition(): AnthropicToolDefinition =
    AnthropicToolDefinition(name = name, description = description, inputSchema = openObjectSchema())

private fun openObjectSchema(): JsonObject = buildJsonObject { put("type", JsonPrimitive("object")) }

/**
 * Anthropic's `tool_use.input` is an arbitrary JSON object, but
 * [ai.droidcommand.agent.ToolResult]'s tool contract only accepts
 * `Map<String, String>`. A primitive value is unwrapped to its literal
 * text; a nested object or array is re-serialized to compact JSON text
 * rather than dropped, so no data silently disappears even though its
 * structure is flattened.
 */
private fun JsonObject.flatten(): Map<String, String> = mapValues { (_, value) -> value.flattenToString() }

private fun JsonElement.flattenToString(): String = when (this) {
    is JsonPrimitive -> content
    else -> toString()
}
