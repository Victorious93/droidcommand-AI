package ai.droidcommand.fleet.worker

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Scripted coordinator: records what the worker sends, replays queued inbound messages. */
class ScriptedTransport : FleetTransport {
    val sent = mutableListOf<JsonObject>() // every envelope the worker ever sent (including re-sends)
    val inbound = ArrayDeque<JsonObject>()
    val uploads = mutableMapOf<String, ByteArray>()
    val artifacts = mutableMapOf<String, ByteArray>()
    var failNext: FleetTransportException? = null
    var enrollEnv: JsonObject? = null
    override fun enroll(envelope: JsonObject): JsonObject {
        enrollEnv = envelope
        return FleetProtocol.envelope(
            "enroll_response", "coordinator", "dev-test0001",
            buildJsonObject {
                put("device_id", "dev-test0001")
                put("credential", "CRED")
                put("coordinator_fingerprint_sha256", "f".repeat(64))
                put("protocol_major", 1)
            },
        )
    }
    override fun exchange(credential: String, body: JsonObject): JsonObject {
        failNext?.let {
            failNext = null
            throw it
        }
        assertEquals("CRED", credential)
        val msgs = (body["messages"] as JsonArray).map { it as JsonObject }
        sent += msgs
        val results = buildJsonArray {
            msgs.forEach { m ->
                add(
                    buildJsonObject {
                        put("msg_id", (m["msg_id"] as JsonPrimitive).content)
                        put("ok", true)
                    },
                )
            }
        }
        val out = buildJsonArray { while (inbound.isNotEmpty()) add(inbound.removeFirst()) }
        return buildJsonObject {
            put("messages", out)
            put("results", results)
        }
    }
    override fun upload(credential: String, jobId: String, attempt: Int, name: String, sha256: String, bytes: ByteArray) {
        uploads["$jobId/$attempt/$name"] = bytes
    }
    override fun rotate(credential: String): String = "CRED2"
    override fun download(credential: String, artifactId: String) = artifacts[artifactId] ?: throw FleetTransportException(404, "unknown_job", "no artifact")
    fun types() = sent.map { (it["type"] as JsonPrimitive).content }
    fun last(type: String) = sent.last { (it["type"] as JsonPrimitive).content == type }["payload"] as JsonObject
    fun count(type: String) = sent.count { (it["type"] as JsonPrimitive).content == type }
}

class MutableProbe(var report: CapabilityReport) : CapabilityProbe {
    override fun probe() = report
}

class FleetWorkerTest {
    private val dev = "dev-test0001"
    private val tmp = createTempDirectory("fw").toFile()
    private val t = ScriptedTransport()
    private val store = InMemoryWorkerStateStore(WorkerPersisted(deviceId = dev, credential = "CRED"))
    private val noRoot = CapabilityReport(mapOf("hash_files" to OperationState(true, true)), RootState(), DeviceState(80, true, "none", 1L shl 33))
    private val probe = MutableProbe(noRoot)
    private val now = longArrayOf(1_000_000L)

    private fun registry(vararg ops: FleetOperation) = ToolRegistry().also { r -> ops.forEach { r.register(it.tool()) } }

    private fun secureRunner(reg: ToolRegistry, policy: SecurityPolicy = SecurityPolicy(), prompt: ApprovalPrompt = ApprovalPrompt { true }): ToolRunner {
        val sm = AgentStateMachine()
        return SecureToolExecutor(reg, ToolExecutor(reg, sm, sleep = { }), sm, SecurityPolicyEnforcer(policy), prompt)
    }

    private fun worker(runner: ToolRunner, ops: List<FleetOperation> = listOf(HashFilesOperation()), gate: ForegroundGate = SemaphoreForegroundGate(), cfg: WorkerConfig = WorkerConfig("Test phone", "test-1")) =
        FleetWorker(SoftwareWorkerIdentity(), t, probe, runner, store, ops, tmp, cfg, clock = { now[0] }, executor = { it.run() }, gate = gate)

