package ai.droidcommand.fleet.worker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

// ---------------------------------------------------------------------------------------------------------------- broker (VictorSuite)
/** What the phone can say about VictorSuite's shell broker RIGHT NOW. Anything but READY means: show [detail], stop, and do not fall back. */
data class BrokerStatus(val state: String, val detail: String?, val rootAllowed: Boolean = false) {
    companion object { fun ready(root: Boolean = false) = BrokerStatus("ready", null, root); fun of(state: String, detail: String) = BrokerStatus(state, detail) }
}
data class BrokerRequest(val sessionId: String, val peer: String, val target: String, val mode: String, val privilege: String, val command: String?, val cols: Int, val rows: Int,
                         val nonce: String, val expiresMs: Long, val binding: String)
sealed class BrokerOpen { object Accepted : BrokerOpen(); data class Rejected(val code: String, val reason: String) : BrokerOpen() }
interface BrokerListener {
    fun onState(state: String, reason: String?, binding: String?)
    fun onOutput(data: ByteArray, channel: String)
    fun onExit(status: Int)
}
/** The ONLY execution path for fleet shell requests. Android implementation: VictorSuite's IShellBroker AIDL service. */
interface ShellBrokerClient {
    fun status(): BrokerStatus
    fun open(req: BrokerRequest, listener: BrokerListener): BrokerOpen
    fun input(sessionId: String, data: ByteArray)
    fun resize(sessionId: String, cols: Int, rows: Int)
    fun signal(sessionId: String, name: String)
    fun close(sessionId: String, reason: String)
}

/** Coordinator-side stream for a session (HTTPS, pinned, device credential): see HttpsFleetTransport.terminalExchange. */
fun interface TerminalRelay { fun exchange(sessionId: String, body: JsonObject): JsonObject }

// ---------------------------------------------------------------------------------------------------------------- messaging (VictorSMS)
data class IncomingDraft(val draftId: String, val channel: String, val fields: JsonObject, val contentHash: String, val origin: String)
data class DraftState(val state: String, val detail: String? = null)
interface DraftHandoffClient {
    fun status(channel: String): BrokerStatus
    /** Idempotent by draft id: a repeat returns the CURRENT state and never creates, re-shows-as-new or submits anything. */
    fun deliver(d: IncomingDraft): DraftState
    fun setOutcomeListener(l: (draftId: String, state: DraftState) -> Unit)
}

/**
 * Terminal / one-shot / draft handling for protocol 1.1. Runs inside [FleetWorker]. It never executes anything and never decides
 * approval: shells go to VictorSuite's broker (owner dialog there), drafts go to VictorSMS (owner Send button there). Messages from the
 * coordinator that arrive when the broker/VictorSMS is missing, untrusted or outdated are refused with the reason; there is no fallback.
 * Nothing here is persisted: a restart forgets live sessions (their shells are closed by the broker when this process dies).
 */
