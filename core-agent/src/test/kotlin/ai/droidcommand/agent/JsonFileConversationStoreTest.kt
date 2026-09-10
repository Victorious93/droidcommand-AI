package ai.droidcommand.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonFileConversationStoreTest {
    private fun newStore() = JsonFileConversationStore(Files.createTempDirectory("droidcommand-conversation-store-test"))

    @Test
    fun `save then load returns an equivalent conversation`() {
        val store = newStore()
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
    fun `a conversation survives being loaded from a fresh store instance over the same directory`() {
        val dir = Files.createTempDirectory("droidcommand-conversation-store-test")
        val context = ConversationContext(systemPrompt = "be helpful")
        context.append(Role.USER, "hi")
        JsonFileConversationStore(dir).save("chat-1", context)

        val reopened = JsonFileConversationStore(dir).load("chat-1")!!

        assertEquals("be helpful", reopened.systemPrompt)
        assertEquals(listOf(Message(Role.USER, "hi")), reopened.messages)
    }

    @Test
    fun `load of an unsaved id returns null`() {
        assertNull(newStore().load("nope"))
    }

    @Test
    fun `mutating a loaded conversation does not change the file on disk`() {
        val store = newStore()
        val context = ConversationContext()
        context.append(Role.USER, "first")
        store.save("chat-1", context)

        store.load("chat-1")!!.append(Role.USER, "second")

        assertEquals(listOf(Message(Role.USER, "first")), store.load("chat-1")!!.messages)
    }

    @Test
    fun `list returns every saved conversation id, sorted`() {
        val store = newStore()
        store.save("b", ConversationContext())
        store.save("a", ConversationContext())

        assertEquals(listOf("a", "b"), store.list())
    }

    @Test
    fun `delete removes the file and reports whether it existed`() {
        val store = newStore()
        store.save("chat-1", ConversationContext())

        assertTrue(store.delete("chat-1"))
        assertNull(store.load("chat-1"))
        assertEquals(false, store.delete("chat-1"))
    }

    @Test
    fun `a conversation id containing a path separator is rejected rather than escaping the directory`() {
        val store = newStore()
        assertFailsWith<InvalidConversationId> { store.save("../escape", ConversationContext()) }
        assertFailsWith<InvalidConversationId> { store.load("../escape") }
        assertFailsWith<InvalidConversationId> { store.delete("../escape") }
    }

    @Test
    fun `an absolute-path-shaped id is rejected`() {
        val store = newStore()
        assertFailsWith<InvalidConversationId> { store.save("/etc/passwd", ConversationContext()) }
    }
}
