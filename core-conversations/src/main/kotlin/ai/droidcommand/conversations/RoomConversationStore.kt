package ai.droidcommand.conversations

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.ConversationStore
import ai.droidcommand.agent.Role
import java.io.IOException

/**
 * Room-backed [ConversationStore] for Android — the persistent counterpart to
 * `core-agent.JsonFileConversationStore`. UNBUILT/UNTESTED (see build.gradle.kts).
 *
 * Same id rule as the JSON store (`[A-Za-z0-9_-]+`) so the two are interchangeable, and the same
 * load behaviour: messages are re-appended through [ConversationContext.append], so a stored
 * conversation larger than its own `maxTokens` is trimmed exactly as it would be live. An unknown
 * stored role is an [IOException], not silently coerced.
 *
 * Methods block on SQLite; Room rejects main-thread queries by default, which is the safe failure.
 */
class RoomConversationStore(
    private val dao: ConversationDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : ConversationStore {
    override fun save(conversationId: String, context: ConversationContext) {
        requireValid(conversationId)
        val header = ConversationEntity(conversationId, context.systemPrompt, context.maxTokens, clock())
        val rows = context.messages.mapIndexed { index, m -> MessageEntity(conversationId = conversationId, position = index, role = m.role.name, content = m.content) }
        dao.replace(header, rows)
    }

    override fun load(conversationId: String): ConversationContext? {
        requireValid(conversationId)
        val header = dao.find(conversationId) ?: return null
        val context = ConversationContext(header.systemPrompt, header.maxTokens)
        for (row in dao.messages(conversationId)) {
            val role = try {
                Role.valueOf(row.role)
            } catch (e: IllegalArgumentException) {
                throw IOException("Conversation '$conversationId' has an unknown role '${row.role}'", e)
            }
            context.append(role, row.content)
        }
        return context
    }

    override fun list(): List<String> = dao.ids()

    override fun delete(conversationId: String): Boolean {
        requireValid(conversationId)
        return dao.deleteConversation(conversationId) > 0
    }

    private fun requireValid(id: String) {
        require(ID_PATTERN.matches(id)) { "Invalid conversation id '$id'" }
    }

    companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]+")
    }
}
