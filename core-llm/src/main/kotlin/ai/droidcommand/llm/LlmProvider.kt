package ai.droidcommand.llm

/**
 * Provider connection settings. [authToken] is a function, never a stored
 * string: a credential is read from secure storage/environment at call
 * time and never becomes part of this object's state, so it cannot be
 * accidentally logged, serialized, or committed.
 */
class LlmConfig(
    val provider: String,
    val model: String,
    val endpoint: String? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val authToken: () -> String? = { null },
)

/**
 * Provider-independent chat/tool-call abstraction. Real implementations
 * live in `core-llm-anthropic` and `core-llm-openai`; [ModelRouter] in this
 * module composes any number of them behind the same interface.
 * [ai.droidcommand.agent] has no dependency on this module, so the agent
 * core stays testable and usable without ever linking against a concrete
 * provider.
 */
interface LlmProvider {
    val config: LlmConfig

    fun complete(request: LlmRequest): LlmResponse
}

/**
 * An [LlmProvider] that can also stream a response as it is generated
 * (ROADMAP-058). [stream] passes each piece of generated *text* to
 * [onTextDelta] as it arrives and returns the same final [LlmResponse]
 * [complete] would have: [LlmResponse.Text] holding the concatenation of
 * every delta, [LlmResponse.ToolCall] once a tool call has fully arrived
 * (tool-call arguments are never streamed to [onTextDelta]), or
 * [LlmResponse.Error].
 *
 * An error that arrives after some deltas were already delivered is still
 * returned as [LlmResponse.Error]; the caller has seen partial text and
 * should treat it as incomplete.
 */
interface StreamingLlmProvider : LlmProvider {
    fun stream(request: LlmRequest, onTextDelta: (String) -> Unit): LlmResponse
}

/**
 * Streams through [StreamingLlmProvider.stream] when this provider
 * supports it; otherwise falls back to [LlmProvider.complete] and passes a
 * [LlmResponse.Text] result to [onTextDelta] as a single delta, so a caller
 * can use one code path for either kind of provider.
 */
fun LlmProvider.completeStreaming(request: LlmRequest, onTextDelta: (String) -> Unit): LlmResponse {
    if (this is StreamingLlmProvider) return stream(request, onTextDelta)
    val response = complete(request)
    if (response is LlmResponse.Text && response.content.isNotEmpty()) onTextDelta(response.content)
    return response
}
