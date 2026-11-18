package ai.droidcommand.bots

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.AuditLog
import ai.droidcommand.security.InMemoryAuditLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class MapDirectory(vararg peers: BotPeer) : BotDirectory {
    private val byId = peers.associateBy { it.id }
    override fun find(id: String) = byId[id]
    override fun all() = byId.values.toList()
}

class BotBusTest {
    private val research = BotPeer("r", "Research", mayMessage = setOf("w"))
    private val writer = BotPeer("w", "Writer")
    private val secret = BotPeer("s", "Secret")

    private fun bus(
        directory: BotDirectory,
        audit: AuditLog = InMemoryAuditLog(),
        responder: BotResponder = BotResponder { to, _, msg, _ -> "${to.name} got: ${msg.substringAfter("---\n")}" },
        maxChain: Int = 3,
        maxMessages: Int = 6,
    ) = BotBus(directory, responder, audit, maxChain, maxMessages)

    @Test
    fun `nobody may message anybody until the owner allows it`() {
        val b = bus(MapDirectory(BotPeer("a", "A"), BotPeer("b", "B")))
        val out = b.open("a").send("a", "b", "hi")
        assertIs<BotMessageOutcome.Denied>(out)
        assertTrue(b.peersOf("a").isEmpty())
    }

    @Test
    fun `an allowed message is delivered, framed as another bot, and audited`() {
        val audit = InMemoryAuditLog()
        var seen = ""
        val b = bus(
            MapDirectory(research, writer), audit,
            BotResponder { _, _, msg, _ ->
                seen = msg
                "done"
            },
        )
        val out = b.open("r").send("r", "w", "draft intro")
        assertEquals(BotMessageOutcome.Delivered("done"), out)
        assertTrue(seen.contains("not the owner") && seen.endsWith("draft intro"))
        assertEquals(listOf(AuditEventType.BOT_MESSAGE_SENT), audit.all().map { it.type })
    }

    @Test
    fun `permission is directed`() {
        val b = bus(MapDirectory(research, writer))
        assertIs<BotMessageOutcome.Denied>(b.open("w").send("w", "r", "hi"))
    }

    @Test
    fun `a paused sender or recipient is refused`() {
        assertIs<BotMessageOutcome.Denied>(bus(MapDirectory(research, writer.copy(enabled = false))).open("r").send("r", "w", "x"))
        assertIs<BotMessageOutcome.Denied>(bus(MapDirectory(research.copy(enabled = false), writer)).open("r").send("r", "w", "x"))
    }

    @Test
    fun `a bot cannot send as someone other than the one speaking`() {
        val b = bus(MapDirectory(research, writer, secret.copy(mayMessage = setOf("w"))))
        assertIs<BotMessageOutcome.Denied>(b.open("r").send("s", "w", "pretend"))
    }

    @Test
    fun `loops and over-deep chains are refused`() {
        val a = BotPeer("a", "A", mayMessage = setOf("b"))
        val bb = BotPeer("b", "B", mayMessage = setOf("a", "c"))
        val c = BotPeer("c", "C", mayMessage = setOf("d"))
        val d = BotPeer("d", "D")
        val replies = mutableListOf<BotMessageOutcome>()
        lateinit var b: BotBus
        b = bus(
            MapDirectory(a, bb, c, d),
            responder = BotResponder { to, _, _, conv ->
                when (to.id) {
                    "b" -> {
                        replies += conv.send("b", "a", "loop back")
                        replies += conv.send("b", "c", "go on")
                        "b done"
                    }
                    "c" -> {
                        replies += conv.send("c", "d", "too deep")
                        "c done"
                    }
                    else -> "?"
                }
            },
        )
        assertEquals(BotMessageOutcome.Delivered("b done"), b.open("a").send("a", "b", "start"))
        assertIs<BotMessageOutcome.Denied>(replies[0]) // B -> A: already in chain
        // C's attempt at D runs inside B's call to C, so it is recorded before B -> C returns.
        assertIs<BotMessageOutcome.Denied>(replies[1]) // C -> D would be 4 deep
        assertEquals(BotMessageOutcome.Delivered("c done"), replies[2]) // chain A,B,C is 3 long
    }

    @Test
    fun `the message budget covers the whole conversation`() {
        val b = bus(MapDirectory(research, writer), maxMessages = 2)
        val conv = b.open("r")
        assertIs<BotMessageOutcome.Delivered>(conv.send("r", "w", "1"))
        assertIs<BotMessageOutcome.Delivered>(conv.send("r", "w", "2"))
        val third = conv.send("r", "w", "3")
        assertIs<BotMessageOutcome.Denied>(third)
        assertTrue(third.reason.contains("2 messages"))
    }

    @Test
    fun `empty and oversized messages are refused`() {
        val conv = bus(MapDirectory(research, writer)).open("r")
        assertIs<BotMessageOutcome.Denied>(conv.send("r", "w", "  "))
        assertIs<BotMessageOutcome.Denied>(conv.send("r", "w", "x".repeat(BotBus.MAX_MESSAGE + 1)))
    }

    @Test
    fun `a responder that throws becomes a denial, not a crash`() {
        val b = bus(MapDirectory(research, writer), responder = BotResponder { _, _, _, _ -> error("model offline") })
        val out = b.open("r").send("r", "w", "hi")
        assertIs<BotMessageOutcome.Denied>(out)
        assertTrue(out.reason.contains("model offline"))
    }

    @Test
    fun `a full audit log stops the message instead of letting it go unrecorded`() {
        var called = false
        val b = bus(
            MapDirectory(research, writer), InMemoryAuditLog(capacity = 0),
            BotResponder { _, _, _, _ ->
                called = true
                "x"
            },
        )
        assertIs<BotMessageOutcome.Denied>(b.open("r").send("r", "w", "hi"))
        assertFalse(called)
    }

    @Test
    fun `tool answers the same whether or not a disallowed bot exists, and still audits`() {
        val audit = InMemoryAuditLog()
        val b = bus(MapDirectory(research, writer, secret), audit)
        val tool = BotMessageTool(b.open("r"))
        val real = tool.execute(mapOf("to" to "Secret", "message" to "hi"))
        val fake = tool.execute(mapOf("to" to "Nobody", "message" to "hi"))
        assertIs<ToolResult.Failure>(real)
        assertEquals(real.reason.replace("Secret", "X"), (fake as ToolResult.Failure).reason.replace("Nobody", "X"))
        assertEquals(2, audit.all().count { it.type == AuditEventType.BOT_MESSAGE_DENIED })
        assertEquals(ToolResult.Success("Writer got: hi"), tool.execute(mapOf("to" to "writer", "message" to "hi")))
    }

    @Test
    fun `peers tool lists only allowed enabled bots by name`() {
        val b = bus(MapDirectory(research, writer, secret))
        assertEquals(ToolResult.Success("- Writer"), BotPeersTool(b.open("r")).execute(emptyMap()))
        assertEquals(ToolResult.Success("You are not allowed to message any other bot."), BotPeersTool(b.open("w")).execute(emptyMap()))
    }
}
