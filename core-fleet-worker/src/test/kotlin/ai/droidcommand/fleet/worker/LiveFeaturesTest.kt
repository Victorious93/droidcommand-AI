package ai.droidcommand.fleet.worker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveFeaturesTest {
    class FakeBroker(var status: BrokerStatus = BrokerStatus.ready()) : ShellBrokerClient {
        val opened = CopyOnWriteArrayList<BrokerRequest>()
        val inputs = CopyOnWriteArrayList<String>()
        val closed = CopyOnWriteArrayList<String>()
        val sizes = CopyOnWriteArrayList<Pair<Int, Int>>()
        val signals = CopyOnWriteArrayList<String>()
        var listener: BrokerListener? = null
        var rejectWith: BrokerOpen.Rejected? = null
        override fun status() = status
        override fun open(req: BrokerRequest, listener: BrokerListener): BrokerOpen {
            opened += req
            this.listener = listener
            return rejectWith ?: BrokerOpen.Accepted
        }
        override fun input(sessionId: String, data: ByteArray) {
            inputs += String(data)
        }
        override fun resize(sessionId: String, cols: Int, rows: Int) {
            sizes += cols to rows
        }
        override fun signal(sessionId: String, name: String) {
            signals += name
        }
        override fun close(sessionId: String, reason: String) {
            closed += reason
        }
    }

    class FakeDrafts(var st: BrokerStatus = BrokerStatus.ready()) : DraftHandoffClient {
        val seen = LinkedHashMap<String, DraftState>()
        val newDrafts = CopyOnWriteArrayList<String>()
        var outcome: ((String, DraftState) -> Unit)? = null
        override fun status(channel: String) = st
        override fun deliver(d: IncomingDraft): DraftState = seen.getOrPut(d.draftId) {
            newDrafts += d.draftId
            DraftState("received")
        }
        override fun setOutcomeListener(l: (String, DraftState) -> Unit) {
            outcome = l
        }
    }

    private val dev = "dev-0123456789ab"
    private val sent = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    private var now = 1_790_000_000_000L
    private val relayCalls = CopyOnWriteArrayList<JsonObject>()
    private val relayReplies = java.util.concurrent.LinkedBlockingQueue<JsonObject>()
    private val relay = TerminalRelay { _, body ->
        relayCalls += body
        relayReplies.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS) ?: buildJsonObject {
            put("state", "open")
            put("ack", 0)
            put("chunks", JsonArray(emptyList()))
        }
    }

    private fun live(b: ShellBrokerClient?, d: DraftHandoffClient? = null) =
        LiveFeatures({ dev }, b, d, relay, { t, p -> sent += t to p }, { now }, { Thread(it).start() }, graceMs = 500)

    private fun open(id: String = "term-1", mode: String = "interactive", priv: String = "user", cmd: String? = null, binding: String? = null, expires: Long = now + 60_000): JsonObject {
        val b = binding ?: LiveProtocol.terminalBindingHash(id, dev, "victorlinux:owner@host", priv, mode, cmd, "n1")
        return buildJsonObject {
            put("session_id", id)
            put("desktop", "victorlinux:owner@host")
            put("mode", mode)
            put("privilege", priv)
            put("cols", 80)
            put("rows", 24)
            put("nonce", "n1")
            put("expires_ms", expires)
            put("binding", b)
            if (cmd != null) put("command", cmd)
        }
    }
    private fun states(sid: String = "term-1") = sent.filter { it.first == "terminal_status" && (it.second["session_id"] as JsonPrimitive).content == sid }.map { (it.second["state"] as JsonPrimitive).content }
    private fun waitFor(f: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end && !f()) Thread.sleep(10)
        assertTrue(f())
    }

    @Test fun `request goes to the broker only, announces awaiting_approval, then open with the echoed binding`() {
        val b = FakeBroker()
        val l = live(b)
        val p = open()
        l.onInbound("terminal_open", p)
        assertEquals(listOf("awaiting_approval"), states())
        assertEquals("victorlinux:owner@host", b.opened.single().peer)
        assertEquals(dev, b.opened.single().target)
        b.listener!!.onState("open", null, (p["binding"] as JsonPrimitive).content)
        waitFor { "open" in states() }
        assertEquals((p["binding"] as JsonPrimitive).content, ((sent.last { it.first == "terminal_status" }.second)["binding"] as JsonPrimitive).content)
        l.shutdown("test")
    }

    @Test fun `broker missing, untrusted, outdated or off means refuse with the reason and never open`() {
        for ((st, word) in listOf(BrokerStatus.of("untrusted", "certificate changed") to "untrusted", BrokerStatus.of("version_outdated", "needs 0.119") to "version outdated", BrokerStatus.of("not_installed", "x") to "not installed", BrokerStatus.of("permission_needed", "switch is off") to "permission needed")) {
            sent.clear()
            val b = FakeBroker(st)
            live(b).onInbound("terminal_open", open("t-$word".replace(' ', '-')))
            assertTrue(b.opened.isEmpty())
            val last = sent.last().second
            assertEquals("failed", (last["state"] as JsonPrimitive).content)
            assertTrue((last["reason"] as JsonPrimitive).content.contains(word))
        }
        sent.clear()
        live(null).onInbound("terminal_open", open("t-none"))
        assertEquals("failed", (sent.last().second["state"] as JsonPrimitive).content)
    }

    @Test fun `tampered binding, expired request and a replayed session id are all refused`() {
        val b = FakeBroker()
        val l = live(b)
        l.onInbound("terminal_open", open("t-a", binding = "0".repeat(64)))
        assertEquals(listOf("failed"), states("t-a"))
        l.onInbound("terminal_open", open("t-b", expires = now - 1))
        assertEquals(listOf("failed"), states("t-b"))
        assertTrue(b.opened.isEmpty())
        l.onInbound("terminal_open", open("t-c"))
        l.onInbound("terminal_open", open("t-c"))
        assertEquals(1, b.opened.size) // replay ignored
        l.shutdown("x")
    }

    @Test fun `privileged shell needs the separate grant`() {
        val b = FakeBroker(BrokerStatus.ready(root = false))
        live(b).onInbound("terminal_open", open("t-r", priv = "root"))
        assertTrue(b.opened.isEmpty())
        assertTrue((sent.last().second["reason"] as JsonPrimitive).content.contains("privileged"))
    }

    @Test fun `broker approving different parameters than requested ends the session`() {
        val b = FakeBroker()
        val l = live(b)
        l.onInbound("terminal_open", open("t-m"))
        b.listener!!.onState("open", null, "f".repeat(64))
        waitFor { "failed" in states("t-m") }
        assertTrue(b.closed.isNotEmpty())
        assertFalse("open" in states("t-m"))
    }

    @Test fun `denied and revoked from the broker are reported as such`() {
        val b = FakeBroker()
        val l = live(b)
        l.onInbound("terminal_open", open("t-d"))
        b.listener!!.onState("denied", "the owner declined", null)
        assertEquals(listOf("awaiting_approval", "denied"), states("t-d"))
        val b2 = FakeBroker()
        val l2 = live(b2)
        l2.onInbound("terminal_open", open("t-v"))
        b2.listener!!.onState("revoked", "revoked", null)
        assertEquals("revoked", states("t-v").last())
    }

    @Test fun `relay input is applied once, in order, and output is held until acknowledged`() {
        val b = FakeBroker()
        val l = live(b)
        val p = open("t-io")
        l.onInbound("terminal_open", p)
        b.listener!!.onState("open", null, (p["binding"] as JsonPrimitive).content)
        b.listener!!.onOutput("hello\n".toByteArray(), "tty")
        fun chunk(seq: Int, kind: String, extra: Map<String, Any> = emptyMap()) = buildJsonObject {
            put("seq", seq)
            put("kind", kind)
            extra.forEach { (k, v) -> if (v is Int) put(k, v) else put(k, v.toString()) }
        }
        relayReplies += buildJsonObject {
            put("state", "open")
            put("ack", 0)
            put(
                "chunks",
                buildJsonArray {
                    add(chunk(1, "data", mapOf("data" to Base64.getEncoder().encodeToString("ls\n".toByteArray()))))
                    add(chunk(2, "resize", mapOf("cols" to 100, "rows" to 30)))
                },
            )
        }
        relayReplies += buildJsonObject {
            put("state", "open")
            put("ack", 1)
            put(
                "chunks",
                buildJsonArray {
                    add(chunk(1, "data", mapOf("data" to Base64.getEncoder().encodeToString("ls\n".toByteArray()))))
                    add(chunk(3, "signal", mapOf("name" to "INT")))
                },
            )
        } // 1 repeated, 3 skips 2? no: 2 was consumed
        waitFor { b.inputs.isNotEmpty() && b.sizes.isNotEmpty() }
        assertEquals(listOf("ls\n"), b.inputs)
        assertEquals(listOf(100 to 30), b.sizes)
        waitFor { relayCalls.any { (it["chunks"] as JsonArray).isNotEmpty() } }
        val first = relayCalls.first { (it["chunks"] as JsonArray).isNotEmpty() }
        assertEquals("data", ((first["chunks"] as JsonArray)[0] as JsonObject)["kind"].let { (it as JsonPrimitive).content })
        l.shutdown("end")
    }

    @Test fun `relay unreachable past the grace period closes the shell instead of buffering input`() {
        val failing = TerminalRelay { _, _ -> throw FleetTransportException(0, null, "network down") }
        val b = FakeBroker()
        val l = LiveFeatures({ dev }, b, null, failing, { t, p -> sent += t to p }, System::currentTimeMillis, { Thread(it).start() }, graceMs = 400)
        val p = open("t-net", expires = System.currentTimeMillis() + 60_000)
        l.onInbound("terminal_open", p)
        b.listener!!.onState("open", null, (p["binding"] as JsonPrimitive).content)
        waitFor { b.closed.isNotEmpty() }
        assertTrue(b.inputs.isEmpty())
        assertTrue(l.liveSessions().isEmpty())
    }

    @Test fun `revocation or disabling shuts every live shell`() {
        val b = FakeBroker()
        val l = live(b)
        val p = open("t-s")
        l.onInbound("terminal_open", p)
        b.listener!!.onState("open", null, (p["binding"] as JsonPrimitive).content)
        l.shutdown("device revoked")
        assertEquals(listOf("device revoked"), b.closed)
        assertTrue(l.liveSessions().isEmpty())
    }

    // ---------------------------------------------------------------------------------------------------- drafts
    private fun draft(id: String = "draft-1", ch: String = "sms"): JsonObject {
        val d = if (ch == "sms") {
            buildJsonObject {
                put("to", buildJsonArray { add(JsonPrimitive("+15550100")) })
                put("body", "hi")
            }
        } else {
            buildJsonObject {
                put("to", buildJsonArray { add(JsonPrimitive("a@b.c")) })
                put("subject", "S")
                put("body", "B")
            }
        }
        return buildJsonObject {
            put("draft_id", id)
            put("channel", ch)
            put("draft", d)
            put("content_hash", LiveProtocol.draftContentHash(ch, d))
            put("origin", "victorlinux:owner")
            put("expires_ms", now + 86_400_000)
        }
    }
    private fun dstates(id: String = "draft-1") = sent.filter { it.first == "draft_status" && (it.second["draft_id"] as JsonPrimitive).content == id }.map { (it.second["state"] as JsonPrimitive).content }

    @Test fun `a draft is handed to VictorSMS and only reported received - never sent by this module`() {
        val d = FakeDrafts()
        val l = live(null, d)
        l.onInbound("draft_deliver", draft())
        assertEquals(listOf("draft-1"), d.newDrafts)
        assertEquals(listOf("received"), dstates())
    }

    @Test fun `a retried hand-off is idempotent and reports the current state`() {
        val d = FakeDrafts()
        val l = live(null, d)
        l.onInbound("draft_deliver", draft())
        d.seen["draft-1"] = DraftState("submitted")
        l.onInbound("draft_deliver", draft())
        l.onInbound("draft_deliver", draft())
        assertEquals(1, d.newDrafts.size)
        assertEquals(listOf("received", "submitted", "submitted"), dstates())
    }

    @Test fun `owner outcomes flow back and VictorSMS missing is reported as failed with the reason`() {
        val d = FakeDrafts()
        live(null, d).onInbound("draft_deliver", draft("d2"))
        d.outcome!!("d2", DraftState("declined"))
        assertEquals(listOf("received", "declined"), dstates("d2"))
        sent.clear()
        live(null, FakeDrafts(BrokerStatus.of("not_installed", "VictorSMS is not installed"))).onInbound("draft_deliver", draft("d3"))
        assertEquals(listOf("failed"), dstates("d3"))
        sent.clear()
        live(null, null).onInbound("draft_deliver", draft("d4"))
        assertEquals(listOf("failed"), dstates("d4"))
    }

    @Test fun `content hash tampering is rejected before VictorSMS sees anything`() {
        val d = FakeDrafts()
        val p = JsonObject(draft() + ("content_hash" to JsonPrimitive("0".repeat(64))))
        live(null, d).onInbound("draft_deliver", p)
        assertTrue(d.newDrafts.isEmpty())
    }

    @Test fun `capabilities are mapped from live status, independently`() {
        val ops = LiveCapabilities.operations(BrokerStatus.ready(), BrokerStatus.of("permission_needed", "SMS permission"), null)
        assertEquals("ready", ops["terminal.session"]!!.status)
        assertEquals("permission_needed", ops["terminal.root"]!!.status)
        assertEquals("permission_needed", ops["message.draft.sms"]!!.status)
        assertEquals("not_installed", ops["message.draft.email"]!!.status)
        assertFalse(ops["message.draft.email"]!!.available)
        FleetProtocol.validateCapabilities(CapabilityReport(ops).toJson()) // what we send must validate
    }
}
