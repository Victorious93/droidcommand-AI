package ai.droidcommand.fleet.tools

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.fleet.messenger.LiveCallResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeSmsBridge : SmsBridge {
    val sends = mutableListOf<JsonObject>()
    val statuses = mutableListOf<JsonObject>()
    var sendResult: LiveCallResult = LiveCallResult.Success("sms.send", buildJsonObject { put("status", JsonPrimitive("pending")) })
    var statusResult: LiveCallResult = LiveCallResult.Success("sms.send.status", buildJsonObject { put("status", JsonPrimitive("pending")) })

    override fun send(params: JsonObject): LiveCallResult = sendResult.also { sends += params }

    override fun status(params: JsonObject): LiveCallResult = statusResult.also { statuses += params }
}

class VictorSmsToolsTest {
    private var now = 10_000_000L
    private var counter = 0
    private fun access(allowed: Set<String> = setOf("+1 555-123-4567"), perHour: Int = 2) = BotSmsAccess(
        allowedRecipients = { allowed },
        maxPerHour = perHour,
        maxChars = 50,
        clock = { now },
        random = { ByteArray(16) { (++counter).toByte() } },
    )

    private fun send(tool: VictorSmsSendTool, to: String = "+15551234567", body: String = "hi") =
        tool.execute(mapOf("to" to to, "body" to body))

    @Test
    fun `numbers are normalised and compared exactly`() {
        assertEquals("+15551234567", BotSmsAccess.normalize("+1 (555) 123-4567"))
        assertNull(BotSmsAccess.normalize("call me"))
        assertNull(BotSmsAccess.normalize("12"))
        assertNull(access().refusal("+1 555 123 4567", "hello"))
        assertTrue(access().refusal("+15551234568", "hello")!!.contains("not allowed"))
    }

    @Test
    fun `an empty allow-list refuses everything and nothing reaches the bridge`() {
        val bridge = FakeSmsBridge()
        val tool = VictorSmsSendTool(bridge, access(allowed = emptySet()))
        assertIs<ToolResult.Failure>(send(tool))
        assertTrue(bridge.sends.isEmpty())
    }

    @Test
    fun `an allowed number is queued with a fresh request id and nonce and is reported as not sent yet`() {
        val bridge = FakeSmsBridge()
        val out = send(VictorSmsSendTool(bridge, access()), body = "  hello  ")
        assertIs<ToolResult.Success>(out)
        assertTrue(out.output.contains("NOT SENT YET"))
        val p = bridge.sends.single()
        assertEquals("+15551234567", p["to"]!!.jsonPrimitive.content)
        assertEquals("hello", p["body"]!!.jsonPrimitive.content)
        assertTrue(Regex("[0-9a-f]{32}").matches(p["requestId"]!!.jsonPrimitive.content))
        assertTrue(Regex("[0-9a-f]{32}").matches(p["nonce"]!!.jsonPrimitive.content))
        assertFalse(p["requestId"]!!.jsonPrimitive.content == p["nonce"]!!.jsonPrimitive.content)
        assertEquals(now, p["timestampMs"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `empty, oversize and over-limit texts are refused before the bridge`() {
        val bridge = FakeSmsBridge()
        val tool = VictorSmsSendTool(bridge, access(perHour = 2))
        assertIs<ToolResult.Failure>(send(tool, body = "   "))
        assertIs<ToolResult.Failure>(send(tool, body = "x".repeat(51)))
        assertIs<ToolResult.Success>(send(tool))
        assertIs<ToolResult.Success>(send(tool))
        val third = send(tool)
        assertIs<ToolResult.Failure>(third)
        assertTrue(third.reason.contains("limit"))
        assertEquals(2, bridge.sends.size)
        now += 3_600_001
        assertIs<ToolResult.Success>(send(tool))
    }

    @Test
    fun `a refused or unexpected bridge answer is a failure and does not use up the hourly limit`() {
        val bridge = FakeSmsBridge()
        val a = access(perHour = 1)
        val tool = VictorSmsSendTool(bridge, a)
        bridge.sendResult = LiveCallResult.RemoteError("sms.send", "no", "external_control_disabled")
        val refused = send(tool)
        assertIs<ToolResult.Failure>(refused)
        assertTrue(refused.reason.contains("external control"))
        bridge.sendResult = LiveCallResult.Success("sms.send", buildJsonObject { put("status", JsonPrimitive("sent")) })
        assertIs<ToolResult.Failure>(send(tool))
        bridge.sendResult = LiveCallResult.Timeout("sms.send", "slow")
        assertTrue((send(tool) as ToolResult.Failure).reason.contains("Nothing is confirmed sent"))
        bridge.sendResult = LiveCallResult.Success("sms.send", buildJsonObject { put("status", JsonPrimitive("pending")) })
        assertIs<ToolResult.Success>(send(tool))
    }

    @Test
    fun `status only works for requests this bot queued and only sent means sent`() {
        val bridge = FakeSmsBridge()
        val a = access()
        val send = VictorSmsSendTool(bridge, a)
        val status = VictorSmsSendStatusTool(bridge, a)
        assertIs<ToolResult.Failure>(status.execute(mapOf("requestId" to "f".repeat(32))))
        assertTrue(bridge.statuses.isEmpty())
        send(send)
        val id = bridge.sends.single()["requestId"]!!.jsonPrimitive.content
        fun statusIs(s: String): ToolResult {
            bridge.statusResult = LiveCallResult.Success("sms.send.status", buildJsonObject { put("status", JsonPrimitive(s)) })
            return status.execute(mapOf("requestId" to id))
        }
        assertEquals("Sent.", (statusIs("sent") as ToolResult.Success).output)
        for (s in listOf("pending", "denied", "expired", "failed")) {
            assertTrue((statusIs(s) as ToolResult.Success).output.contains("Not sent"), s)
        }
        assertIs<ToolResult.Failure>(statusIs("weird"))
    }

    @Test
    fun `the gate switch and the live allow-list are read on every call`() {
        val bridge = FakeSmsBridge()
        var on = false
        var list = emptySet<String>()
        val a = BotSmsAccess({ list }, clock = { now })
        val tool = VictorSmsSendTool(GatedSmsBridge({ on }, bridge), a)
        assertIs<ToolResult.Failure>(send(tool)) // empty list
        list = setOf("+15551234567")
        val off = send(tool)
        assertIs<ToolResult.Failure>(off) // switch off
        assertTrue(off.reason.contains("switched off"))
        assertTrue(bridge.sends.isEmpty())
        on = true
        assertIs<ToolResult.Success>(send(tool))
        on = false
        assertIs<ToolResult.Failure>(VictorSmsSendStatusTool(GatedSmsBridge({ on }, bridge), a).execute(mapOf("requestId" to "a".repeat(32))))
    }

    @Test
    fun `the send tool asks for confirmation and the status tool does not`() {
        val a = access()
        assertTrue(VictorSmsSendTool(FakeSmsBridge(), a).spec.requiresConfirmation)
        assertFalse(VictorSmsSendStatusTool(FakeSmsBridge(), a).spec.requiresConfirmation)
    }
}
