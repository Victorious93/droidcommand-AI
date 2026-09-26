package ai.droidcommand.llm.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire types for Anthropic's Messages API (`POST /v1/messages`), kept
 * separate from [ai.droidcommand.llm.LlmRequest]/[ai.droidcommand.llm.LlmResponse]
 * so the provider-independent contract in core-llm never depends on one
 * vendor's JSON shape.
 */
@Serializable
data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String? = null,
    val messages: List<AnthropicMessage>,
    val temperature: Double? = null,
    val tools: List<AnthropicToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: AnthropicToolChoice? = null,
    val stream: Boolean? = null,
)

/** `{"type":"tool","name":...}` forces the model to call that one tool. */
@Serializable
data class AnthropicToolChoice(val type: String, val name: String? = null)

@Serializable
data class AnthropicMessage(val role: String, val content: String)

/**
 * [inputSchema] is a permissive `{"type":"object"}` for every tool: nothing
 * in core-agent's [ai.droidcommand.agent.ToolSpec] models a tool's parameter
 * shape yet, so this is the most that can honestly be advertised to the
 * model without inventing a schema the tool never declared.
 */
@Serializable
data class AnthropicToolDefinition(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
)

@Serializable
data class AnthropicResponse(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val content: List<AnthropicContentBlock> = emptyList(),
    val model: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
data class AnthropicContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
)

/**
 * One `data:` payload of a streamed (`"stream": true`) Messages API
 * response. Only the fields this provider reads are modeled: `type`
 * distinguishes `content_block_start` / `content_block_delta` / `error` /
 * the rest, which are ignored.
 */
@Serializable
data class AnthropicStreamEvent(
    val type: String,
    val index: Int? = null,
    @SerialName("content_block") val contentBlock: AnthropicContentBlock? = null,
    val delta: AnthropicStreamDelta? = null,
    val error: AnthropicStreamError? = null,
)

/** A `text_delta` carries [text]; an `input_json_delta` carries a fragment of a tool call's input as [partialJson]. */
@Serializable
data class AnthropicStreamDelta(
    val type: String? = null,
    val text: String? = null,
    @SerialName("partial_json") val partialJson: String? = null,
)

@Serializable
data class AnthropicStreamError(val type: String? = null, val message: String? = null)
