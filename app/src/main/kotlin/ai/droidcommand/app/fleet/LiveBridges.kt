package ai.droidcommand.app.fleet

import ai.droidcommand.fleet.worker.BrokerListener
import ai.droidcommand.fleet.worker.BrokerOpen
import ai.droidcommand.fleet.worker.BrokerRequest
import ai.droidcommand.fleet.worker.BrokerStatus
import ai.droidcommand.fleet.worker.DraftHandoffClient
import ai.droidcommand.fleet.worker.DraftState
import ai.droidcommand.fleet.worker.IncomingDraft
import ai.droidcommand.fleet.worker.ShellBrokerClient
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.termux.victor.shellbroker.IShellBroker
import com.termux.victor.shellbroker.IShellSessionCallback
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android adapters for the fleet worker's live features. NOT RUN ON A DEVICE. Every security decision is made by the other app, which
 * authenticates THIS app (Binder uid + pinned certificate): VictorSuite's owner trust list, VictorSMS's pairing. Missing/untrusted/outdated
 * states are reported, never bypassed.
 */
class VictorSuiteBrokerClient(private val ctx: Context) : ShellBrokerClient {
    private val pkg = "com.termux"
    private val svc = "com.termux.victor.shellbroker.android.ShellBrokerService"
    @Volatile private var broker: IShellBroker? = null
    private val sessions = ConcurrentHashMap<String, Boolean>()

    private fun installed() = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun connect(): IShellBroker? {
        broker?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName, b: IBinder) { broker = IShellBroker.Stub.asInterface(b); latch.countDown() }
            override fun onServiceDisconnected(n: ComponentName) { broker = null }
        }
        val ok = ctx.bindService(Intent().setComponent(ComponentName(pkg, svc)), conn, Context.BIND_AUTO_CREATE)
        if (!ok || !latch.await(5, TimeUnit.SECONDS)) return null
        return broker
    }

    override fun status(): BrokerStatus {
        if (!installed()) return BrokerStatus.of("not_installed", "VictorSuite is not installed")
        val b = connect() ?: return BrokerStatus.of("version_outdated", "this VictorSuite has no shell broker (update it)")
        return try {
            if (b.apiVersion() < 1) return BrokerStatus.of("version_outdated", "VictorSuite broker too old")
            val c = b.capabilities()
            if (!c.getBoolean("enabled")) BrokerStatus.of("permission_needed", "switch on Remote shell access in VictorSuite") else BrokerStatus.ready(c.getBoolean("root_allowed") && c.getBoolean("root_available"))
        } catch (e: Exception) { BrokerStatus.of("unavailable", e.javaClass.simpleName) }
    }

    override fun open(req: BrokerRequest, listener: BrokerListener): BrokerOpen {
        val b = connect() ?: return BrokerOpen.Rejected("unavailable", "VictorSuite broker not reachable")
        val cb = object : IShellSessionCallback.Stub() {
            override fun onState(sessionId: String, state: String, reason: String?, binding: String?) = listener.onState(state, reason, binding)
            override fun onOutput(sessionId: String, data: ByteArray, channel: String) = listener.onOutput(data, channel)
            override fun onExit(sessionId: String, status: Int) = listener.onExit(status)
        }
        val r = Bundle().apply {
            putString("session_id", req.sessionId); putString("peer", req.peer); putString("target", req.target); putString("mode", req.mode); putString("privilege", req.privilege)
            req.command?.let { putString("command", it) }; putInt("cols", req.cols); putInt("rows", req.rows); putString("nonce", req.nonce); putLong("expires_ms", req.expiresMs); putString("binding", req.binding)
            putInt("caller_api_version", 1)
        }
        val code = try { b.open(r, cb) } catch (e: Exception) { return BrokerOpen.Rejected("unavailable", e.javaClass.simpleName) }
        return if (code.isEmpty()) BrokerOpen.Accepted.also { sessions[req.sessionId] = true } else BrokerOpen.Rejected(code, code.replace('_', ' '))
    }

    override fun input(sessionId: String, data: ByteArray) { runCatching { broker?.input(sessionId, data) } }
    override fun resize(sessionId: String, cols: Int, rows: Int) { runCatching { broker?.resize(sessionId, cols, rows) } }
    override fun signal(sessionId: String, name: String) { runCatching { broker?.signal(sessionId, name) } }
    override fun close(sessionId: String, reason: String) { sessions.remove(sessionId); runCatching { broker?.close(sessionId, reason) } }
}

/** Hands drafts to VictorSMS over its existing Messenger bridge (ops message.draft.deliver / message.draft.status). VictorSMS checks our pairing and the DRAFT group. */
class VictorSmsDraftClient(private val ctx: Context) : DraftHandoffClient {
    private val pkg = "com.victorious.sms"
    private val action = "com.victorious.sms.action.BRIDGE"
    private var listener: ((String, DraftState) -> Unit)? = null
    private val tracked = ConcurrentHashMap<String, String>()          // draftId -> last state
    private val poller = HandlerThread("draft-status").also { it.start() }

