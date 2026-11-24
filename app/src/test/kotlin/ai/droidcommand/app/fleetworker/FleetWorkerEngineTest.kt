package ai.droidcommand.app.fleetworker

import ai.droidcommand.activity.ActivityKind
import ai.droidcommand.activity.InMemoryActivityLog
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.fleet.worker.CapabilityProbe
import ai.droidcommand.fleet.worker.CapabilityReport
import ai.droidcommand.fleet.worker.DeviceState
import ai.droidcommand.fleet.worker.FleetOperation
import ai.droidcommand.fleet.worker.FleetProtocol
import ai.droidcommand.fleet.worker.FleetTransport
import ai.droidcommand.fleet.worker.FleetTransportException
import ai.droidcommand.fleet.worker.InMemoryWorkerStateStore
import ai.droidcommand.fleet.worker.JobContext
import ai.droidcommand.fleet.worker.OperationState
import ai.droidcommand.fleet.worker.PairingPayload
import ai.droidcommand.fleet.worker.RootState
import ai.droidcommand.fleet.worker.SoftwareWorkerIdentity
import ai.droidcommand.fleet.worker.WorkerPersisted
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.BatchOfferHandler
import ai.droidcommand.security.InMemoryAuditLog
import ai.droidcommand.security.SecurityPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The phone-side engine driven end to end by a scripted coordinator, on the JVM. It proves the wiring (switches, pairing, the
 * job pipeline, batch approval, the Activity feed); it does not prove anything about Android (Keystore, service, battery).
 */
class FleetWorkerEngineTest {
    private val tmp = createTempDirectory("engine").toFile()
    private val dev = "dev-engine01"
    private val paired = WorkerPersisted(deviceId = dev, credential = "CRED", coordinatorUrl = "https://10.0.0.2:8443", coordinatorSpkiSha256 = "a".repeat(64))
    private val store = InMemoryWorkerStateStore(paired)
    private val activity = InMemoryActivityLog()
    private val audit = InMemoryAuditLog()
    private val settings = InMemoryFleetWorkerSettings(worker = true, batch = true)
    private val taps = AtomicInteger()
    private val ran = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var offerAnswer: Duration? = Duration.ofMinutes(10)
    private val offers = AtomicInteger()

    /** A confirmation-asking operation that opts in to batches (or not), needing no input artifacts. */
    private inner class NoteOp(override val batchable: Boolean) : FleetOperation {
        override val op = "note_save"
        override val marksExecution = true
        override fun prepare(ctx: JobContext): Map<String, String> = mapOf("title" to ((ctx.args["title"] as? JsonPrimitive)?.content ?: "?"))
        override fun tool(): Tool = object : Tool {
            override val spec = ToolSpec(
                name = toolName,
                description = "Save a note.",
                securityLevel = SecurityLevel.SENSITIVE,
                requiresConfirmation = true,
                requiredInitiator = setOf(Initiator.REMOTE),
            )

            override fun execute(input: Map<String, String>): ToolResult {
                ai.droidcommand.fleet.worker.ExecutionMarker.mark()
                ran += input["title"].orEmpty()
                return ToolResult.Success("1")
            }
        }
    }

    private class Scripted(val inbound: ArrayDeque<JsonObject> = ArrayDeque()) : FleetTransport {
        val sent = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
        var enrollResponseFingerprint: String? = null
        var failEnroll: FleetTransportException? = null
        override fun enroll(envelope: JsonObject): JsonObject {
            failEnroll?.let { throw it }
            return FleetProtocol.envelope(
                "enroll_response", "coordinator", "dev-new00001",
                buildJsonObject {
                    put("device_id", "dev-new00001")
                    put("credential", "NEWCRED")
                    put("coordinator_fingerprint_sha256", enrollResponseFingerprint ?: "f".repeat(64))
                    put("protocol_major", 1)
                },
            )
        }

        override fun exchange(credential: String, body: JsonObject): JsonObject {
            Thread.sleep(5)
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
            val out = buildJsonArray { synchronized(inbound) { while (inbound.isNotEmpty()) add(inbound.removeFirst()) } }
            return buildJsonObject {
                put("messages", out)
                put("results", results)
            }
        }

        override fun upload(credential: String, jobId: String, attempt: Int, name: String, sha256: String, bytes: ByteArray) = Unit

        override fun download(credential: String, artifactId: String) = ByteArray(0)

