package ai.droidcommand.agent

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * One tool call a model asked for, kept exactly as the provider sent it: [id] pairs the call with its result on the
 * next request, [argumentsJson] is the provider's own argument text (not re-encoded).
 */
data class ToolCallRecord(val id: String, val name: String, val argumentsJson: String)

/**
 * [toolCalls] (on an [Role.ASSISTANT] message) and [toolCallId] (on a [Role.TOOL] message) carry a structured tool
 * exchange for providers that need calls and results paired by id. Both are empty by default: a message built with
 * only [role] and [content] behaves exactly as before, and providers that do not understand the pairing ignore it.
 */
data class Message(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCallRecord> = emptyList(),
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
