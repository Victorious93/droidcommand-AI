package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryConversationStoreTest {
    @Test
    fun `save then load returns an equivalent conversation`() {
        val store = InMemoryConversationStore()
        val context = ConversationContext(systemPrompt = "be helpful", maxTokens = 1000)
        context.append(Role.USER, "hi")
        context.append(Role.ASSISTANT, "hello")

        store.save("chat-1", context)
        val loaded = store.load("chat-1")!!

        assertEquals("be helpful", loaded.systemPrompt)
        assertEquals(1000, loaded.maxTokens)
        assertEquals(listOf(Message(Role.USER, "hi"), Message(Role.ASSISTANT, "hello")), loaded.messages)
    }

    @Test
    fun `load of an unsaved id returns null`() {
        assertNull(InMemoryConversationStore().load("nope"))
    }

    @Test
    fun `mutating the original context after saving does not change the stored copy`() {
        val store = InMemoryConversationStore()
        val context = ConversationContext()
        context.append(Role.USER, "first")
        store.save("chat-1", context)

        context.append(Role.USER, "second") // mutate the same object the caller kept

        assertEquals(listOf(Message(Role.USER, "first")), store.load("chat-1")!!.messages)
    }

    @Test
    fun `mutating a loaded conversation does not change the store's copy`() {
        val store = InMemoryConversationStore()
        val context = ConversationContext()
        context.append(Role.USER, "first")
        store.save("chat-1", context)

        store.load("chat-1")!!.append(Role.USER, "second") // mutate the returned copy

        assertEquals(listOf(Message(Role.USER, "first")), store.load("chat-1")!!.messages)
    }

    @Test
    fun `list returns every saved conversation id, sorted`() {
        val store = InMemoryConversationStore()
        store.save("b", ConversationContext())
        store.save("a", ConversationContext())

        assertEquals(listOf("a", "b"), store.list())
    }

    @Test
    fun `delete removes a conversation and reports whether it existed`() {
        val store = InMemoryConversationStore()
        store.save("chat-1", ConversationContext())

        assertTrue(store.delete("chat-1"))
        assertNull(store.load("chat-1"))
        assertEquals(false, store.delete("chat-1"))
    }
}