        override fun rotate(credential: String) = "CRED2"

        fun count(type: String) = synchronized(sent) { sent.count { (it["type"] as JsonPrimitive).content == type } }
    }

    private val transport = Scripted()

    private fun engine(batchable: Boolean = true, appPromptAnswer: Boolean = true): FleetWorkerEngine {
        val probe = CapabilityProbe { CapabilityReport(mapOf("note_save" to OperationState(true, settings.workerEnabled.value)), RootState(), DeviceState(80, true, "none", 1L shl 33)) }
        return FleetWorkerEngine(
            settings = settings,
            store = store,
            identity = SoftwareWorkerIdentity(),
            probe = probe,
            transportFor = { _, _ -> transport },
            appPrompt = object : ApprovalPrompt {
                override fun requestApproval(reason: String): Boolean = ask()
                override fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean = ask()
                private fun ask(): Boolean {
                    taps.incrementAndGet()
                    return appPromptAnswer
                }
            },
            policy = SecurityPolicy(autoApprove = emptySet()),
            auditLog = audit,
            activityLog = activity,
            offers = BatchOfferHandler {
                offers.incrementAndGet()
                offerAnswer
            },
            workRoot = File(tmp, "work"),
            deviceName = "Test phone",
            appVersion = "test-1",
            operations = listOf(NoteOp(batchable)),
        )
    }

    private fun args(title: String) = buildJsonObject { put("title", title) }

    private fun manifest(vararg jobs: Pair<String, String>) = buildJsonObject {
        put("plan_id", "plan-1")
        put(
            "jobs",
            buildJsonArray {
                jobs.forEach { (k, t) ->
                    add(
                        buildJsonObject {
                            put("job_id", "plan-1.$k")
                            put("op", "note_save")
                            put("args", args(t))
                            put("requires_root", false)
                        },
                    )
                }
            },
        )
    }

    private fun assignment(key: String, title: String, plan: JsonObject?) = FleetProtocol.envelope(
        "assignment", "coordinator", dev,
        buildJsonObject {
            put("job_id", "plan-1.$key")
            put("attempt", 1)
            put("fence", 1)
            put("op", "note_save")
            put("args", args(title))
            put("inputs", JsonArray(emptyList()))
            put("lease_expires_ms", System.currentTimeMillis() + 90_000)
            put("requires_root", false)
            put("ui_exclusive", false)
            put("idempotent", true)
            if (plan != null) put("plan", plan)
        },
    )

    private fun runUntilDone(e: FleetWorkerEngine, jobs: Int) {
        val deadline = System.currentTimeMillis() + 15_000
        val t = Thread { e.runLoop({ transport.count("complete") + transport.count("fail") >= jobs || System.currentTimeMillis() > deadline }) }
        t.start()
        t.join(20_000)
        assertFalse(t.isAlive, "the loop returned")
    }

    @Test fun `nothing runs while the worker switch is off, even when paired`() {
        settings.setWorkerEnabled(false)
        val e = engine()
        assertFalse(e.shouldRun())
        e.runLoop({ false })
        assertEquals(0, transport.sent.size, "it never talked to the coordinator")
        assertFalse(e.status.value.connected)
    }

    @Test fun `nothing runs when unpaired, even with the switch on`() {
        store.save(WorkerPersisted())
        val e = engine()
        assertFalse(e.shouldRun())
        e.runLoop({ false })
        assertEquals(0, transport.sent.size)
        assertFalse(e.status.value.paired)
    }

    @Test fun `with batch approval on, one owner approval covers the plan and nobody is asked per job`() {
        transport.inbound += listOf(assignment("a", "alpha", manifest("a" to "alpha", "b" to "beta")), assignment("b", "beta", manifest("a" to "alpha", "b" to "beta")))
        val e = engine()
        runUntilDone(e, 2)
        assertEquals(setOf("alpha", "beta"), ran.toSet())
        assertEquals(0, taps.get(), "no per-job tap")
        assertEquals(1, offers.get(), "one question for the plan")
        assertEquals(2, transport.count("complete"))
        assertTrue(audit.all().any { it.type == AuditEventType.GRANT_ISSUED })
        // The Activity feed shows both jobs as fleet work and records that a batch covered them.
        val events = activity.snapshot().filter { it.actor.id == "fleet:worker" }
        assertEquals(2, events.count { it.kind == ActivityKind.RUN_STARTED })
        assertEquals(2, events.count { it.kind == ActivityKind.RUN_FINISHED })
        assertTrue(events.any { it.kind == ActivityKind.NOTE && "batch approval" in it.summary })
        assertTrue(events.none { "alpha" in it.summary || "beta" in (it.detail ?: "") }, "no job arguments in the feed")
    }

