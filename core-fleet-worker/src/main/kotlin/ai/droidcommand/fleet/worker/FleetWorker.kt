package ai.droidcommand.fleet.worker

import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.security.ApprovalPrompt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** What the owner sees and confirms during pairing; never contains a long-lived secret other than the single-use secret. */
data class PairingPayload(val coordinatorUrl: String, val certFingerprintSha256: String, val spkiSha256: String, val pairingSecret: String)

data class WorkerConfig(
    val deviceName: String,
    val appVersion: String,
    val heartbeatMs: Long = 15_000,
    val longPollSeconds: Int = 20,
    val activePollSeconds: Int = 1,
    val rootSlackMs: Long = 60_000,
    val maxSeenInbound: Int = 512,
)

/** Bounded exponential backoff with jitter (no unbounded retry storms against a coordinator that is down). */
class Backoff(private val baseMs: Long = 1_000, private val maxMs: Long = 60_000, private val random: () -> Double = { Math.random() }) {
    private var attempt = 0
    fun next(): Long {
        val cap = minOf(maxMs, baseMs shl minOf(attempt, 20))
        attempt++
        return (cap / 2 + (random() * cap / 2)).toLong().coerceAtLeast(1)
    }
    fun reset() {
        attempt = 0
    }
}

/** Serialises foreground UI control on this phone: local Pilot, worker jobs and the screen-control adapter share ONE gate. */
interface ForegroundGate {
    fun tryAcquire(timeoutMs: Long): Boolean
    fun release()
}

class SemaphoreForegroundGate : ForegroundGate {
    private val s = Semaphore(1)
    override fun tryAcquire(timeoutMs: Long) = s.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
    override fun release() = s.release()
}

/** Wraps the app's approval prompt so the coordinator is told when a job is waiting on the owner (never reported as success). */
class FleetApprovalPrompt(private val delegate: ApprovalPrompt, private val pending: (reason: String) -> Unit, private val resolved: (approved: Boolean) -> Unit) : ApprovalPrompt {
    override fun requestApproval(reason: String): Boolean = ask(reason) { delegate.requestApproval(reason) }
    override fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean = ask(reason) { delegate.requestApproval(toolName, input, reason) }
    private fun ask(reason: String, block: () -> Boolean): Boolean {
        pending(reason)
        val ok = try {
            block()
        } catch (e: Throwable) {
            resolved(false)
            throw e
        }
        resolved(ok)
        return ok
    }
}

sealed class StepResult {
    object NotPaired : StepResult()
    object Revoked : StepResult()
    data class Ok(val inbound: Int) : StepResult()
}

private class RunningInfo(val jobId: String, val attempt: Int, val op: String, val startedMs: Long) {
    @Volatile var cancelled = false
}

/**
 * The phone side of the fleet protocol. It never schedules anything: it accepts an assignment from the single coordinator,
 * re-checks live permissions, then hands execution to the device's [ToolRunner] (SecureToolExecutor in the app), so the
 * phone's own policy, approvals, grants and audit remain authoritative. Reports are fenced by (job, attempt).
 */
