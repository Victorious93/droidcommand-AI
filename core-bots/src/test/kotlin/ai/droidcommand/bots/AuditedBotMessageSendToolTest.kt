package ai.droidcommand.bots

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.InMemoryAuditLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuditedBotMessageSendToolTest {
    private val audit = InMemoryAuditLog()
    private val bus = BotMessageBus({ BotPeerPolicy.NONE.allow("r", "w") })

    private fun send(from: String = "r") =
        AuditedBotMessageSendTool(BotMessageSendTool(from, bus), from, audit)

    @Test
    fun `a delivered message is logged with the recipient and length but not the text`() {
        val result = send().execute(mapOf("to" to "w", "text" to "secret plan"))
        assertIs<ToolResult.Success>(result)
        val event = audit.all().single()
        assertEquals(AuditEventType.BOT_MESSAGE_SENT, event.type)
        assertEquals("r", event.subject)
        assertTrue(event.detail.contains("'w'") && event.detail.contains("11 chars"))
        assertFalse(event.detail.contains("secret"))
    }

    @Test
    fun `a refused message is logged as denied with the reason`() {
        val result = send(from = "w").execute(mapOf("to" to "r", "text" to "hi"))
        assertIs<ToolResult.Failure>(result)
        val event = audit.all().single()
        assertEquals(AuditEventType.BOT_MESSAGE_DENIED, event.type)
        assertTrue(event.detail.contains("not allowed"))
    }

    @Test
    fun `the wrapper keeps the tool's own spec and result`() {
        val tool = send()
        assertEquals(BotMessageSendTool.NAME, tool.spec.name)
        assertEquals(ToolResult.Failure("Missing 'to' (the bot id)."), tool.execute(emptyMap()))
    }

    @Test
    fun `tool names are the bus tools and the sandbox tools`() {
        assertEquals("bot_message_send", BotToolNames.MESSAGE_SEND)
        assertEquals("bot_message_read", BotToolNames.MESSAGE_READ)
    }
}
