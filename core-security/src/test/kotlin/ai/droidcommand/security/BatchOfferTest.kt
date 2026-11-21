package ai.droidcommand.security

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The "offer the batch once per plan, at the first job that would ask anyway" step of [BatchApprovalPrompt]. */
class BatchOfferTest {
    private val now = Instant.parse("2026-11-21T12:00:00Z")
    private var switchOn = true
    private val audit = InMemoryAuditLog()
    private var ids = 0
    private val digest = ArgsDigest.of(Json.parseToJsonElement("""{"pkg":"com.example"}"""))

    private fun tool(name: String, level: SecurityLevel = SecurityLevel.SENSITIVE) = object : Tool {
        override val spec = ToolSpec(
            name = name,
            description = "d",
            securityLevel = level,
            requiresConfirmation = true,
            requiredInitiator = setOf(Initiator.REMOTE),
        )

        override fun execute(input: Map<String, String>): ToolResult = ToolResult.Success("ran")
    }

    private val registry = ToolRegistry().apply {
        register(tool("fleet.apk.diagnostics"))
        register(tool("fleet.shell.run"))
    }

    private val approvals = BatchApprovals(
        ToolBatchEligibility(registry) { setOf("fleet.apk.diagnostics", "fleet.shell.run") },
        enabled = { switchOn },
        auditLog = audit,
        clock = { now },
        newId = { "b${++ids}" },
    )

    private class Owner(var answer: Boolean = false) : ApprovalPrompt {
        val asked = AtomicInteger()
        override fun requestApproval(reason: String): Boolean {
            asked.incrementAndGet()
            return answer
        }

        override fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean = requestApproval(reason)
    }

    private fun bj(id: String, tool: String = "fleet.apk.diagnostics") = BatchJob(id, tool, digest, "summary of $id")

    private fun plan(planId: String, vararg jobs: BatchJob) = BatchOffer(planId, "coordinator-x", jobs.toList())

    private fun ask(prompt: BatchApprovalPrompt, jobId: String, offer: BatchOffer?, tool: String = "fleet.apk.diagnostics"): Boolean =
        BatchJobScope.within(BatchJobContext(jobId, tool, digest, offer)) { prompt.requestApproval(tool, emptyMap(), "r") }

    @Test
    fun `the first job that would ask puts the batch to the owner once and the rest of the plan is covered`() {
        val offer = plan("p1", bj("p1.a"), bj("p1.b"), bj("p1.c"))
        val owner = Owner()
        val seen = mutableListOf<BatchReview.Reviewable>()
        val prompt = BatchApprovalPrompt(owner, approvals, offers = {
            seen += it
            Duration.ofMinutes(10)
        })
        assertTrue(ask(prompt, "p1.a", offer))
        assertTrue(ask(prompt, "p1.b", offer))
        assertTrue(ask(prompt, "p1.c", offer))
        assertEquals(0, owner.asked.get(), "nobody was asked for a per-job tap")
        assertEquals(1, seen.size, "one question for the whole plan")
        assertEquals(3, seen.single().covered.size)
    }

    @Test
    fun `a no from the owner means every job asks for its own tap, and the batch is not offered again`() {
        val offer = plan("p1", bj("p1.a"), bj("p1.b"))
        val owner = Owner(answer = true)
        var offers = 0
        val prompt = BatchApprovalPrompt(owner, approvals, offers = {
            offers++
            null
        })
        assertTrue(ask(prompt, "p1.a", offer))
        assertTrue(ask(prompt, "p1.b", offer))
        assertEquals(2, owner.asked.get())
        assertEquals(1, offers)
        assertTrue(approvals.active().isEmpty())
    }

    @Test
    fun `an offer handler that throws counts as no`() {
        val offer = plan("p1", bj("p1.a"))
        val owner = Owner(answer = false)
        val prompt = BatchApprovalPrompt(owner, approvals, offers = { error("screen went away") })
        assertEquals(false, ask(prompt, "p1.a", offer))
        assertEquals(1, owner.asked.get())
        assertTrue(approvals.active().isEmpty())
    }

    @Test
    fun `what will still ask is shown to the owner with the reason, and still asks after the batch is approved`() {
        val offer = plan("p1", bj("p1.a"), bj("p1.sh", "fleet.shell.run"))
        val owner = Owner(answer = false)
        var review: BatchReview.Reviewable? = null
        val prompt = BatchApprovalPrompt(owner, approvals, offers = {
            review = it
            Duration.ofMinutes(5)
        })
        assertTrue(ask(prompt, "p1.a", offer))
        val shown = review!!
        assertEquals(listOf("p1.a"), shown.covered.map { it.jobId })
        assertEquals(listOf("p1.sh"), shown.stillAsk.map { it.job.jobId })
        // The shell job is in the same plan the owner approved and still goes to the owner, who says no.
        assertEquals(false, ask(prompt, "p1.sh", offer, tool = "fleet.shell.run"))
        assertEquals(1, owner.asked.get())
    }