class FleetWorker(
    private val identity: WorkerIdentity,
    private val transport: FleetTransport,
    private val probe: CapabilityProbe,
    private val runner: ToolRunner,
    private val store: WorkerStateStore,
    operations: List<FleetOperation>,
    private val workRoot: File,
    private val config: WorkerConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: Executor = Executor { Thread(it, "fleet-job").apply { isDaemon = true }.start() },
    private val gate: ForegroundGate = SemaphoreForegroundGate(),
    private val onNodeMessage: (fromDevice: String, topic: String, body: JsonObject) -> Unit = { _, _, _ -> },
    private val onRevoked: () -> Unit = {},
    /** Protocol 1.1 terminal / draft handler. Null = this build does not offer those features (it then reports none of them). */
    liveFactory: ((sendStatus: (String, JsonObject) -> Unit, relay: TerminalRelay) -> LiveFeatures)? = null,
) {
    private val ops = operations.associateBy { it.op }
    private val running = ConcurrentHashMap<String, RunningInfo>()
    private val outboundLock = Any()
    private val currentJob = ThreadLocal<RunningInfo?>()

    @Volatile private var helloSent = false

    // Live (non-durable) status messages for terminals/drafts. Held in memory, retried only until acknowledged or 60 s old; never persisted,
    // so a restart can never revive an approval or a session.
    private val liveOutbound = java.util.ArrayList<Pair<Long, JsonObject>>()
    private val live: LiveFeatures? = liveFactory?.invoke(
        { type, payload ->
            val dev = store.load().deviceId
            if (dev != null) synchronized(liveOutbound) { liveOutbound += clock() + 60_000 to env(dev, type, payload) }
        },
        TerminalRelay { sid, body -> transport.terminalExchange(store.load().credential ?: throw FleetTransportException(401, "unauthorized", "not paired"), sid, body) },
    )

    @Volatile var enabled = true
        private set
    private var lastHeartbeat = 0L
    private val pendingAcks = mutableListOf<String>()

    // ------------------------------------------------------------------ pairing

    /** Call only after the owner has confirmed [PairingPayload.certFingerprintSha256] matches the coordinator's screen. */
    fun enroll(payload: PairingPayload, transportForPairing: FleetTransport = transport): PairingResult {
        val nonce = FleetProtocol.newId()
        val proof = payload.pairingSecret
        val secretHash = sha256Hex(proof.toByteArray())
        val signed = "victorfleet-enroll-v1\n${payload.certFingerprintSha256}\n$secretHash\n$nonce".toByteArray()
        val sig = Base64.getEncoder().encodeToString(identity.sign(signed))
        val body = buildJsonObject {
            put("device_name", config.deviceName)
            put("public_key_pem", identity.publicKeyPem())
            put("pairing_proof", proof)
            put("signature_b64", sig)
            put("nonce", nonce)
        }
        FleetProtocol.validateWorkerPayload("enroll_request", body)
        val env = FleetProtocol.envelope("enroll_request", "unenrolled", "coordinator", body, nowMs = clock())
        val resp = transportForPairing.enroll(env)
        val p = FleetProtocol.validateEnvelope(resp)["payload"] as JsonObject
        val id = (p["device_id"] as JsonPrimitive).content
        val cred = (p["credential"] as JsonPrimitive).content
        val fpr = (p["coordinator_fingerprint_sha256"] as JsonPrimitive).content
        if (fpr != payload.certFingerprintSha256) return PairingResult.Mismatch("coordinator identity changed during pairing")
        store.save(
            store.load().copy(
                deviceId = id, credential = cred, coordinatorUrl = payload.coordinatorUrl,
                coordinatorSpkiSha256 = payload.spkiSha256, revoked = false,
            ),
        )
        helloSent = false
        return PairingResult.Paired(id)
    }

    sealed class PairingResult {
        data class Paired(val deviceId: String) : PairingResult()
        data class Mismatch(val why: String) : PairingResult()
    }

    // ------------------------------------------------------------------ control

    /** Owner toggle "allow fleet jobs on this phone". Disabling cancels running jobs and stops accepting work. */
    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) { live?.shutdown("worker access disabled by the owner"); cancelAll("worker access disabled by the owner") }
    }
    fun setPaused(on: Boolean) {
        store.save(store.load().copy(paused = on))
        helloSent = false
    }
    fun runningJobs(): List<String> = running.values.map { "${it.jobId}#${it.attempt}" }

    /** Local, owner-initiated forget (the coordinator copy is revoked from VictorLinux). Credentials are wiped. */
    fun unpair() {
        live?.shutdown("unpaired by the owner")
        cancelAll("unpaired by the owner")
        store.save(WorkerPersisted(seenInbound = emptyList()))
        helloSent = false
    }

    private fun cancelAll(reason: String) {
        running.values.forEach { it.cancelled = true }
    }

    // ------------------------------------------------------------------ loop

    /** One protocol round trip. Pure of threads/sleeps so it is deterministic in tests. */
    fun step(): StepResult {
        val st = store.load()
        val cred = st.credential
        val dev = st.deviceId
        if (st.revoked) return StepResult.Revoked
        if (cred == null || dev == null) return StepResult.NotPaired
        val now = clock()
        val out = mutableListOf<JsonObject>()
        if (!helloSent) {
            out += hello(dev, st)
        } else if (now - lastHeartbeat >= config.heartbeatMs) {
            out += heartbeat(dev)
        }
        val pending = synchronized(outboundLock) { store.load().pendingOutbound.map { FleetProtocol.json.parseToJsonElement(it) as JsonObject } }
        val liveMsgs = synchronized(liveOutbound) { liveOutbound.removeAll { it.first < now }; liveOutbound.map { it.second } }
        val batch = (out + pending + liveMsgs).take(FleetProtocol.MAX_BATCH)
        val acks = synchronized(pendingAcks) { pendingAcks.toList() }
        val wait = if (running.isEmpty() && pending.isEmpty()) config.longPollSeconds else config.activePollSeconds
        val resp = try {
            transport.exchange(
                cred,
                buildJsonObject {
                    put("messages", JsonArray(batch))
                    put("acks", buildJsonArray { acks.forEach { add(JsonPrimitive(it)) } })
                    put("wait_s", wait)
                },
            )
        } catch (e: FleetTransportException) {
            if (e.isRevoked) {
                handleRevoked()
                return StepResult.Revoked
            }
            helloSent = false // any transport failure: re-announce capabilities and running jobs on reconnect
            throw e
        }
        synchronized(pendingAcks) { pendingAcks.removeAll(acks.toSet()) }
        if (out.isNotEmpty() && out.first()["type"].toString().contains("hello")) helloSent = true
        if (out.isNotEmpty()) lastHeartbeat = now
        reconcileOutbound(batch, resp["results"] as? JsonArray)
        reconcileLive(liveMsgs, resp["results"] as? JsonArray)
        val inbound = (resp["messages"] as? JsonArray) ?: JsonArray(emptyList())
        for (m in inbound) handleInbound(m as JsonObject)
        return StepResult.Ok(inbound.size)
    }

    /** Keeps retrying until stopped: bounded backoff, re-hello after any failure, stops cleanly on revocation. */
    fun runLoop(stop: () -> Boolean, sleeper: (Long) -> Unit = { Thread.sleep(it) }, backoff: Backoff = Backoff()) {
        while (!stop()) {
            if (!enabled) {
                sleeper(1_000)
                continue
            }
            try {
                when (step()) {
                    StepResult.NotPaired, StepResult.Revoked -> return
                    is StepResult.Ok -> backoff.reset()
                }
            } catch (e: FleetTransportException) {
                helloSent = false
                if (e.isUnauthorized && !e.isRevoked) return // credential rejected: needs the owner (re-pair), do not hammer
                sleeper(backoff.next())
            }
        }
    }

    private fun handleRevoked() {
        live?.shutdown("device revoked by the coordinator")      // a revoked phone ends any live shell at once
        cancelAll("device revoked by the coordinator")
        store.save(WorkerPersisted(revoked = true))
        onRevoked()
    }

    // ------------------------------------------------------------- outbound
    private fun hello(dev: String, st: WorkerPersisted): JsonObject {
        val rep = probe.probe()
        val caps = if (st.paused || !enabled) rep.copy(operations = rep.operations.mapValues { it.value.copy(available = false, reason = "worker paused/disabled by owner") }) else rep
        val body = buildJsonObject {
            put("capabilities", caps.toJson())
            put("app_version", config.appVersion)
            put(
                "running",
                buildJsonArray {
                    running.values.forEach { r ->
                        add(
                            buildJsonObject {
                                put("job_id", r.jobId)
                                put("attempt", r.attempt)
                            },
                        )
                    }
                },
            )
        }
        return env(dev, "hello", body)
    }

    private fun heartbeat(dev: String): JsonObject = env(dev, "heartbeat", buildJsonObject { put("device_state", probe.probe().deviceJson()) })

    private fun env(dev: String, type: String, payload: JsonObject): JsonObject {
        FleetProtocol.validateWorkerPayload(type, payload)
        return FleetProtocol.envelope(type, dev, "coordinator", payload, nowMs = clock())
    }

    /** Reliable results: queued durably, re-sent after any disconnect, coordinator de-duplicates by msg_id. */
    private fun enqueue(type: String, payload: JsonObject) {
        val dev = store.load().deviceId ?: return
        val e = env(dev, type, payload)
        synchronized(outboundLock) {
            val s = store.load()
            store.save(s.copy(pendingOutbound = s.pendingOutbound + e.toString()))
        }
    }

    private fun reconcileOutbound(sent: List<JsonObject>, results: JsonArray?) {
        val byId = results?.mapNotNull { it as? JsonObject }?.associateBy { (it["msg_id"] as? JsonPrimitive)?.contentOrNull } ?: return
        val done = mutableSetOf<String>()
        for (m in sent) {
            val id = (m["msg_id"] as JsonPrimitive).content
            val r = byId[id] ?: continue
            val ok = (r["ok"] as? JsonPrimitive)?.contentOrNull == "true"
            val code = ((r["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
            // transient problems stay queued; permanent ones (stale attempt, malformed) are dropped, never retried forever
            if (ok || code in setOf("stale_attempt", "unknown_job", "bad_request", "too_large", "unknown_type", "unauthorized")) done += id
        }
        if (done.isNotEmpty()) {
            synchronized(outboundLock) {
                val s = store.load()
                store.save(s.copy(pendingOutbound = s.pendingOutbound.filterNot { p -> (FleetProtocol.json.parseToJsonElement(p) as JsonObject)["msg_id"].let { (it as JsonPrimitive).content } in done }))
            }
        }
    }

    private fun reconcileLive(sent: List<JsonObject>, results: JsonArray?) {
        val ids = results?.mapNotNull { it as? JsonObject }?.filter { (it["ok"] as? JsonPrimitive)?.contentOrNull == "true" || it["error"] != null }
            ?.mapNotNull { (it["msg_id"] as? JsonPrimitive)?.contentOrNull }?.toSet() ?: return
        synchronized(liveOutbound) { liveOutbound.removeAll { (it.second["msg_id"] as JsonPrimitive).content in ids } }
    }

    // -------------------------------------------------------------- inbound
    private fun handleInbound(m: JsonObject) {
        val id = (m["msg_id"] as? JsonPrimitive)?.contentOrNull ?: return
        synchronized(pendingAcks) { pendingAcks += id }
        val st = store.load()
        if (id in st.seenInbound) return // duplicate delivery
        store.save(st.copy(seenInbound = (st.seenInbound + id).takeLast(config.maxSeenInbound)))
        val dev = st.deviceId ?: return
        try {
            FleetProtocol.validateEnvelope(m)
            if ((m["target"] as JsonPrimitive).content != dev || (m["sender"] as JsonPrimitive).content != "coordinator") return
        } catch (e: FleetProtocolException) {
            return
        }
        val p = m["payload"] as JsonObject
        when ((m["type"] as JsonPrimitive).content) {
            "assignment" -> onAssignment(p)
            "cancel" -> onCancel(p)
            "revoke" -> handleRevoked()
            "terminal_open", "terminal_close", "draft_deliver" -> live?.onInbound((m["type"] as JsonPrimitive).content, p)
            "node_message" -> runCatching { onNodeMessage((p["from_device"] as JsonPrimitive).content, (p["topic"] as JsonPrimitive).content, p["body"] as JsonObject) }
        }
    }

    private fun onCancel(p: JsonObject) {
        val job = (p["job_id"] as JsonPrimitive).content
        val att = (p["attempt"] as JsonPrimitive).intOrNull ?: return
        val r = running["$job#$att"]
        if (r != null) {
            r.cancelled = true
        } else {
            enqueue(
                "cancel_ack",
                buildJsonObject {
                    put("job_id", job)
                    put("attempt", att)
                    put("state", "not_running")
                },
            )
        }
    }

    private fun onAssignment(p: JsonObject) {
        try {
            FleetProtocol.validateAssignment(p)
        } catch (e: FleetProtocolException) {
            val j = (p["job_id"] as? JsonPrimitive)?.contentOrNull
            val a = (p["attempt"] as? JsonPrimitive)?.intOrNull
            if (j != null && a != null && FleetProtocol.ID_RE.matches(j)) failJob(j, a, "bad_request", "malformed assignment: ${e.message}", "none")
            return
        }
        val job = (p["job_id"] as JsonPrimitive).content
        val att = (p["attempt"] as JsonPrimitive).int()
        val key = "$job#$att"
        if (running.containsKey(key)) return
        val info = RunningInfo(job, att, (p["op"] as JsonPrimitive).content, clock())
        running[key] = info
        store.save(store.load().let { s -> s.copy(running = s.running + RunningJob(job, att, info.op, info.startedMs)) })
        executor.execute {
            try {
                runJob(info, p)
            } finally {
                finish(info)
            }
        }
    }

    private fun finish(info: RunningInfo) {
        running.remove("${info.jobId}#${info.attempt}")
        store.save(store.load().let { s -> s.copy(running = s.running.filterNot { it.jobId == info.jobId && it.attempt == info.attempt }) })
    }

    // ---------------------------------------------------------------- jobs
    private fun runJob(info: RunningInfo, p: JsonObject) {
        val job = info.jobId
        val att = info.attempt
        val lease = (p["lease_expires_ms"] as JsonPrimitive).longOrNull ?: 0
        val op = ops[info.op]
        if (clock() >= lease) return failJob(job, att, "deadline_exceeded", "assignment lease already expired", "none")
        if (op == null) return failJob(job, att, "capability_missing", "operation '${info.op}' is not implemented on this phone", "none")
        if (store.load().paused || !enabled) return failJob(job, att, "device_busy", "worker is paused or disabled by the owner", "none")
        // live permission re-check, immediately before execution (not the capability report from hello)
        val rep = probe.probe()
        val os = rep.operations[info.op]
        if (os == null || !os.available) return failJob(job, att, "executor_unavailable", "no working executor for '${info.op}' right now", "none")
        if (!os.granted) return failJob(job, att, "permission_denied", "permission for '${info.op}' is not currently granted", "none")
        val wantsRoot = (p["requires_root"] as? JsonPrimitive)?.contentOrNull == "true"
        if (wantsRoot) {
            val r = rep.root
            if (!r.available || !r.granted) return failJob(job, att, "permission_denied", "root is not granted on this phone", "none")
            if (r.grantExpiresMs != null && r.grantExpiresMs <= clock() + config.rootSlackMs) return failJob(job, att, "permission_denied", "root grant expired or about to expire", "none")
        }
        val ui = (p["ui_exclusive"] as? JsonPrimitive)?.contentOrNull == "true"
        if (ui && !gate.tryAcquire(2_000)) return failJob(job, att, "device_busy", "foreground UI control is in use on this phone", "none")
        val dir = File(workRoot, "$job-$att".replace(Regex("[^A-Za-z0-9._-]"), "_"))
        try {
            enqueue(
                "ack",
                buildJsonObject {
                    put("job_id", job)
                    put("attempt", att)
                },
            )
            val cred = store.load().credential ?: return
            val inDir = File(dir, "in").apply { mkdirs() }
            val outDir = File(dir, "out").apply { mkdirs() }
            val inputs = mutableMapOf<String, ByteArray>()
            for (i in (p["inputs"] as? JsonArray).orEmpty()) {
                val io = i as JsonObject
                val bytes = transport.download(cred, (io["artifact_id"] as JsonPrimitive).content)
                if (sha256Hex(bytes) != (io["sha256"] as JsonPrimitive).content || bytes.size.toLong() != (io["size"] as JsonPrimitive).longOrNull) {
                    return failJob(job, att, "bad_request", "input '${(io["name"] as JsonPrimitive).content}' failed its integrity check", "none")
                }
                inputs[(io["name"] as JsonPrimitive).content] = bytes
            }
            val ctx = JobContext(job, att, inDir, outDir, p["args"] as JsonObject, inputs)
            val toolInput = try {
                op.prepare(ctx)
            } catch (e: Exception) {
                return failJob(job, att, "bad_request", "cannot prepare job: ${e.message}".take(400), "none")
            }
            currentJob.set(info)
            ExecutionMarker.reset()
            val result = try {
                runner.run(op.toolName, toolInput, isCancelled = { info.cancelled }, initiator = Initiator.REMOTE)
            } finally {
                currentJob.remove()
            }
            when (result) {
                is ToolResult.Success -> {
                    val files = op.outputs(ctx)
                    val refs = buildJsonArray {
                        for (f in files) {
                            val b = f.readBytes()
                            val sha = sha256Hex(b)
                            transport.upload(cred, job, att, f.name, sha, b)
                            add(
                                buildJsonObject {
                                    put("name", f.name)
                                    put("size", b.size)
                                    put("sha256", sha)
                                },
                            )
                        }
                    }
                    enqueue(
                        "complete",
                        buildJsonObject {
                            put("job_id", job)
                            put("attempt", att)
                            put("result", op.result(ctx, result.output))
                            put("artifacts", refs)
                        },
                    )
                }
                is ToolResult.Failure -> if (info.cancelled) {
                    enqueue(
                        "cancel_ack",
                        buildJsonObject {
                            put("job_id", job)
                            put("attempt", att)
                            put("state", "stopped")
                        },
                    )
                } else {
                    // refused by the phone's policy BEFORE the tool ran => nothing happened. Only trusted when the operation marks execution start.
                    val refusedBeforeRun = op.marksExecution && !ExecutionMarker.hasStarted()
                    val denied = refusedBeforeRun || Regex("denied|not available in|requires initiator|authoriz", RegexOption.IGNORE_CASE).containsMatchIn(result.reason) && !ExecutionMarker.hasStarted()
                    if (denied) {
                        failJob(job, att, "permission_denied", result.reason.take(400), "none")
                    } else {
                        failJob(job, att, "internal", result.reason.take(400), "possible")
                    }
                }
                is ToolResult.Partial -> failJob(job, att, "internal", "partial result: ${result.reason}".take(400), "possible")
                is ToolResult.Unexpected -> failJob(job, att, "internal", "unexpected: ${result.description}".take(400), "possible")
            }
        } catch (e: FleetTransportException) {
            // transport trouble mid-job: outcome of what already ran is unknown to the coordinator; report honestly when we can
            failJob(job, att, "internal", "transport error while running: ${e.message}".take(400), "possible")
        } catch (e: Exception) {
            failJob(job, att, "internal", "${e.javaClass.simpleName}: ${e.message}".take(400), "possible")
        } finally {
            if (ui) gate.release()
            dir.deleteRecursively()
        }
    }

    private fun failJob(job: String, att: Int, code: String, message: String, side: String) =
        enqueue(
            "fail",
            buildJsonObject {
                put("job_id", job)
                put("attempt", att)
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        put("message", message)
                    },
                )
                put("side_effects", side)
            },
        )

    /** Called by [FleetApprovalPrompt]: the job is waiting for the owner on this device. */
    fun approvalPending(reason: String) {
        val j = currentJob.get() ?: return
        enqueue(
            "approval_needed",
            buildJsonObject {
                put("job_id", j.jobId)
                put("attempt", j.attempt)
                put("reason", reason.take(250))
            },
        )
    }

    fun approvalResolved(approved: Boolean) {
        val j = currentJob.get() ?: return
        enqueue(
            "progress",
            buildJsonObject {
                put("job_id", j.jobId)
                put("attempt", j.attempt)
                put("note", if (approved) "owner approved on device" else "owner declined on device")
            },
        )
    }

    private fun JsonPrimitive.int(): Int = intOrNull ?: error("not an int")
    private fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
