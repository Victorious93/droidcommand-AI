package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