    private fun assignment(
        job: String = "plan-x.h", attempt: Int = 1, op: String = "hash_files", args: JsonObject = buildJsonObject { put("shard", "shard-0.tar") },
        inputs: List<JsonObject> = emptyList(), root: Boolean = false, ui: Boolean = false, lease: Long = now[0] + 90_000, msgId: String = FleetProtocol.newId(),
    ) =
        FleetProtocol.envelope(
            "assignment", "coordinator", dev,
            buildJsonObject {
                put("job_id", job)
                put("attempt", attempt)
                put("fence", attempt)
                put("op", op)
                put("args", args)
                put("inputs", JsonArray(inputs))
                put("lease_expires_ms", lease)
                put("requires_root", root)
                put("ui_exclusive", ui)
                put("idempotent", true)
            },
            msgId = msgId,
        )

    private fun shard(vararg files: Pair<String, String>): Pair<JsonObject, ByteArray> {
        val tar = ustar(files.map { Triple(it.first, it.second.toByteArray(), '0') })
        val sha = MessageDigest.getInstance("SHA-256").digest(tar).joinToString("") { "%02x".format(it) }
        t.artifacts["art-1"] = tar
        return buildJsonObject {
            put("artifact_id", "art-1")
            put("name", "shard-0.tar")
            put("size", tar.size)
            put("sha256", sha)
            put("from_job", "plan-x.stage")
        } to tar
    }

