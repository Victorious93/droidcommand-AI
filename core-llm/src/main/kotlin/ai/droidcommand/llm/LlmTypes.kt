package ai.droidcommand.llm

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.ToolSpec

data class LlmRequest(
    val systemPrompt: String?,
    val messages: List<Message>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

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
