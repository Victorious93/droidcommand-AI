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
    data class ToolCall(val toolName: String, val input: Map<String, String>) : LlmResponse()
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
