package ai.droidcommand.llm.factory

import ai.droidcommand.agent.ConversationStore
import ai.droidcommand.agent.InvalidConversationId
import ai.droidcommand.agent.JsonFileConversationStore
import ai.droidcommand.agent.Role
import java.util.UUID

/** One saved conversation as the chat list shows it. [title] is the first user line, shortened. */
data class SavedChat(val id: String, val title: String)

/**
 * Titles for the chats already in [store]. Does not create or delete anything.
 * A conversation with no user text is titled "New chat".
 */
fun savedChats(store: ConversationStore): List<SavedChat> =
    store.list().map { id ->
        val first = store.load(id)?.messages?.firstOrNull { it.role == Role.USER }?.content
        val title = first?.lineSequence()?.firstOrNull()?.trim()?.take(TITLE_LIMIT)?.ifEmpty { null } ?: "New chat"
        SavedChat(id, title)
    }

/** A new conversation id that both the file store and the Room store accept. */
fun newChatId(): String {
    val id = "c" + UUID.randomUUID().toString().replace("-", "")
    if (!JsonFileConversationStore.ID_PATTERN.matches(id)) throw InvalidConversationId(id)
    return id
}

private const val TITLE_LIMIT = 72
