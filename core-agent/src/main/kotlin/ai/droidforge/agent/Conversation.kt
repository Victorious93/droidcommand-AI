package ai.droidforge.agent

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class Message(val role: Role, val content: String)

/** Ordered conversation history handed to a [Planner] on every turn. Append-only from the caller's perspective. */
class ConversationContext(val systemPrompt: String? = null) {
    private val mutableMessages = mutableListOf<Message>()

    val messages: List<Message>
        get() = mutableMessages.toList()

    fun append(message: Message): ConversationContext {
        mutableMessages += message
        return this
    }

    fun append(role: Role, content: String): ConversationContext = append(Message(role, content))
}
