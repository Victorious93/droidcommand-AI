package ai.droidcommand.bots

import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BotMessagingTest {
    private var now = 1_000_000L
    private var policy = BotPeerPolicy.NONE.allow("a", "b").allow("b", "a")
    private fun bus(maxDepth: Int = 4, maxPerMinute: Int = 10, maxMailbox: Int = 20) =
        BotMessageBus({ policy }, { now }, maxDepth = maxDepth, maxPerMinute = maxPerMinute, maxMailbox = maxMailbox)

    @Test
    fun `default policy allows nothing and links are directed`() {
        assertFalse(BotPeerPolicy.NONE.mayMessage("a", "b"))
        val one = BotPeerPolicy.NONE.allow("a", "b")
        assertTrue(one.mayMessage("a", "b"))
        assertFalse(one.mayMessage("b", "a"))
    }

    @Test
    fun `self links are rejected and never decoded as grants`() {
        runCatching { BotPeerPolicy.NONE.allow("a", "a") }.also { assertTrue(it.isFailure) }
        val decoded = BotPeerPolicy.decode("""[{"from":"a","to":"a"},{"from":"a","to":"b"},{"bad":1}]""")
        assertFalse(decoded.mayMessage("a", "a"))
        assertTrue(decoded.mayMessage("a", "b"))
    }

    @Test
    fun `encode then decode round trips and garbage decodes to no links`() {
        val p = BotPeerPolicy.NONE.allow("a", "b").allow("a", "c").allow("c", "a")
        val back = BotPeerPolicy.decode(p.encode())
        assertEquals(listOf("b", "c"), back.targetsOf("a"))
        assertTrue(back.mayMessage("c", "a"))
        assertFalse(BotPeerPolicy.decode("not json").mayMessage("a", "b"))
        assertFalse(BotPeerPolicy.decode(null).mayMessage("a", "b"))
    }

    @Test
    fun `forget removes links in both directions`() {
        val p = BotPeerPolicy.NONE.allow("a", "b").allow("b", "a").allow("c", "b").forget("b")
        assertFalse(p.mayMessage("a", "b"))
        assertFalse(p.mayMessage("b", "a"))
        assertFalse(p.mayMessage("c", "b"))
    }

    @Test
    fun `send is denied without a link and delivered with one`() {
        val bus = bus()
        assertIs<BotSendResult.Denied>(bus.send("a", "z", "hi"))
        assertIs<BotSendResult.Delivered>(bus.send("a", "b", "hi"))
        assertEquals(listOf("hi"), bus.drain("b").map { it.text })
        assertTrue(bus.drain("b").isEmpty())
    }

    @Test
    fun `revoking a link takes effect on the next send`() {
        val bus = bus()
        assertIs<BotSendResult.Delivered>(bus.send("a", "b", "one"))
        policy = policy.revoke("a", "b")
        assertIs<BotSendResult.Denied>(bus.send("a", "b", "two"))
    }

    @Test
    fun `empty, oversize, full-mailbox and rate-limited sends are refused`() {
        val bus = bus(maxMailbox = 2, maxPerMinute = 3)
        assertIs<BotSendResult.Denied>(bus.send("a", "b", "   "))
        assertIs<BotSendResult.Denied>(bus.send("a", "b", "x".repeat(2_001)))
        assertIs<BotSendResult.Delivered>(bus.send("a", "b", "1"))
        assertIs<BotSendResult.Delivered>(bus.send("a", "b", "2"))
        assertIs<BotSendResult.Denied>(bus.send("a", "b", "3")) // mailbox full
        bus.drain("b")
        assertIs<BotSendResult.Delivered>(bus.send("a", "b", "3"))
        assertIs<BotSendResult.Denied>(bus.send("a", "b", "4")) // 3 sends in the minute
        now += 61_000
        assertIs<BotSendResult.Delivered>(bus.send("a", "b", "4"))
    }

    @Test
    fun `a ping-pong chain ends at the depth limit even when the model never says it is a reply`() {
        val bus = bus(maxDepth = 2, maxPerMinute = 100)
        assertIs<BotSendResult.Delivered>(bus.send("a", "b", "0")) // depth 0
        var from = "b"
        var to = "a"
        var delivered = 1
        var refusal: String? = null
        while (refusal == null && delivered < 10) {
            bus.drain(from) // the replying bot reads its mailbox first
            when (val r = bus.send(from, to, "again")) {
                is BotSendResult.Delivered -> delivered++
                is BotSendResult.Denied -> refusal = r.reason
            }
            from = to.also { to = from }
        }
        assertTrue(refusal.orEmpty().contains("chain is too long"), refusal)
        assertEquals(3, delivered) // depths 0, 1, 2 only
    }

    @Test
    fun `forget drops a deleted bot's pending and sent messages`() {
        val bus = bus()
        bus.send("a", "b", "x")
        bus.send("b", "a", "y")
        bus.forget("a")
        assertTrue(bus.drain("b").isEmpty())
        assertEquals(0, bus.pendingCount("a"))
    }

    @Test
    fun `tools fix the sender and only read the owner's own mailbox`() {
        val bus = bus()
        val sendA = BotMessageSendTool("a", bus) { if (it == "b") "Bee" else null }
        // A model-supplied 'from' is ignored: the sender is always the tool's own bot.
        assertEquals(ToolResult.Success("Delivered to Bee."), sendA.execute(mapOf("to" to "b", "text" to "hi", "from" to "z")))
        assertIs<ToolResult.Failure>(sendA.execute(mapOf("text" to "no recipient")))
        assertIs<ToolResult.Failure>(sendA.execute(mapOf("to" to "q", "text" to "not linked")))
        val readA = BotMessageReadTool("a", bus)
        assertEquals(ToolResult.Success("No new messages."), readA.execute(emptyMap()))
        val readB = BotMessageReadTool("b", bus) { "Ay" }
        val out = readB.execute(emptyMap())
        assertIs<ToolResult.Success>(out)
        assertTrue(out.output.contains("From Ay (id a): hi"))
    }
}