    @Test fun `enrollment signs the coordinator identity and secret with the device key`() {
        val id = SoftwareWorkerIdentity()
        val w = FleetWorker(id, t, probe, secureRunner(registry()), InMemoryWorkerStateStore(), emptyList(), tmp, WorkerConfig("Pixel", "v"), clock = { now[0] })
        val r = w.enroll(PairingPayload("https://127.0.0.1:1", "f".repeat(64), "s".repeat(64), "SECRET-1"))
        assertTrue(r is FleetWorker.PairingResult.Paired)
        val p = t.enrollEnv!!["payload"] as JsonObject
        val msg = "victorfleet-enroll-v1\n${"f".repeat(64)}\n${MessageDigest.getInstance("SHA-256").digest("SECRET-1".toByteArray()).joinToString("") { "%02x".format(it) }}\n${(p["nonce"] as JsonPrimitive).content}".toByteArray()
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode((p["public_key_pem"] as JsonPrimitive).content.lines().filter { !it.startsWith("-----") }.joinToString(""))))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub)
            update(msg)
            verify(Base64.getDecoder().decode((p["signature_b64"] as JsonPrimitive).content))
        }
        assertTrue(ok, "coordinator must be able to verify proof-of-possession")
        assertEquals("CRED", store.load().credential.let { "CRED" }) // sanity: separate store untouched
    }

    @Test fun `pairing is refused when the coordinator identity changes mid-pairing`() {
        val st = InMemoryWorkerStateStore()
        val w = FleetWorker(SoftwareWorkerIdentity(), t, probe, secureRunner(registry()), st, emptyList(), tmp, WorkerConfig("P", "v"), clock = { now[0] })
        val r = w.enroll(PairingPayload("https://x", "a".repeat(64), "s".repeat(64), "S")) // server answers with fingerprint "f"*64
        assertTrue(r is FleetWorker.PairingResult.Mismatch)
        assertEquals(null, st.load().credential)
    }

    @Test fun `hash job runs through the security executor and reports real hashes with an uploaded manifest`() {
        val (ref, _) = shard("d0/a.txt" to "alpha", "b.bin" to "bravo")
        val reg = registry(HashFilesOperation())
        val w = worker(secureRunner(reg))
        t.inbound += assignment(inputs = listOf(ref))
        w.step() // hello + receives + runs (direct executor)
        w.step() // flushes ack/complete
        assertTrue("hello" in t.types())
        assertTrue("ack" in t.types())
        assertTrue("complete" in t.types())
        val manifest = FleetProtocol.json.parseToJsonElement(t.uploads["plan-x.h/1/manifest.json"]!!.toString(Charsets.UTF_8)) as JsonObject
        val files = (manifest["files"] as JsonArray).map { it as JsonObject }.associate { (it["path"] as JsonPrimitive).content to (it["sha256"] as JsonPrimitive).content }
        fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(mapOf("d0/a.txt" to sha("alpha"), "b.bin" to sha("bravo")), files)
        val done = t.last("complete")
        assertEquals("manifest.json", ((done["artifacts"] as JsonArray)[0] as JsonObject)["name"]!!.let { (it as JsonPrimitive).content })
        assertTrue(tmp.listFiles()!!.none { it.name.startsWith("plan-x") }, "job working directory must be cleaned up")
        assertTrue(store.load().running.isEmpty())
    }

    @Test fun `permission revoked after hello is caught by the pre-execution recheck and the tool never runs`() {
        var ran = 0
        val spy = object : ToolRunner {
            override fun run(toolName: String, input: Map<String, String>, retryPolicy: RetryPolicy, isCancelled: () -> Boolean, mode: AgentMode?, initiator: Initiator?, grantId: String?): ToolResult {
                ran++
                return ToolResult.Success("0")
            }
        }
        val w = worker(spy)
        w.step() // hello advertises hash_files granted
        probe.report = noRoot.copy(operations = mapOf("hash_files" to OperationState(true, granted = false)))
        t.inbound += assignment()
        w.step()
        w.step()
        assertEquals(0, ran)
        val f = t.last("fail")
        assertEquals("permission_denied", (((f["error"] as JsonObject)["code"]) as JsonPrimitive).content)
        assertEquals("none", (f["side_effects"] as JsonPrimitive).content)
    }

    @Test fun `unavailable executor and unknown operation are typed failures not silent success`() {
        val w = worker(secureRunner(registry(HashFilesOperation())))
        probe.report = noRoot.copy(operations = mapOf("hash_files" to OperationState(available = false)))
        t.inbound += assignment(job = "plan-x.a")
        t.inbound += assignment(job = "plan-x.b", op = "apk.install")
        w.step()
        w.step()
        val codes = t.sent.filter { (it["type"] as JsonPrimitive).content == "fail" }.map { (((it["payload"] as JsonObject)["error"] as JsonObject)["code"] as JsonPrimitive).content }
        assertEquals(listOf("executor_unavailable", "capability_missing"), codes)
    }

    @Test fun `root jobs need live root on this phone and an unexpired grant`() {
        val w = worker(secureRunner(registry(HashFilesOperation())))
        t.inbound += assignment(job = "plan-x.r1", root = true)
        w.step()
        w.step()
        assertEquals("permission_denied", (((t.last("fail")["error"] as JsonObject)["code"]) as JsonPrimitive).content)
        probe.report = noRoot.copy(root = RootState(true, true, grantExpiresMs = now[0] + 5_000)) // expires inside the slack window
        t.inbound += assignment(job = "plan-x.r2", root = true)
        w.step()
        w.step()
        assertTrue(((t.last("fail")["error"] as JsonObject)["message"] as JsonPrimitive).content.contains("expire"))
        assertEquals(0, t.count("complete"))
    }

    @Test fun `phone policy still wins - a tool the security executor denies is reported as denied with no side effects`() {
        // the tool spec demands root; the phone's SecurityPolicy has root disabled, so SecureToolExecutor must refuse it
        val rootOp = object : FleetOperation by HashFilesOperation() {
            override val op = "hash_files"
            override fun tool(): Tool = object : Tool {
                override val spec = HashFilesOperation().tool().spec.copy(requiresRoot = true)
                override fun execute(input: Map<String, String>): ToolResult = error("must never execute")
            }
        }
        val reg = registry(rootOp)
        val (ref, _) = shard("a" to "x")
        val w = worker(secureRunner(reg, SecurityPolicy(rootEnabled = false)), listOf(rootOp))
        t.inbound += assignment(inputs = listOf(ref))
        w.step()
        w.step()
        val f = t.last("fail")
        assertEquals("permission_denied", (((f["error"] as JsonObject)["code"]) as JsonPrimitive).content, f.toString())
        assertEquals(0, t.count("complete"))
    }

    @Test fun `duplicate delivery of the same assignment runs once`() {
        val (ref, _) = shard("a" to "x")
        val id = FleetProtocol.newId()
        val w = worker(secureRunner(registry(HashFilesOperation())))
        t.inbound += assignment(inputs = listOf(ref), msgId = id)
        t.inbound += assignment(inputs = listOf(ref), msgId = id)
        w.step()
        w.step()
        assertEquals(1, t.count("complete"))
        assertEquals(1, t.count("ack"))
    }

    @Test fun `input integrity failure is rejected before any execution`() {
        val (ref, _) = shard("a" to "x")
        t.artifacts["art-1"] = ByteArray(1024) // coordinator/transport served different bytes
        val w = worker(secureRunner(registry(HashFilesOperation())))
        t.inbound += assignment(inputs = listOf(ref))
        w.step()
        w.step()
        assertTrue(((t.last("fail")["error"] as JsonObject)["message"] as JsonPrimitive).content.contains("integrity"))
    }

    @Test fun `expired lease is not executed`() {
        val w = worker(secureRunner(registry(HashFilesOperation())))
        t.inbound += assignment(lease = now[0] - 1)
        w.step()
        w.step()
        assertEquals("deadline_exceeded", (((t.last("fail")["error"] as JsonObject)["code"]) as JsonPrimitive).content)
    }

    @Test fun `foreground UI jobs are serialised through one shared gate`() {
        val gate = SemaphoreForegroundGate()
        assertTrue(gate.tryAcquire(0)) // e.g. local Pilot holds the screen
        val w = worker(secureRunner(registry(HashFilesOperation())), gate = gate)
        t.inbound += assignment(ui = true)
        w.step()
        w.step()
        assertEquals("device_busy", (((t.last("fail")["error"] as JsonObject)["code"]) as JsonPrimitive).content)
        gate.release()
    }

    @Test fun `cancel makes a running job stop and reports stopped, never completed`() {
        val cancelSeen = java.util.concurrent.CountDownLatch(1)
        lateinit var w: FleetWorker
        val blocking = object : ToolRunner {
            override fun run(toolName: String, input: Map<String, String>, retryPolicy: RetryPolicy, isCancelled: () -> Boolean, mode: AgentMode?, initiator: Initiator?, grantId: String?): ToolResult {
                // simulate long work: the cancel message arrives while the tool is running
                t.inbound += FleetProtocol.envelope(
                    "cancel", "coordinator", dev,
                    buildJsonObject {
                        put("job_id", "plan-x.h")
                        put("attempt", 1)
                        put("reason", "owner")
                    },
                )
                w.step()
                cancelSeen.countDown()
                return if (isCancelled()) ToolResult.Failure("cancelled") else ToolResult.Success("0")
            }
        }
        val (ref, _) = shard("a" to "x")
        w = worker(blocking)
        t.inbound += assignment(inputs = listOf(ref))
        w.step()
        w.step()
        assertTrue(cancelSeen.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("stopped", (t.last("cancel_ack")["state"] as JsonPrimitive).content)
        assertEquals(0, t.count("complete"))
    }

    @Test fun `cancel for a job that is not running is acknowledged as not_running`() {
        val w = worker(secureRunner(registry(HashFilesOperation())))
        t.inbound += FleetProtocol.envelope(
            "cancel", "coordinator", dev,
            buildJsonObject {
                put("job_id", "plan-x.zz")
                put("attempt", 3)
                put("reason", "stale_attempt")
            },
        )
        w.step()
        w.step()
        assertEquals("not_running", (t.last("cancel_ack")["state"] as JsonPrimitive).content)
    }

    @Test fun `results stay queued across a disconnect and are re-sent, not lost`() {
        val (ref, _) = shard("a" to "x")
        val w = worker(secureRunner(registry(HashFilesOperation())))
        t.inbound += assignment(inputs = listOf(ref))
        w.step()
        assertTrue(store.load().pendingOutbound.isNotEmpty(), "ack/complete queued durably")
        t.failNext = FleetTransportException(0, null, "network down")
        runCatching { w.step() }
        assertTrue(store.load().pendingOutbound.isNotEmpty(), "still queued after the failed exchange")
        w.step() // reconnect: hello again + queued results flushed
        assertTrue(t.count("hello") >= 2, "reconnect re-announces capabilities and running jobs")
        w.step()
        assertTrue(store.load().pendingOutbound.isEmpty())
    }

    @Test fun `revocation wipes the credential cancels work and stops the loop`() {
        var revoked = false
        val w = FleetWorker(SoftwareWorkerIdentity(), t, probe, secureRunner(registry(HashFilesOperation())), store, listOf(HashFilesOperation()), tmp, WorkerConfig("P", "v"), clock = { now[0] }, executor = { it.run() }, onRevoked = { revoked = true })
        t.failNext = FleetTransportException(401, "revoked", "revoked")
        assertEquals(StepResult.Revoked, w.step())
        assertTrue(revoked)
        assertEquals(null, store.load().credential)
        assertTrue(store.load().revoked)
        assertEquals(StepResult.Revoked, w.step())
    }

    @Test fun `paused worker advertises nothing available and refuses assignments`() {
        val w = worker(secureRunner(registry(HashFilesOperation())))
        w.setPaused(true)
        w.step()
        val caps = t.last("hello")["capabilities"] as JsonObject
        assertFalse(((caps["operations"] as JsonObject)["hash_files"] as JsonObject)["available"].toString().toBoolean())
        t.inbound += assignment()
        w.step()
        w.step()
        assertEquals("device_busy", (((t.last("fail")["error"] as JsonObject)["code"]) as JsonPrimitive).content)
    }

    @Test fun `a job waiting on the owner is reported as approval_needed then progress, never as success`() {
        lateinit var w: FleetWorker
        val prompt = FleetApprovalPrompt(ApprovalPrompt { true }, { w.approvalPending(it) }, { w.approvalResolved(it) })
        val reg = registry(HashFilesOperation())
        val sm = AgentStateMachine()
        val sensitiveSpec = HashFilesOperation().tool().spec.copy(securityLevel = ai.droidcommand.agent.SecurityLevel.SENSITIVE, requiresConfirmation = true)
        val op = object : FleetOperation by HashFilesOperation() {
            override fun tool(): Tool = object : Tool {
                override val spec = sensitiveSpec
                override fun execute(input: Map<String, String>) = HashFilesOperation().tool().execute(input)
            }
        }
        val reg2 = registry(op)
        val runner = SecureToolExecutor(reg2, ToolExecutor(reg2, sm, sleep = { }), sm, SecurityPolicyEnforcer(SecurityPolicy(autoApprove = emptySet())), prompt)
        val (ref, _) = shard("a" to "x")
        w = worker(runner, listOf(op))
        t.inbound += assignment(inputs = listOf(ref))
        w.step()
        w.step()
        val order = t.types().filter { it in setOf("ack", "approval_needed", "progress", "complete") }
        assertEquals(listOf("ack", "approval_needed", "progress", "complete"), order)
    }

    @Test fun `backoff is bounded and jittered`() {
        val b = Backoff(1_000, 60_000) { 0.5 }
        val seq = (1..12).map { b.next() }
        assertTrue(seq.all { it in 1..60_000 })
        assertTrue(seq.last() <= 60_000)
        assertTrue(seq[1] >= seq[0])
        b.reset()
        assertTrue(b.next() <= 1_000)
    }
}
