package ai.droidcommand.agent

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * One tool call an assistant message asked for, kept exactly as the model sent it so the same call can be shown
 * back to the provider on the next request. [id] is the provider's own call id (OpenAI `tool_calls[].id`,
 * Anthropic `tool_use.id`); [argumentsJson] is the arguments as JSON object text, unflattened.
 */
data class ToolCallRequest(val id: String, val name: String, val argumentsJson: String)

/**
 * [toolCalls] is set only on an [Role.ASSISTANT] message that asked for tools ([content] is then whatever text
 * came with the calls, often empty). [toolCallId] is set only on a [Role.TOOL] message and names the call that
 * message answers. Both default to "none", which is what every message written before they existed is: a
 * [Role.TOOL] message without an id is still accepted, and a provider adapter sends it the way it always did.
 */
data class Message
    @JvmOverloads
    constructor(
        val role: Role,
        val content: String,
        val toolCalls: List<ToolCallRequest> = emptyList(),
        val toolCallId: String? = null,
    )

/**
 * A rough, provider-agnostic token estimate (~4 characters per token, the
 * order of magnitude most tokenizers land near for English text) — not an
 * exact count from any real tokenizer, since core-agent has no LLM
 * dependency to ask. Good enough to bound context growth; never treat it
 * as a count a provider would agree with exactly.
 */
fun estimateTokens(text: String): Int = (text.length + 3) / 4

/**
 * Ordered conversation history handed to a [Planner] on every turn.
 * Append-only from the caller's perspective. When [maxTokens] is set (null,
 * the default, means unbounded — every existing caller is unaffected), an
 * append that would push [estimatedTokens] over budget evicts the oldest
 * messages first. [systemPrompt] is never evicted, and the message just
 * appended is never evicted either, even if that one message alone exceeds
 * the budget — dropping it would silence the very turn the caller just
 * added, which is worse than a temporarily over-budget context.
 */
class ConversationContext(val systemPrompt: String? = null, val maxTokens: Int? = null) {
    init {
        require(maxTokens == null || maxTokens > 0) { "maxTokens must be > 0 if set, got $maxTokens" }
    }

    private val mutableMessages = mutableListOf<Message>()

    val messages: List<Message>
        get() = mutableMessages.toList()

    fun estimatedTokens(): Int =
        (systemPrompt?.let { estimateTokens(it) } ?: 0) + mutableMessages.sumOf { estimateTokens(it.content) }

    fun append(message: Message): ConversationContext {
        mutableMessages += message
        enforceBudget()
        return this
    }

    fun append(role: Role, content: String): ConversationContext = append(Message(role, content))

    private fun enforceBudget() {
        val budget = maxTokens ?: return
        while (estimatedTokens() > budget && mutableMessages.size > 1) {
            mutableMessages.removeAt(0)
        }
    }
}
