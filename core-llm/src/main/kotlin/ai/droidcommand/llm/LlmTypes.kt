package ai.droidcommand.llm

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.ToolSpec

/**
 * [responseFormat] asks for structured output (ROADMAP-060): the response
 * is an [LlmResponse.Text] whose content is a JSON object, or an
 * [LlmResponse.Error] with [LlmError.InvalidResponse] when the provider
 * returned anything else. It cannot be combined with [tools]; a provider
 * rejects that combination with [LlmError.InvalidResponse] before sending.
 */
data class LlmRequest(
    val systemPrompt: String?,
    val messages: List<Message>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val responseFormat: ResponseFormat? = null,
)

/** The shape of structured output an [LlmRequest] asks for. */
sealed class ResponseFormat {
    /** Any JSON object. */
    data object Json : ResponseFormat()

    /**
     * A JSON object described by [schema], a JSON Schema document given as
     * JSON text. [name] identifies the schema to the provider (letters,
     * digits, `_` and `-`). [strict] asks providers that support it
     * (OpenAI's `json_schema` mode) to enforce the schema exactly; strict
     * mode has its own schema restrictions, so it is off by default.
     *
     * Only the "is a JSON object" part is checked on the way back; the
     * response is not validated against [schema] here.
     */
    data class Schema(val name: String, val schema: String, val strict: Boolean = false) : ResponseFormat()
}

sealed class LlmResponse {
    data class Text(val content: String) : LlmResponse()

    /**
     * The model asked for one or more tools. This object is the first call; any further calls made in the same
     * response are in [additionalCalls], in the order the model gave them, and [calls] is all of them. A caller
     * that only reads [toolName] and [input] keeps seeing the first call, as before these fields existed.
     *
     * [id] is the provider's id for the call and must be sent back unchanged with its result; it is null when the
     * provider has none (Gemini, some self-hosted servers). [argumentsJson] is the arguments exactly as received,
     * as JSON object text; [input] is the same arguments flattened to strings for [ai.droidcommand.agent.Tool].
     * [text] is assistant text that arrived with the calls. [additionalCalls] is only read on the first call.
     */
    data class ToolCall(
        val toolName: String,
        val input: Map<String, String>,
        val id: String? = null,
        val argumentsJson: String? = null,
        val additionalCalls: List<ToolCall> = emptyList(),
        val text: String = "",
    ) : LlmResponse() {
        val calls: List<ToolCall> get() = listOf(this) + additionalCalls

        companion object {
            /** One response from [calls] (at least one), the rest carried as [additionalCalls]. */
            fun of(calls: List<ToolCall>, text: String = ""): ToolCall =
                calls.first().copy(additionalCalls = calls.drop(1).map { it.copy(additionalCalls = emptyList()) }, text = text)
        }
    }

    data class Error(val error: LlmError) : LlmResponse()
}

sealed class LlmError(val message: String) {
    class Network(detail: String) : LlmError(detail)
    class Authentication(detail: String) : LlmError(detail)
    class Timeout(detail: String) : LlmError(detail)
    class ModelUnavailable(detail: String) : LlmError(detail)
    class InvalidResponse(detail: String) : LlmError(detail)
    class Cancelled(detail: String) : LlmError(detail)
}
