package ai.droidcommand.fleet.worker

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.BatchApprovalPrompt
import ai.droidcommand.security.BatchApprovals
import ai.droidcommand.security.BatchJobContext
import ai.droidcommand.security.BatchJobScope
import ai.droidcommand.security.BatchReview
import ai.droidcommand.security.InMemoryAuditLog
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import ai.droidcommand.security.ToolBatchEligibility
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The opt-in "approve a reviewed batch once" feature as the worker drives it: the plan manifest on an assignment, the job context
 * around the tool call, the owner's two switches, and the end of every batch when the phone is paused, disabled, unpaired or revoked.
 */
class FleetWorkerBatchTest {
    private val dev = "dev-test0001"
    private val tmp = createTempDirectory("fwb").toFile()
    private val t = ScriptedTransport()
    private var store = InMemoryWorkerStateStore(WorkerPersisted(deviceId = dev, credential = "CRED"))
    private val noRoot = CapabilityReport(mapOf("note_save" to OperationState(true, true)), RootState(), DeviceState(80, true, "none", 1L shl 33))
    private val probe = MutableProbe(noRoot)
    private val now = longArrayOf(1_000_000L)
    private val audit = InMemoryAuditLog()
    private var switchOn = true
    private var ids = 0

    /** A small confirmation-asking operation that opts in to batches (or not). It needs no input artifacts. */
    private class NoteOp(override val batchable: Boolean = true, private val ran: MutableList<String>) : FleetOperation {
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
                ExecutionMarker.mark()
                ran += input["title"].orEmpty()
                return ToolResult.Success("1")
            }
        }
    }

    /** The owner's own tap. Records what the batch layer had told it about the running job at the moment it was asked. */
    private class Owner(var answer: Boolean = true) : ApprovalPrompt {
        var taps = 0
        val contexts = mutableListOf<BatchJobContext?>()
        override fun requestApproval(reason: String): Boolean = ask()
        override fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean = ask()
        private fun ask(): Boolean {
            taps++
            contexts += BatchJobScope.current()
            return answer
        }
    }

    private val ran = mutableListOf<String>()
    private val owner = Owner()
    private var offersMade = mutableListOf<BatchReview.Reviewable>()
    private var offerAnswer: Duration? = Duration.ofMinutes(10)
    private val events = mutableListOf<FleetJobEvent>()

    private fun args(title: String) = buildJsonObject { put("title", title) }

    private fun jobOf(plan: String, key: String, title: String) = buildJsonObject {
        put("job_id", "$plan.$key")
        put("op", "note_save")
        put("args", args(title))
        put("requires_root", false)
    }

    private fun manifest(plan: String, vararg jobs: Pair<String, String>) = buildJsonObject {
        put("plan_id", plan)
        put("jobs", buildJsonArray { jobs.forEach { (k, title) -> add(jobOf(plan, k, title)) } })
    }

    private fun assignment(plan: String, key: String, title: String, manifest: JsonObject?, root: Boolean = false, argsOverride: JsonObject? = null, attempt: Int = 1) =
        FleetProtocol.envelope(
            "assignment", "coordinator", dev,
            buildJsonObject {
                put("job_id", "$plan.$key")
                put("attempt", attempt)
                put("fence", attempt)
                put("op", "note_save")
                put("args", argsOverride ?: args(title))
                put("inputs", JsonArray(emptyList()))
                put("lease_expires_ms", now[0] + 90_000)
                put("requires_root", root)
                put("ui_exclusive", false)
                put("idempotent", true)
                if (manifest != null) put("plan", manifest)
            },
        )

    private class Rig(val worker: FleetWorker, val approvals: BatchApprovals)

    private fun rig(batchable: Boolean = true, withBatchFeature: Boolean = true): Rig {
        lateinit var w: FleetWorker
        val op = NoteOp(batchable, ran)
        val reg = ToolRegistry().also { it.register(op.tool()) }
        val approvals = BatchApprovals(
            ToolBatchEligibility(reg) { setOf(op.toolName) },
            enabled = { switchOn },
            auditLog = audit,
            clock = { java.time.Instant.parse("2026-11-21T12:00:00Z") },
            newId = { "b${++ids}" },
        )
        val fleetPrompt = FleetApprovalPrompt(owner, { w.approvalPending(it) }, { w.approvalResolved(it) })
        val prompt = BatchApprovalPrompt(fleetPrompt, approvals, offers = {
            offersMade += it
            offerAnswer
        }, onCovered = { w.approvalCoveredByBatch() })
        val sm = AgentStateMachine()
        val runner = SecureToolExecutor(reg, ToolExecutor(reg, sm, sleep = { }), sm, SecurityPolicyEnforcer(SecurityPolicy(autoApprove = emptySet())), prompt, auditLog = audit)
        w = FleetWorker(
            SoftwareWorkerIdentity(), t, probe, runner, store, listOf(op), tmp, WorkerConfig("Test phone", "test-1"),
            clock = { now[0] }, executor = { it.run() }, batch = if (withBatchFeature) approvals else null,
            onJobEvent = { events += it },
        )
        return Rig(w, approvals)
    }

    private fun run(r: Rig, vararg msgs: JsonObject) {
        t.inbound += msgs
        r.worker.step()
        r.worker.step()
    }

    @Test fun `the owner approves a reviewed plan once and its jobs run with no per-job tap`() {
        val m = manifest("plan-1", "a" to "alpha", "b" to "beta", "c" to "gamma")
        val r = rig()
        run(r, assignment("plan-1", "a", "alpha", m), assignment("plan-1", "b", "beta", m), assignment("plan-1", "c", "gamma", m))
        assertEquals(listOf("alpha", "beta", "gamma"), ran)
        assertEquals(0, owner.taps, "no per-job tap")
        assertEquals(1, offersMade.size, "one question for the whole plan")
        assertEquals(listOf("plan-1.a", "plan-1.b", "plan-1.c"), offersMade.single().covered.map { it.jobId })
        assertEquals(3, t.count("complete"))
        assertEquals(0, t.count("approval_needed"), "a covered job never reports itself as waiting on the owner")
        val notes = t.sent.filter { (it["type"] as JsonPrimitive).content == "progress" }.map { ((it["payload"] as JsonObject)["note"] as JsonPrimitive).content }
        assertEquals(3, notes.count { "batch approval" in it })
    }

    @Test fun `with the batch switch off a manifest changes nothing and every job asks`() {
        switchOn = false
        val m = manifest("plan-1", "a" to "alpha", "b" to "beta")
        val r = rig()
        run(r, assignment("plan-1", "a", "alpha", m), assignment("plan-1", "b", "beta", m))
        assertEquals(2, owner.taps)
        assertEquals(0, offersMade.size)
        assertEquals(2, t.count("approval_needed"))
        assertEquals(2, t.count("complete"))
    }

    @Test fun `without the feature wired in at all a manifest is ignored and every job asks`() {
        val m = manifest("plan-1", "a" to "alpha", "b" to "beta")
        val r = rig(withBatchFeature = false)
        run(r, assignment("plan-1", "a", "alpha", m), assignment("plan-1", "b", "beta", m))
        assertEquals(2, owner.taps)
        assertEquals(0, offersMade.size)
        assertTrue(owner.contexts.all { it == null }, "no batch context was ever set")
    }

    @Test fun `a no from the owner leaves each job asking for its own tap`() {
        offerAnswer = null
        val m = manifest("plan-1", "a" to "alpha", "b" to "beta")
        val r = rig()
        run(r, assignment("plan-1", "a", "alpha", m), assignment("plan-1", "b", "beta", m))
        assertEquals(1, offersMade.size)
        assertEquals(2, owner.taps)
        assertEquals(2, t.count("complete"))
    }

    @Test fun `a job whose arguments differ from what the manifest listed is not covered`() {
        val m = manifest("plan-1", "a" to "alpha", "b" to "beta")
        val r = rig()
        // The coordinator listed title=beta for job b, then assigned title=BETA-DIFFERENT.
        run(r, assignment("plan-1", "a", "alpha", m), assignment("plan-1", "b", "x", m, argsOverride = args("BETA-DIFFERENT")))
        assertEquals(listOf("alpha", "BETA-DIFFERENT"), ran)
        assertEquals(1, owner.taps, "only the mismatching job asked")
        assertNull(owner.contexts.single()?.offer, "and it was given no plan to offer")
    }

    @Test fun `an operation that did not opt in is never covered and never offered`() {
        val m = manifest("plan-1", "a" to "alpha", "b" to "beta")
        val r = rig(batchable = false)
        run(r, assignment("plan-1", "a", "alpha", m), assignment("plan-1", "b", "beta", m))
        assertEquals(2, owner.taps)
        assertEquals(0, offersMade.size)
        assertTrue(owner.contexts.all { it == null })
    }

    @Test fun `a job the coordinator says needs root never runs inside a batch`() {
        probe.report = noRoot.copy(root = RootState(available = true, granted = true))
        val m = manifest("plan-1", "a" to "alpha", "b" to "beta")
        val r = rig()
        run(r, assignment("plan-1", "a", "alpha", m, root = true))
        assertEquals(1, owner.taps)
        assertEquals(0, offersMade.size)
        assertNull(owner.contexts.single(), "no batch context at all for a root job")
    }

    @Test fun `an unusable manifest is ignored and the job still runs and asks as before`() {
        val broken = buildJsonObject {
            put("plan_id", "plan-1")
            put(
                "jobs",
                buildJsonArray {
                    add(jobOf("plan-1", "a", "alpha"))
                    add(jobOf("plan-1", "a", "alpha"))
                },
            ) // duplicate job id
        }
        val r = rig()
        run(r, assignment("plan-1", "a", "alpha", broken))
        assertEquals(listOf("alpha"), ran)
        assertEquals(1, owner.taps)
        assertEquals(0, offersMade.size)
        assertEquals(1, t.count("complete"))
    }

    @Test fun `the owner switching batch approval off mid-plan stops coverage at once`() {
        val m = manifest("plan-1", "a" to "alpha", "b" to "beta", "c" to "gamma")
        val r = rig()
        run(r, assignment("plan-1", "a", "alpha", m))
        assertEquals(0, owner.taps)
        switchOn = false
        run(r, assignment("plan-1", "b", "beta", m))
        assertEquals(1, owner.taps, "the second job asked, because the switch is off")
        assertEquals(listOf("alpha", "beta"), ran)
    }

    @Test fun `pausing, disabling, unpairing or being revoked ends every live batch`() {
        fun live(action: (Rig) -> Unit) {
            store = InMemoryWorkerStateStore(WorkerPersisted(deviceId = dev, credential = "CRED"))
            val m = manifest("plan-z", "a" to "alpha", "b" to "beta")
            val r = rig()
            run(r, assignment("plan-z", "a", "alpha", m))
            assertEquals(1, r.approvals.active().size, "a batch is live after the first job")
            action(r)
            assertTrue(r.approvals.active().isEmpty(), "the batch ended")
            ids = 0
            offersMade.clear()
        }
        live { it.worker.setPaused(true) }
        live { it.worker.setEnabled(false) }
        live { it.worker.unpair() }
        live { r ->
            t.failNext = FleetTransportException(401, "revoked", "revoked")
            assertEquals(StepResult.Revoked, r.worker.step())
        }
        assertTrue(audit.all().count { it.type == AuditEventType.GRANT_REVOKED } >= 4)
    }

    @Test fun `a retry of a covered job is not covered a second time and asks the owner`() {
        val m = manifest("plan-1", "a" to "alpha")
        val r = rig()
        run(r, assignment("plan-1", "a", "alpha", m))
        assertEquals(0, owner.taps)
        owner.answer = false
        run(r, assignment("plan-1", "a", "alpha", m, attempt = 2))
        assertEquals(1, owner.taps)
        assertEquals(listOf("alpha"), ran, "the declined retry did not run")
        assertEquals("permission_denied", (((t.last("fail")["error"] as JsonObject)["code"]) as JsonPrimitive).content)
    }

    @Test fun `the owner's activity feed hears about a batch-covered job without any arguments`() {
        val m = manifest("plan-1", "a" to "alpha")
        val r = rig()
        run(r, assignment("plan-1", "a", "alpha", m))
        assertEquals(
            listOf(FleetJobEvent.Phase.STARTED, FleetJobEvent.Phase.COVERED_BY_BATCH, FleetJobEvent.Phase.COMPLETED),
            events.map { it.phase },
        )
        assertTrue(events.all { it.jobId == "plan-1.a" && it.op == "note_save" })
        assertTrue(events.none { "alpha" in (it.detail ?: "") })
    }

    @Test fun `a job that waits on the owner is reported as waiting, then answered, then completed`() {
        switchOn = false
        val r = rig()
        run(r, assignment("plan-1", "a", "alpha", null))
        assertEquals(
            listOf(FleetJobEvent.Phase.STARTED, FleetJobEvent.Phase.WAITING_FOR_OWNER, FleetJobEvent.Phase.OWNER_ANSWERED, FleetJobEvent.Phase.COMPLETED),
            events.map { it.phase },
        )
        assertEquals("approved", events.single { it.phase == FleetJobEvent.Phase.OWNER_ANSWERED }.detail)
    }

    @Test fun `a job the owner declines ends as failed with the typed reason`() {
        switchOn = false
        owner.answer = false
        val r = rig()
        run(r, assignment("plan-1", "a", "alpha", null))
        val last = events.last()
        assertEquals(FleetJobEvent.Phase.FAILED, last.phase)
        assertTrue(last.detail!!.startsWith("permission_denied"), last.detail)
    }

    @Test fun `a listener that throws cannot break the worker`() {
        val noteOp = NoteOp(true, ran)
        val reg = ToolRegistry().also { it.register(noteOp.tool()) }
        val sm = AgentStateMachine()
        val runner = SecureToolExecutor(reg, ToolExecutor(reg, sm, sleep = { }), sm, SecurityPolicyEnforcer(SecurityPolicy(autoApprove = emptySet())), ApprovalPrompt { true })
        val w = FleetWorker(
            SoftwareWorkerIdentity(), t, probe, runner, store, listOf(noteOp), tmp, WorkerConfig("P", "v"),
            clock = { now[0] }, executor = { it.run() }, onJobEvent = { error("listener bug") },
        )
        t.inbound += assignment("plan-1", "a", "alpha", null)
        w.step()
        w.step()
        assertEquals(listOf("alpha"), ran, "the job still ran")
        assertEquals(1, t.count("complete"), "and still reached its terminal report")
    }
}
