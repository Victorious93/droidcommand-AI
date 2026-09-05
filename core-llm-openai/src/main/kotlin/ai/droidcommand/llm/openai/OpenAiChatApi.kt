package ai.droidcommand.llm.openai

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire types for the OpenAI Chat Completions API shape (`POST
 * /v1/chat/completions`) — the same shape OpenAI itself serves and the one
 * most self-hosted "OpenAI-compatible" servers (Ollama, vLLM, LM Studio,
 * llama.cpp's server, and similar) implement, kept separate from
 * [ai.droidcommand.llm.LlmRequest]/[ai.droidcommand.llm.LlmResponse] so the
 * provider-independent contract in core-llm never depends on one vendor's
 * JSON shape.
 */
@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiChatMessage>,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val tools: List<OpenAiToolDefinition>? = null,
)

@Serializable
data class OpenAiChatMessage(val role: String, val content: String)

/**
 * [parameters] is a permissive `{"type":"object"}` for every tool, for the
 * same reason as `core-llm-anthropic.AnthropicToolDefinition.inputSchema`:
 * nothing in core-agent's [ai.droidcommand.agent.ToolSpec] models a tool's
 * parameter shape yet.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OpenAiToolDefinition(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "function",
    val function: OpenAiFunctionDefinition,
)

@Serializable
data class OpenAiFunctionDefinition(val name: String, val description: String, val parameters: JsonObject)

@Serializable
data class OpenAiChatResponse(val choices: List<OpenAiChoice> = emptyList())

@Serializable
data class OpenAiChoice(
    val message: OpenAiResponseMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class OpenAiResponseMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null,
)

@Serializable
data class OpenAiToolCall(val id: String? = null, val type: String? = null, val function: OpenAiFunctionCall)

/** [arguments] is a JSON object serialized as a *string*, per the real API shape — not a nested JSON object. */
@Serializable
data class OpenAiFunctionCall(val name: String, val arguments: String)