    init { Handler(poller.looper).postDelayed(object : Runnable { override fun run() { poll(); Handler(poller.looper).postDelayed(this, 4000) } }, 4000) }

    override fun setOutcomeListener(l: (String, DraftState) -> Unit) { listener = l }

    private fun call(op: String, params: JSONObject): JSONObject? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val svc = ctx.packageManager.resolveService(Intent(action).setPackage(pkg), 0)?.serviceInfo ?: return null
        if (svc.packageName != pkg) return null
        val latch = CountDownLatch(1); var out: JSONObject? = null
        val h = Handler(poller.looper)
        val reply = Messenger(object : Handler(poller.looper) { override fun handleMessage(m: Message) { out = runCatching { JSONObject(m.data.getString("response").orEmpty()) }.getOrNull(); latch.countDown() } })
        val conn = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName, b: IBinder) {
                val req = JSONObject().put("protocol_major", 1).put("request_id", java.util.UUID.randomUUID().toString()).put("operation", op).put("parameters", params)
                runCatching { Messenger(b).send(Message.obtain(null, 1).apply { data = Bundle().apply { putString("request", req.toString()) }; replyTo = reply }) }.onFailure { latch.countDown() }
            }
            override fun onServiceDisconnected(n: ComponentName) {}
        }
        if (!ctx.bindService(Intent(action).setPackage(pkg), conn, Context.BIND_AUTO_CREATE)) return null
        try { latch.await(8, TimeUnit.SECONDS) } finally { runCatching { ctx.unbindService(conn) } }
        return out
    }

    override fun status(channel: String): BrokerStatus {
        if (runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isFailure) return BrokerStatus.of("not_installed", "VictorSMS is not installed")
        val r = call("capabilities.get", JSONObject()) ?: return BrokerStatus.of("unavailable", "VictorSMS did not answer (is external control on?)")
        if (!r.optBoolean("ok")) return when (r.optJSONObject("error")?.optString("code")) {
            "not_paired" -> BrokerStatus.of("untrusted", "pair DroidCommand in VictorSMS → Connected apps")
            "external_control_disabled" -> BrokerStatus.of("permission_needed", "switch on external control in VictorSMS")
            "permission_denied" -> BrokerStatus.of("permission_needed", "tick Drafts for DroidCommand in VictorSMS")
            else -> BrokerStatus.of("unavailable", "VictorSMS refused")
        }
        val ops = r.optJSONObject("result")?.optJSONArray("ops") ?: JSONArray()
        val has = (0 until ops.length()).any { ops.getJSONObject(it).optString("name") == "message.draft.deliver" }
        return if (has) BrokerStatus.ready() else BrokerStatus.of("version_outdated", "this VictorSMS cannot receive drafts (update it)")
    }

    override fun deliver(d: IncomingDraft): DraftState {
        val f = d.fields
        fun arr(k: String) = JSONArray().also { a -> (f[k] as? kotlinx.serialization.json.JsonArray)?.forEach { a.put((it as kotlinx.serialization.json.JsonPrimitive).content) } }
        val p = JSONObject().put("draftId", d.draftId).put("channel", d.channel).put("to", arr("to")).put("cc", arr("cc")).put("bcc", arr("bcc")).put("contentHash", d.contentHash).put("origin", d.origin)
            .put("body", (f["body"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()).put("subject", (f["subject"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty())
        (f["account_hint"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { p.put("accountHint", it) }
        (f["attachments"] as? kotlinx.serialization.json.JsonArray)?.let { a -> p.put("attachments", JSONArray().also { out -> a.forEach { x -> val o = x as kotlinx.serialization.json.JsonObject
            out.put(JSONObject().put("name", (o["name"] as kotlinx.serialization.json.JsonPrimitive).content).put("mime", (o["mime"] as kotlinx.serialization.json.JsonPrimitive).content).put("data_b64", (o["data_b64"] as kotlinx.serialization.json.JsonPrimitive).content)) } }) }
        val r = call("message.draft.deliver", p) ?: return DraftState("failed", "VictorSMS did not answer")
        if (!r.optBoolean("ok")) return DraftState("failed", "VictorSMS refused: ${r.optJSONObject("error")?.optString("code")}")
        val st = r.optJSONObject("result")?.optString("state") ?: "received"
        if (st == "received") tracked[d.draftId] = st
        return DraftState(st)
    }

    private fun poll() {
        for (id in tracked.keys.toList()) {
            val r = call("message.draft.status", JSONObject().put("draftId", id)) ?: continue
            val res = r.optJSONObject("result") ?: continue
            val st = res.optString("state")
            if (st.isNotEmpty() && st != "received") { tracked.remove(id); listener?.invoke(id, DraftState(st, res.optString("detail").ifEmpty { null })) }
        }
    }
}
