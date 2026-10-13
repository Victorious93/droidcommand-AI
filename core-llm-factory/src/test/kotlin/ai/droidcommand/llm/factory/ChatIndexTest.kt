package ai.droidcommand.llm.factory

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.agent.InvalidConversationId
import ai.droidcommand.agent.Role
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.remote.JdkHttpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChatIndexTest {
    @Test
    fun `titles come from the first user line and a blank chat stays untitled`() {
        val store = InMemoryConversationStore()
        store.save("a", ConversationContext().apply { append(Role.USER, "Fix the login\nmore") })
        store.save("b", ConversationContext())
        assertEquals(listOf(SavedChat("a", "Fix the login"), SavedChat("b", "New chat")), savedChats(store))
    }

    @Test
    fun `a new id is accepted by the conversation id rule`() {
        val id = newChatId()
        assertTrue(id.startsWith("c"))
        assertTrue(id.length > 8)
    }

    @Test
    fun `opening another chat leaves the first one stored`() {
        val store = InMemoryConversationStore()
        store.save("default", ConversationContext().apply { append(Role.USER, "first") })
        val chat = ChatSession(InMemorySecretsVault(), store, transport = JdkHttpTransport())
        assertEquals(listOf("first"), chat.history().map { it.content })
        chat.open(newChatId())
        assertEquals(emptyList(), chat.history())
        assertEquals(listOf("first"), store.load("default")!!.messages.map { it.content })
    }

    @Test
    fun `an id with a slash is refused`() {
        val chat = ChatSession(InMemorySecretsVault(), InMemoryConversationStore(), transport = JdkHttpTransport())
        assertFailsWith<InvalidConversationId> { chat.open("../x") }
        assertEquals("default", chat.currentId())
    }
}