    @Test
    fun `parallel jobs of one plan wait on a single question`() {
        val offer = plan("p1", *(1..8).map { bj("p1.$it") }.toTypedArray())
        val owner = Owner()
        val asked = AtomicInteger()
        val prompt = BatchApprovalPrompt(
            owner,
            approvals,
            offers = {
                asked.incrementAndGet()
                Thread.sleep(150)
                Duration.ofMinutes(10)
            },
        )
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map { n -> pool.submit<Boolean> { ask(prompt, "p1.$n", offer) } }
            assertTrue(results.all { it.get(10, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, asked.get())
        assertEquals(0, owner.asked.get())
    }

    @Test
    fun `a question about one plan does not hold up another plan`() {
        val a = plan("pa", bj("pa.a"))
        val b = plan("pb", bj("pb.a"))
        val release = CountDownLatch(1)
        val inA = CountDownLatch(1)
        val prompt = BatchApprovalPrompt(
            Owner(),
            approvals,
            offers = { review ->
                if (review.offer.planId == "pa") {
                    inA.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                Duration.ofMinutes(5)
            },
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Boolean> { ask(prompt, "pa.a", a) }
            assertTrue(inA.await(5, TimeUnit.SECONDS))
            val second = pool.submit<Boolean> { ask(prompt, "pb.a", b) }
            assertTrue(second.get(5, TimeUnit.SECONDS), "plan B was answered while plan A's question was still open")
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `with the feature switched off nothing is offered and a later switch-on still gets the question`() {
        val offer = plan("p1", bj("p1.a"), bj("p1.b"))
        val owner = Owner(answer = true)
        var offers = 0
        val prompt = BatchApprovalPrompt(owner, approvals, offers = {
            offers++
            Duration.ofMinutes(5)
        })
        switchOn = false
        assertTrue(ask(prompt, "p1.a", offer))
        assertEquals(0, offers)
        assertEquals(1, owner.asked.get())
        switchOn = true
        owner.answer = false
        assertTrue(ask(prompt, "p1.b", offer), "p1.b is covered by the batch the owner approved just now")
        assertEquals(1, offers)
        assertEquals(1, owner.asked.get())
    }

    @Test
    fun `without a handler, or without a plan on the job, nothing is offered`() {
        val offer = plan("p1", bj("p1.a"))
        val owner = Owner(answer = true)
        var offers = 0
        val noHandler = BatchApprovalPrompt(owner, approvals)
        assertTrue(ask(noHandler, "p1.a", offer))
        val noPlan = BatchApprovalPrompt(owner, approvals, offers = {
            offers++
            Duration.ofMinutes(5)
        })
        assertTrue(ask(noPlan, "p1.a", null))
        assertEquals(0, offers)
        assertEquals(2, owner.asked.get())
        // And a reason-only request is never a fleet job's: it goes straight to the owner.
        assertTrue(noPlan.requestApproval("some reason"))
        assertEquals(3, owner.asked.get())
    }

    @Test
    fun `the duration the owner picks is honoured but never past the maximum`() {
        val offer = plan("p1", bj("p1.a"))
        val prompt = BatchApprovalPrompt(Owner(), approvals, offers = { Duration.ofHours(5) })
        assertTrue(ask(prompt, "p1.a", offer))
        // The only job was used by the covering call, so the batch is spent; its audit record carries the clamped expiry.
        assertTrue(approvals.active().isEmpty())
        val issued = audit.all().single { it.type == AuditEventType.GRANT_ISSUED }
        assertTrue("valid until ${now.plus(Duration.ofMinutes(60))}" in issued.detail, issued.detail)
    }

    @Test
    fun `a plan id is only remembered for a bounded number of plans`() {
        var offers = 0
        val prompt = BatchApprovalPrompt(Owner(answer = true), approvals, offers = {
            offers++
            null
        }, maxRememberedPlans = 2)
        for (p in listOf("p1", "p2", "p3")) ask(prompt, "$p.a", plan(p, bj("$p.a")))
        assertEquals(3, offers)
        ask(prompt, "p3.a", plan("p3", bj("p3.a")))
        assertEquals(3, offers, "p3 is still remembered")
        ask(prompt, "p1.a", plan("p1", bj("p1.a")))
        assertEquals(4, offers, "p1 was forgotten, so it is asked about again")
    }

    @Test
    fun `through the real executor the offer replaces only the tap`() {
        val machine = AgentStateMachine()
        val owner = Owner(answer = false)
        val prompt = BatchApprovalPrompt(owner, approvals, offers = { Duration.ofMinutes(5) })
        val secure = SecureToolExecutor(registry, ToolExecutor(registry, machine, sleep = { }), machine, SecurityPolicyEnforcer(SecurityPolicy()), prompt, auditLog = audit)
        val offer = plan("p1", bj("p1.a"))
        val ok = BatchJobScope.within(BatchJobContext("p1.a", "fleet.apk.diagnostics", digest, offer)) {
            secure.run("fleet.apk.diagnostics", emptyMap(), initiator = Initiator.REMOTE)
        }
        assertIs<ToolResult.Success>(ok)
        assertEquals(0, owner.asked.get())
        // An initiator the tool refuses is stopped before approval is ever considered, so no offer is made for it.
        var offers = 0
        val prompt2 = BatchApprovalPrompt(owner, approvals, offers = {
            offers++
            Duration.ofMinutes(5)
        })
        val secure2 = SecureToolExecutor(registry, ToolExecutor(registry, machine, sleep = { }), machine, SecurityPolicyEnforcer(SecurityPolicy()), prompt2, auditLog = audit)
        val refused = BatchJobScope.within(BatchJobContext("p2.a", "fleet.apk.diagnostics", digest, plan("p2", bj("p2.a")))) {
            secure2.run("fleet.apk.diagnostics", emptyMap(), initiator = Initiator.AI)
        }
        assertIs<ToolResult.Failure>(refused)
        assertEquals(0, offers)
    }
}
