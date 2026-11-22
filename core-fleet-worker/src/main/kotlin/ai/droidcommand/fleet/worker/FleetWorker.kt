package ai.droidcommand.fleet.worker

import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.ArgsDigest
import ai.droidcommand.security.BatchApprovals
import ai.droidcommand.security.BatchJob
import ai.droidcommand.security.BatchJobContext
import ai.droidcommand.security.BatchJobScope
import ai.droidcommand.security.BatchOffer
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

/**
 * What happened to one job on this phone, for the owner's Activity feed. Carries no job arguments or output: [detail] is only a short
 * reason (an error code and message, or the owner's answer).
 */
data class FleetJobEvent(val jobId: String, val attempt: Int, val op: String, val phase: Phase, val detail: String? = null) {
    enum class Phase { STARTED, WAITING_FOR_OWNER, OWNER_ANSWERED, COVERED_BY_BATCH, COMPLETED, FAILED, STOPPED }
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
 *
 * [batch] is the owner's optional, off-by-default "approve a reviewed batch once" feature. It is never required: with it null, or
 * with its switch off, every job behaves exactly as it always did. When present the worker only (a) tells it which job is running
 * and which plan the coordinator says that job belongs to, around the one tool call, and (b) ends every live batch when this phone
 * is paused, disabled, unpaired or revoked. Whether a tap is replaced is decided by `BatchApprovalPrompt` inside the phone's own
 * `SecureToolExecutor`, never here.
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
    private val batch: BatchApprovals? = null,
    private val onJobEvent: (FleetJobEvent) -> Unit = {},
) {
    private val ops = operations.associateBy { it.op }
    private val running = ConcurrentHashMap<String, RunningInfo>()
    private val outboundLock = Any()
    private val currentJob = ThreadLocal<RunningInfo?>()

    @Volatile private var helloSent = false

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
        if (!on) {
            batch?.revokeAll("fleet worker switched off by the owner")
            cancelAll("worker access disabled by the owner")
        }
    }
    fun setPaused(on: Boolean) {
        store.save(store.load().copy(paused = on))
        if (on) batch?.revokeAll("fleet worker paused by the owner")
        helloSent = false
    }
    fun runningJobs(): List<String> = running.values.map { "${it.jobId}#${it.attempt}" }

    /** Local, owner-initiated forget (the coordinator copy is revoked from VictorLinux). Credentials are wiped. */
    fun unpair() {
        batch?.revokeAll("unpaired from the coordinator by the owner")
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
        val batch = (out + pending).take(FleetProtocol.MAX_BATCH)
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
        batch?.revokeAll("this phone was revoked by the coordinator")
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
    private fun emit(info: RunningInfo, phase: FleetJobEvent.Phase, detail: String? = null) {
        runCatching { onJobEvent(FleetJobEvent(info.jobId, info.attempt, info.op, phase, detail)) }
    }

    /** The terminal reports of a job that is running here are also shown to the owner. */
    private fun reportToOwner(type: String, payload: JsonObject) {
        val job = (payload["job_id"] as? JsonPrimitive)?.contentOrNull ?: return
        val att = (payload["attempt"] as? JsonPrimitive)?.intOrNull ?: return
        val info = running["$job#$att"] ?: return
        when (type) {
            "complete" -> emit(info, FleetJobEvent.Phase.COMPLETED)
            "fail" -> {
                val err = payload["error"] as? JsonObject
                emit(info, FleetJobEvent.Phase.FAILED, "${(err?.get("code") as? JsonPrimitive)?.contentOrNull}: ${(err?.get("message") as? JsonPrimitive)?.contentOrNull}")
            }
            "cancel_ack" -> if ((payload["state"] as? JsonPrimitive)?.contentOrNull == "stopped") emit(info, FleetJobEvent.Phase.STOPPED)
        }
    }

    private fun enqueue(type: String, payload: JsonObject) {
        val dev = store.load().deviceId ?: return
        reportToOwner(type, payload)
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
        emit(info, FleetJobEvent.Phase.STARTED)
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
                val covered = batchContext(op, job, ctx.args, p, wantsRoot)
                if (covered == null) {
                    runner.run(op.toolName, toolInput, isCancelled = { info.cancelled }, initiator = Initiator.REMOTE)
                } else {
                    BatchJobScope.within(covered) {
                        runner.run(op.toolName, toolInput, isCancelled = { info.cancelled }, initiator = Initiator.REMOTE)
                    }
                }
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

    /**
     * What the batch-approval prompt needs to know about the job about to run, or null when no batch may ever apply to it: the feature
     * is not wired in, the operation did not opt in ([FleetOperation.batchable]), or the job needs root (a root job always asks).
     * The offer is the plan the coordinator listed, reduced to what this phone could ever cover, and is dropped when the manifest is
     * unusable or disagrees with this very assignment about the job's arguments. The coordinator's word is only ever a description
     * shown to the owner: coverage is bound to the digest of the arguments this phone actually received.
     */
    private fun batchContext(op: FleetOperation, jobId: String, args: JsonObject, assignment: JsonObject, wantsRoot: Boolean): BatchJobContext? {
        if (batch == null || wantsRoot || !op.batchable) return null
        val digest = ArgsDigest.of(args)
        return BatchJobContext(jobId, op.toolName, digest, offerFor(jobId, digest, assignment))
    }

    private fun offerFor(jobId: String, digest: String, assignment: JsonObject): BatchOffer? {
        val plan = FleetProtocol.parsePlanManifest(assignment) ?: return null
        val mine = plan.jobs.firstOrNull { it.jobId == jobId } ?: return null
        if (ArgsDigest.of(mine.args) != digest || mine.requiresRoot) return null
        val jobs = plan.jobs.filter { !it.requiresRoot }.map { j ->
            val known = ops[j.op]
            // An operation this phone does not have, or has not opted in, still appears so the owner sees it will ask or not run here.
            BatchJob(j.jobId, known?.toolName ?: "fleet.${j.op}", ArgsDigest.of(j.args), "${j.op} ${ArgsDigest.canonical(j.args)}")
        }
        return BatchOffer(plan.planId, "the fleet coordinator", jobs)
    }

    /** Called when the owner's one-time batch approval covered this job's confirmation: tell the coordinator, as a note, never as completion. */
    fun approvalCoveredByBatch() {
        val j = currentJob.get() ?: return
        emit(j, FleetJobEvent.Phase.COVERED_BY_BATCH)
        enqueue(
            "progress",
            buildJsonObject {
                put("job_id", j.jobId)
                put("attempt", j.attempt)
                put("note", "covered by the owner's batch approval on device")
            },
        )
    }

    /** Called by [FleetApprovalPrompt]: the job is waiting for the owner on this device. */
    fun approvalPending(reason: String) {
        val j = currentJob.get() ?: return
        emit(j, FleetJobEvent.Phase.WAITING_FOR_OWNER)
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
        emit(j, FleetJobEvent.Phase.OWNER_ANSWERED, if (approved) "approved" else "declined")
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
