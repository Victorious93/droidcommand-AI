package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConversationContextTest {
    @Test
    fun `appends messages in order and preserves the system prompt`() {
        val context = ConversationContext(systemPrompt = "You are DroidCommand AI")
        context.append(Role.USER, "first")
        context.append(Role.ASSISTANT, "second")

        assertEquals("You are DroidCommand AI", context.systemPrompt)
        assertEquals(
            listOf(Message(Role.USER, "first"), Message(Role.ASSISTANT, "second")),
            context.messages,
        )
    }

    @Test
    fun `messages snapshot is not affected by later appends`() {
        val context = ConversationContext()
        context.append(Role.USER, "first")
        val snapshot = context.messages
        context.append(Role.USER, "second")

        assertEquals(1, snapshot.size)
        assertEquals(2, context.messages.size)
    }

    @Test
    fun `estimateTokens is a rough, positive, length-proportional estimate`() {
        assertEquals(0, estimateTokens(""))
        assertTrue(estimateTokens("a") > 0)
        assertTrue(estimateTokens("a".repeat(400)) > estimateTokens("a".repeat(100)))
    }

    @Test
    fun `unbounded by default, no maxTokens means no eviction however large the context grows`() {
        val context = ConversationContext()
        repeat(50) { context.append(Role.USER, "x".repeat(1000)) }
        assertEquals(50, context.messages.size)
    }

    @Test
    fun `rejects a non-positive maxTokens`() {
        assertFailsWith<IllegalArgumentException> { ConversationContext(maxTokens = 0) }
        assertFailsWith<IllegalArgumentException> { ConversationContext(maxTokens = -1) }
    }

    @Test
    fun `evicts the oldest messages first once maxTokens is exceeded`() {
        val context = ConversationContext(maxTokens = 20)
        context.append(Role.USER, "x".repeat(40)) // ~10 tokens
        context.append(Role.USER, "y".repeat(40)) // ~10 tokens, total ~20, still fits
        context.append(Role.USER, "z".repeat(40)) // pushes over budget, evicts the oldest ("x")

        assertEquals(2, context.messages.size)
        assertEquals("y".repeat(40), context.messages[0].content)
        assertEquals("z".repeat(40), context.messages[1].content)
    }

    @Test
    fun `never evicts the system prompt`() {
        val context = ConversationContext(systemPrompt = "s".repeat(200), maxTokens = 10)
        context.append(Role.USER, "hello")
        context.append(Role.USER, "world")

        assertEquals("s".repeat(200), context.systemPrompt)
    }

    @Test
    fun `never evicts the message just appended, even if it alone exceeds the budget`() {
        val context = ConversationContext(maxTokens = 5)
        context.append(Role.USER, "a".repeat(400)) // ~100 tokens, alone already over budget

        assertEquals(1, context.messages.size)
        assertEquals("a".repeat(400), context.messages[0].content)
    }
}