class LiveFeatures(
    private val deviceId: () -> String?,
    private val broker: ShellBrokerClient?,
    private val drafts: DraftHandoffClient?,
    private val relay: TerminalRelay?,
    private val sendStatus: (type: String, payload: JsonObject) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: Executor = Executor { Thread(it, "fleet-terminal").apply { isDaemon = true }.start() },
    private val graceMs: Long = 8_000,
) {
    private val seenSessions = LinkedHashSet<String>()
    private val sessions = ConcurrentHashMap<String, Session>()

    init {
        drafts?.setOutcomeListener { id, st -> sendStatus("draft_status", buildJsonObject { put("draft_id", id); put("state", st.state); st.detail?.let { put("detail", it.take(200)) } }) }
    }

    fun onInbound(type: String, p: JsonObject) {
        when (type) {
            "terminal_open" -> runCatching { onTerminalOpen(p) }.onFailure { e -> (p["session_id"] as? JsonPrimitive)?.contentOrNull?.let { fail(it, "bad request: ${e.message}") } }
            "terminal_close" -> (p["session_id"] as? JsonPrimitive)?.contentOrNull?.let { sessions[it]?.endFromCoordinator("closed by the desktop") }
            "draft_deliver" -> runCatching { onDraft(p) }
        }
    }

    fun liveSessions() = sessions.keys.toList()
    fun shutdown(reason: String) { sessions.values.forEach { it.endFromCoordinator(reason) } }

    private fun status(sid: String, state: String, reason: String? = null, binding: String? = null, exit: Int? = null) =
        sendStatus("terminal_status", buildJsonObject {
            put("session_id", sid); put("state", state); reason?.let { put("reason", it.take(250)) }; binding?.let { put("binding", it) }; exit?.let { put("exit_status", it) }
        })
    private fun fail(sid: String, why: String) = status(sid, "failed", why)

    // ---------------------------------------------------------------------------------------------------------------- terminal
    private fun onTerminalOpen(p: JsonObject) {
        LiveProtocol.validateTerminalOpen(p)
        val sid = (p["session_id"] as JsonPrimitive).content
        val dev = deviceId() ?: return
        synchronized(seenSessions) { if (!seenSessions.add(sid)) return; if (seenSessions.size > 256) seenSessions.remove(seenSessions.first()) }   // a request is never replayed
        val mode = (p["mode"] as JsonPrimitive).content; val priv = (p["privilege"] as JsonPrimitive).content
        val command = (p["command"] as? JsonPrimitive)?.contentOrNull; val desktop = (p["desktop"] as JsonPrimitive).content
        val nonce = (p["nonce"] as JsonPrimitive).content; val expires = (p["expires_ms"] as JsonPrimitive).content.toLong()
        val binding = LiveProtocol.terminalBindingHash(sid, dev, desktop, priv, mode, command, nonce)
        if (binding != (p["binding"] as JsonPrimitive).content) return fail(sid, "approval binding does not match the request contents")
        if (expires <= clock()) return status(sid, "failed", "the request had already expired")
        val b = broker ?: return fail(sid, "VictorSuite's shell broker is not available on this phone")
        val st = b.status()
        if (st.state != "ready") return fail(sid, "VictorSuite: ${st.state.replace('_', ' ')}${st.detail?.let { " — $it" } ?: ""}")   // no fallback path
        if (priv == "root" && !st.rootAllowed) return fail(sid, "privileged shells are not granted in VictorSuite")
        status(sid, "awaiting_approval")
        val s = Session(sid, mode, binding, b)
        sessions[sid] = s
        val ack = b.open(BrokerRequest(sid, desktop, dev, mode, priv, command, (p["cols"] as JsonPrimitive).content.toInt(), (p["rows"] as JsonPrimitive).content.toInt(), nonce, expires, binding), s)
        if (ack is BrokerOpen.Rejected) { sessions.remove(sid); fail(sid, "VictorSuite refused: ${ack.reason}") }
    }

    private inner class Session(val sid: String, val mode: String, val expectBinding: String, val b: ShellBrokerClient) : BrokerListener {
        @Volatile var finished = false
        @Volatile var opened = false
        private var seq = 0
        private val unconfirmed = ArrayList<JsonObject>()
        private var unconfirmedBytes = 0
        private var ackIn = 0L
        @Volatile private var lastContact = clock()
        private var exitStatus: Int? = null
        @Volatile private var brokerDone = false

        private fun push(c: JsonObject, size: Int) = synchronized(unconfirmed) {
            seq++; unconfirmed += JsonObject(c + ("seq" to JsonPrimitive(seq))); unconfirmedBytes += size + 32
            if (unconfirmedBytes > 1 shl 20) { b.close(sid, "relay not draining"); end("failed", "output buffer limit") }
        }

        override fun onState(state: String, reason: String?, binding: String?) {
            when (state) {
                "open" -> {
                    if (binding != expectBinding) { b.close(sid, "binding mismatch"); end("failed", "VictorSuite approved different parameters than requested"); return }
                    opened = true; status(sid, "open", binding = binding); lastContact = clock(); executor.execute { pump() }
                }
                "denied" -> end("denied", reason); "revoked" -> end("revoked", reason)
                "expired", "failed" -> end("failed", reason ?: state)
                "closed" -> { brokerDone = true; if (!opened) end("closed", reason) else status(sid, "closed", reason, exit = exitStatus) }
            }
        }
        override fun onOutput(data: ByteArray, channel: String) {
            push(JsonObject(mapOf("kind" to JsonPrimitive("data"), "data" to JsonPrimitive(Base64.getEncoder().encodeToString(data)), "channel" to JsonPrimitive(channel))), data.size)
        }
        override fun onExit(status: Int) { exitStatus = status; push(JsonObject(mapOf("kind" to JsonPrimitive("exit"), "status" to JsonPrimitive(status))), 0) }

        fun end(state: String, reason: String?) {
            if (finished) return; finished = true; sessions.remove(sid)
            status(sid, state, reason, exit = exitStatus)
        }
        fun endFromCoordinator(why: String) { if (!finished) { b.close(sid, why); finished = true; sessions.remove(sid) } }

        private fun pump() {
            val deadlineAfterBroker = 5_000L; var brokerDoneAt = 0L
            while (!finished) {
                val chunks = synchronized(unconfirmed) { unconfirmed.take(64) }
                val resp = try {
                    relay!!.exchange(sid, buildJsonObject { put("ack", ackIn); put("chunks", JsonArray(chunks)); put("wait_s", if (chunks.isEmpty()) 1 else 0) })
                } catch (e: Exception) {
                    if (clock() - lastContact > graceMs) { b.close(sid, "relay unreachable; input is never buffered"); end("failed", "coordinator connection lost"); return }
                    Thread.sleep(250); continue
                }
                lastContact = clock()
                val acked = (resp["ack"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
                synchronized(unconfirmed) { val keep = unconfirmed.filter { (it["seq"] as JsonPrimitive).content.toLong() > acked }; unconfirmedBytes = keep.sumOf { ((it["data"] as? JsonPrimitive)?.content?.length ?: 0) + 32 }; unconfirmed.clear(); unconfirmed.addAll(keep) }
                for (c in (resp["chunks"] as? JsonArray).orEmpty()) {
                    val o = c as JsonObject; val n = (o["seq"] as JsonPrimitive).content.toLong()
                    if (n <= ackIn) continue                       // duplicate: input is applied at most once
                    if (n != ackIn + 1) { b.close(sid, "relay sequence gap"); end("failed", "relay sequence gap"); return }
                    ackIn = n
                    when ((o["kind"] as JsonPrimitive).content) {
                        "data" -> if (mode == "interactive") b.input(sid, Base64.getDecoder().decode((o["data"] as JsonPrimitive).content))
                        "resize" -> b.resize(sid, (o["cols"] as JsonPrimitive).content.toInt(), (o["rows"] as JsonPrimitive).content.toInt())
                        "signal" -> b.signal(sid, (o["name"] as JsonPrimitive).content)
                        "eof" -> if (mode == "interactive") b.input(sid, byteArrayOf(4))
                        "close" -> { b.close(sid, "closed by the desktop"); finished = true; sessions.remove(sid); return }
                    }
                }
                val st = (resp["state"] as? JsonPrimitive)?.content
                if (st in setOf("closed", "failed", "revoked", "expired", "disconnected", "denied")) { b.close(sid, "relay ended: $st"); finished = true; sessions.remove(sid); return }
                if (brokerDone) {
                    if (brokerDoneAt == 0L) brokerDoneAt = clock()
                    if (synchronized(unconfirmed) { unconfirmed.isEmpty() } || clock() - brokerDoneAt > deadlineAfterBroker) { finished = true; sessions.remove(sid); return }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- drafts
    private fun onDraft(p: JsonObject) {
        LiveProtocol.validateDraftDeliver(p)
        val id = (p["draft_id"] as JsonPrimitive).content; val ch = (p["channel"] as JsonPrimitive).content; val hash = (p["content_hash"] as JsonPrimitive).content
        fun st(state: String, detail: String? = null) = sendStatus("draft_status", buildJsonObject { put("draft_id", id); put("state", state); put("content_hash", hash); detail?.let { put("detail", it.take(200)) } })
        val d = drafts ?: return st("failed", "VictorSMS is not available on this phone")
        val s = d.status(ch)
        if (s.state != "ready") return st("failed", "VictorSMS: ${s.state.replace('_', ' ')}${s.detail?.let { " — $it" } ?: ""}")
        if ((p["expires_ms"] as JsonPrimitive).content.toLong() <= clock()) return st("failed", "the draft had expired")
        val r = d.deliver(IncomingDraft(id, ch, p["draft"] as JsonObject, hash, (p["origin"] as JsonPrimitive).content))
        st(r.state, r.detail)
    }
}