    @Test fun `with batch approval off every job asks, and no offer is ever made`() {
        settings.setBatchApprovalEnabled(false)
        transport.inbound += listOf(assignment("a", "alpha", manifest("a" to "alpha", "b" to "beta")), assignment("b", "beta", manifest("a" to "alpha", "b" to "beta")))
        val e = engine()
        runUntilDone(e, 2)
        assertEquals(2, taps.get())
        assertEquals(0, offers.get())
        assertEquals(2, transport.count("approval_needed"))
        assertTrue(activity.snapshot().any { it.kind == ActivityKind.NEEDS_APPROVAL })
    }

    @Test fun `an operation that did not opt in is never covered`() {
        transport.inbound += listOf(assignment("a", "alpha", manifest("a" to "alpha", "b" to "beta")))
        val e = engine(batchable = false)
        runUntilDone(e, 1)
        assertEquals(1, taps.get())
        assertEquals(0, offers.get())
    }

    @Test fun `switching batch approval off ends the live batch at once and says so in the audit log`() {
        transport.inbound += listOf(assignment("a", "alpha", manifest("a" to "alpha", "b" to "beta", "c" to "gamma")))
        val e = engine()
        runUntilDone(e, 1)
        assertEquals(1, e.approvals.active().size, "a batch with two jobs left is live")
        settings.setBatchApprovalEnabled(false)
        e.applySettings()
        assertEquals(0, e.approvals.active().size)
        assertTrue(audit.all().any { it.type == AuditEventType.GRANT_REVOKED })
    }

    @Test fun `unpairing forgets the coordinator, switches the worker off and ends batches`() {
        transport.inbound += listOf(assignment("a", "alpha", manifest("a" to "alpha", "b" to "beta")))
        val e = engine()
        runUntilDone(e, 1)
        assertEquals(1, e.approvals.active().size)
        e.unpair()
        assertEquals(0, e.approvals.active().size)
        assertFalse(settings.workerEnabled.value)
        assertEquals(null, store.load().credential)
        assertFalse(e.status.value.paired)
    }

    @Test fun `pairing succeeds against the coordinator the owner confirmed`() {
        store.save(WorkerPersisted())
        val e = engine()
        val fpr = "c".repeat(64)
        transport.enrollResponseFingerprint = fpr
        val r = e.pair(PairingPayload("https://10.0.0.2:8443", fpr, "d".repeat(64), "secret"))
        assertEquals("dev-new00001", assertIs<PairOutcome.Paired>(r).deviceId)
        assertTrue(e.status.value.paired)
        assertEquals("https://10.0.0.2:8443", store.load().coordinatorUrl)
    }

    @Test fun `pairing is refused when the coordinator's identity differs from what the owner confirmed`() {
        store.save(WorkerPersisted())
        val e = engine()
        transport.enrollResponseFingerprint = "9".repeat(64)
        val r = e.pair(PairingPayload("https://10.0.0.2:8443", "c".repeat(64), "d".repeat(64), "secret"))
        assertIs<PairOutcome.Failed>(r)
        assertFalse(e.status.value.paired)
    }

    @Test fun `a rejected pairing secret says what to do, and an already paired phone must unpair first`() {
        store.save(WorkerPersisted())
        val e = engine()
        transport.failEnroll = FleetTransportException(401, "unauthorized", "no")
        val r = assertIs<PairOutcome.Failed>(e.pair(PairingPayload("https://10.0.0.2:8443", "c".repeat(64), "d".repeat(64), "bad")))
        assertTrue("make a new one" in r.why, r.why)
        store.save(paired)
        val again = assertIs<PairOutcome.Failed>(engine().pair(PairingPayload("https://10.0.0.2:8443", "c".repeat(64), "d".repeat(64), "x")))
        assertTrue("already paired" in again.why)
    }

    @Test fun `a revoked phone stops, says so, and does not run again`() {
        val e = engine()
        store.save(store.load().copy(revoked = true))
        assertFalse(e.shouldRun())
        e.runLoop({ false })
        assertEquals(0, transport.sent.size)
    }
}
