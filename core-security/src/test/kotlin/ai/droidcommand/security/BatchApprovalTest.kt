package ai.droidcommand.security

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

class ArgsDigestTest {
    @Test
    fun `key order and spacing never change the digest`() {
        assertEquals(
            ArgsDigest.of(json("""{"a":1,"b":{"y":2,"x":[1,2]}}""")),
            ArgsDigest.of(json("""{ "b" : { "x" : [1, 2], "y": 2 }, "a": 1 }""")),
        )
    }

    @Test
    fun `any real difference changes the digest`() {
        val base = ArgsDigest.of(json("""{"pkg":"com.a","n":1}"""))
        assertNotEquals(base, ArgsDigest.of(json("""{"pkg":"com.b","n":1}""")))
        assertNotEquals(base, ArgsDigest.of(json("""{"pkg":"com.a","n":2}""")))
        assertNotEquals(base, ArgsDigest.of(json("""{"pkg":"com.a","n":1,"extra":true}""")))
        assertNotEquals(ArgsDigest.of(json("[1,2]")), ArgsDigest.of(json("[2,1]")), "array order matters")
        assertNotEquals(ArgsDigest.of(json("""{"n":1}""")), ArgsDigest.of(json("""{"n":"1"}""")), "a number is not a string")
        assertNotEquals(ArgsDigest.of(json("""{"n":null}""")), ArgsDigest.of(json("""{"n":"null"}""")))
    }

    @Test
    fun `canonical form is sorted, compact and escapes only what JSON requires`() {
        assertEquals("""{"a":"q\"b\\c\u0001é","b":[true,null,1.50]}""", ArgsDigest.canonical(json("""{"b":[true,null,1.50],"a":"q\"b\\c\u0001é"}""")))
    }
}

class BatchApprovalTest {
    private val now = Instant.parse("2026-11-21T12:00:00Z")
    private var clock = now
    private var switchOn = true
    private val audit = InMemoryAuditLog()
    private var ids = 0

    private val args = ArgsDigest.of(json("""{"pkg":"com.example"}"""))

    private fun job(id: String, tool: String = "fleet.apk.diagnostics", digest: String = args) = BatchJob(id, tool, digest, "summary of $id")

    private fun offer(vararg jobs: BatchJob, plan: String = "plan-1") = BatchOffer(plan, "coordinator-x", jobs.toList())

    private fun engine(
        allowed: Set<String> = setOf("fleet.apk.diagnostics", "fleet.apk.install", "fleet.root.thing", "fleet.shell.run"),
        policy: BatchApprovalPolicy = BatchApprovalPolicy(),
        auditLog: AuditLog? = audit,
    ): BatchApprovals {
        val registry = ToolRegistry().apply {
            register(simpleTool("fleet.apk.diagnostics", SecurityLevel.SENSITIVE))
            register(simpleTool("fleet.apk.install", SecurityLevel.SENSITIVE))
            register(simpleTool("fleet.root.thing", SecurityLevel.ROOT, requiresRoot = true))
            register(simpleTool("fleet.shell.run", SecurityLevel.SENSITIVE))
        }
        return BatchApprovals(
            ToolBatchEligibility(registry) { allowed },
            enabled = { switchOn },
            policy = policy,
            auditLog = auditLog,
            clock = { clock },
            newId = { "b${++ids}" },
        )
    }

    private fun simpleTool(name: String, level: SecurityLevel, requiresRoot: Boolean = false, category: PermissionCategory? = null, grant: String? = null) = object : Tool {
        override val spec = ToolSpec(
            name = name,
            description = "d",
            requiresRoot = requiresRoot,
            securityLevel = level,
            requiresConfirmation = true,
            requiredInitiator = setOf(Initiator.REMOTE),
            permissionCategory = category,
            grantCapability = grant,
        )

        override fun execute(input: Map<String, String>): ToolResult = ToolResult.Success("ran")
    }

    private fun approved(e: BatchApprovals, vararg jobs: BatchJob): BatchGrant {
        val review = assertIs<BatchReview.Reviewable>(e.review(offer(*jobs)))
        return assertNotNull(e.approve(review))
    }

    // ------------------------------------------------------------------ review

    @Test
    fun `the feature is off until the owner turns it on`() {
        switchOn = false
        assertIs<BatchReview.Refused>(engine().review(offer(job("j1"))))
    }

    @Test
    fun `a reviewable plan separates what one approval covers from what will still ask, with the reason`() {
        val review = assertIs<BatchReview.Reviewable>(
            engine().review(offer(job("j1"), job("j2", "fleet.root.thing"), job("j3", "fleet.shell.run"), job("j4", "fleet.unknown.tool"))),
        )
        assertEquals(listOf("j1"), review.covered.map { it.jobId })
        assertEquals(listOf("j2", "j3", "j4"), review.stillAsk.map { it.job.jobId })
        assertTrue("root" in review.stillAsk[0].reason)
        assertTrue("shell" in review.stillAsk[1].reason)
        assertTrue("not allowed" in review.stillAsk[2].reason, "a tool the app never listed is not coverable")
    }

    @Test
    fun `a tool behind its own grant or a root-level category is never coverable, even if listed`() {
        val registry = ToolRegistry().apply {
            register(simpleTool("fleet.granted", SecurityLevel.SENSITIVE, grant = "root"))
            register(simpleTool("fleet.container", SecurityLevel.SENSITIVE, category = PermissionCategory.CONTAINER))
            register(simpleTool("fleet.fine", SecurityLevel.SENSITIVE))
            register(simpleTool("fleet.wipe_data", SecurityLevel.SENSITIVE))
        }
        val e = ToolBatchEligibility(registry) { setOf("fleet.granted", "fleet.container", "fleet.fine", "fleet.wipe_data") }
        assertNotNull(e.refusal("fleet.granted"))
        assertNotNull(e.refusal("fleet.container"))
        assertNotNull(e.refusal("fleet.wipe_data"), "destructive by name")
        assertNull(e.refusal("fleet.fine"))
    }

    @Test
    fun `a plan with nothing coverable has nothing to approve`() {
        assertIs<BatchReview.NothingToCover>(engine().review(offer(job("j1", "fleet.root.thing"))))
    }

    @Test
    fun `malformed or oversized offers are refused`() {
        val e = engine(policy = BatchApprovalPolicy(maxJobs = 2))
        assertIs<BatchReview.Refused>(e.review(offer()))
        assertIs<BatchReview.Refused>(e.review(offer(job("a"), job("b"), job("c"))), "more than maxJobs")
        assertIs<BatchReview.Refused>(e.review(offer(job("a"), job("a"))), "duplicate job id")
        assertIs<BatchReview.Refused>(e.review(offer(job("bad id!"))), "invalid id")
        assertIs<BatchReview.Refused>(e.review(offer(job("a", digest = "nothex"))), "invalid digest")
        assertIs<BatchReview.Refused>(e.review(offer(job("a"), plan = "bad plan!")))
    }

    @Test
    fun `summaries are cut to the policy length`() {
        val e = engine(policy = BatchApprovalPolicy(summaryChars = 10))
        val review = assertIs<BatchReview.Reviewable>(e.review(offer(BatchJob("j1", "fleet.apk.diagnostics", args, "x".repeat(500)))))
        assertEquals(10, review.covered.single().summary.length)
    }

    // ------------------------------------------------------------------ approve

    @Test
    fun `approving issues an audited batch that only lists the coverable jobs`() {
        val e = engine()
        val grant = approved(e, job("j1"), job("j2", "fleet.root.thing"))
        assertEquals(listOf("j1"), grant.jobs.map { it.jobId })
        assertEquals(now.plus(Duration.ofMinutes(15)), grant.expiresAt)
        val issued = audit.all().single()
        assertEquals(AuditEventType.GRANT_ISSUED, issued.type)
        assertTrue("plan-1" in issued.detail && "coordinator-x" in issued.detail)
        assertEquals(listOf(grant), e.active())
    }

    @Test
    fun `the owner can shorten the time but never go past the maximum`() {
        val e = engine()
        val review = assertIs<BatchReview.Reviewable>(e.review(offer(job("j1"))))
        assertEquals(now.plus(Duration.ofMinutes(5)), assertNotNull(e.approve(review, Duration.ofMinutes(5))).expiresAt)
        assertEquals(now.plus(Duration.ofMinutes(60)), assertNotNull(e.approve(review, Duration.ofDays(3))).expiresAt)
        assertNull(e.approve(review, Duration.ZERO))
        assertNull(e.approve(review, Duration.ofMinutes(-1)))
    }

    @Test
    fun `approval is refused when the switch went off after the review`() {
        val e = engine()
        val review = assertIs<BatchReview.Reviewable>(e.review(offer(job("j1"))))
        switchOn = false
        assertNull(e.approve(review))
    }

    @Test
    fun `approval fails closed when the audit log cannot record it`() {
        val full = InMemoryAuditLog(capacity = 0)
        val e = engine(auditLog = full)
        val review = assertIs<BatchReview.Reviewable>(e.review(offer(job("j1"))))
        assertNull(e.approve(review))
        assertTrue(e.active().isEmpty())
    }

    @Test
    fun `too many live batches are refused instead of evicting an older one`() {
        val e = engine(policy = BatchApprovalPolicy(maxActive = 2))
        approved(e, job("a"), plan = "p1")
        approved(e, job("b"), plan = "p2")
        val review = assertIs<BatchReview.Reviewable>(e.review(offer(job("c"), plan = "p3")))
        assertNull(e.approve(review))
        assertEquals(2, e.active().size)
    }

    private fun approved(e: BatchApprovals, vararg jobs: BatchJob, plan: String): BatchGrant {
        val review = assertIs<BatchReview.Reviewable>(e.review(offer(*jobs, plan = plan)))
        return assertNotNull(e.approve(review))
    }

    // ------------------------------------------------------------------ use

    @Test
    fun `an approved job is covered once, and a retry asks again`() {
        val e = engine()
        val grant = approved(e, job("j1"), job("j2"))
        assertTrue(e.tryCover("j1", "fleet.apk.diagnostics", args))
        assertFalse(e.tryCover("j1", "fleet.apk.diagnostics", args), "single use")
        assertEquals(1, e.remaining(grant.id))
        assertEquals(
            listOf(AuditEventType.GRANT_ISSUED, AuditEventType.GRANT_CONSUMED, AuditEventType.GRANT_DENIED),
            audit.all().map { it.type },
            "the replay is audited as a denial",
        )
    }

    @Test
    fun `a job with a different tool or different arguments from what was approved is not covered`() {
        val e = engine()
        approved(e, job("j1"), job("j2"))
        assertFalse(e.tryCover("j1", "fleet.apk.install", args), "approved as diagnostics, asked as install")
        assertFalse(e.tryCover("j2", "fleet.apk.diagnostics", ArgsDigest.of(json("""{"pkg":"com.evil"}"""))), "other arguments")
        assertTrue(audit.all().count { it.type == AuditEventType.GRANT_DENIED } == 2)
        // The approved ones still work afterwards.
        assertTrue(e.tryCover("j1", "fleet.apk.diagnostics", args))
        assertTrue(e.tryCover("j2", "fleet.apk.diagnostics", args))
    }

    @Test
    fun `a job that was never in the batch is not covered, and says nothing`() {
        val e = engine()
        approved(e, job("j1"))
        val before = audit.all().size
        assertFalse(e.tryCover("other-job", "fleet.apk.diagnostics", args))
        assertEquals(before, audit.all().size)
    }

    @Test
    fun `a job from another plan's batch is not covered by this plan's approval id`() {
        val e = engine()
        approved(e, job("j1"), plan = "p1")
        assertFalse(e.tryCover("j9", "fleet.apk.diagnostics", args))
    }

    @Test
    fun `coverage ends at expiry`() {
        val e = engine()
        approved(e, job("j1"))
        clock = now.plus(Duration.ofMinutes(15)).plusSeconds(1)
        assertFalse(e.tryCover("j1", "fleet.apk.diagnostics", args))
        assertTrue(e.active().isEmpty())
    }

    @Test
    fun `revoking a batch ends it at once, and is audited`() {
        val e = engine()
        val grant = approved(e, job("j1"), job("j2"))
        assertTrue(e.revoke(grant.id, "owner revoked"))
        assertFalse(e.revoke(grant.id, "again"), "already revoked")
        assertFalse(e.tryCover("j1", "fleet.apk.diagnostics", args))
        assertEquals(AuditEventType.GRANT_REVOKED, audit.all().last().type)
        assertFalse(e.revoke("nope", "x"))
    }

    @Test
    fun `turning the switch off stops coverage immediately, even without a revoke`() {
        val e = engine()
        approved(e, job("j1"))
        switchOn = false
        assertFalse(e.tryCover("j1", "fleet.apk.diagnostics", args))
        switchOn = true
        assertTrue(e.tryCover("j1", "fleet.apk.diagnostics", args), "still the same live batch if it is turned back on")
    }

    @Test
    fun `revokeAll ends every live batch and reports how many`() {
        val e = engine()
        approved(e, job("a"), plan = "p1")
        approved(e, job("b"), plan = "p2")
        assertEquals(2, e.revokeAll("switch turned off"))
        assertTrue(e.active().isEmpty())
        assertEquals(0, e.revokeAll("again"))
    }

    @Test
    fun `a tool that stopped being coverable after approval is not covered`() {
        var allowed = setOf("fleet.apk.diagnostics")
        val registry = ToolRegistry().apply { register(simpleTool("fleet.apk.diagnostics", SecurityLevel.SENSITIVE)) }
        val e = BatchApprovals(ToolBatchEligibility(registry) { allowed }, { true }, auditLog = audit, clock = { clock })
        val review = assertIs<BatchReview.Reviewable>(e.review(offer(job("j1"))))
        assertNotNull(e.approve(review))
        allowed = emptySet()
        assertFalse(e.tryCover("j1", "fleet.apk.diagnostics", args))
    }

    @Test
    fun `nothing is covered when the audit log cannot record the use`() {
        val log = object : AuditLog {
            var full = false
            val inner = InMemoryAuditLog()
            override fun record(event: AuditEvent): Boolean = !full && inner.record(event)
        }
        val e = engine(auditLog = log)
        approved(e, job("j1"))
        log.full = true
        assertFalse(e.tryCover("j1", "fleet.apk.diagnostics", args))
    }

    @Test
    fun `concurrent attempts to use one job cover it exactly once`() {
        val e = engine()
        approved(e, job("j1"))
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        try {
            val results = (1..8).map {
                pool.submit<Boolean> {
                    go.await()
                    e.tryCover("j1", "fleet.apk.diagnostics", args)
                }
            }
            go.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
    }

    // ------------------------------------------------------------------ the prompt in front of the owner

    private class CountingPrompt(var answer: Boolean = true) : ApprovalPrompt {
        var asked = 0
        override fun requestApproval(reason: String): Boolean {
            asked++
            return answer
        }

        override fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean {
            asked++
            return answer
        }
    }

    @Test
    fun `a covered job is not asked about again, and the coordinator is told`() {
        val e = engine()
        approved(e, job("j1"))
        val owner = CountingPrompt(answer = false)
        val covered = mutableListOf<String>()
        val prompt = BatchApprovalPrompt(owner, e) { covered += it }
        val answer = BatchJobScope.within(BatchJobContext("j1", "fleet.apk.diagnostics", args)) {
            prompt.requestApproval("fleet.apk.diagnostics", emptyMap(), "reason")
        }
        assertTrue(answer)
        assertEquals(0, owner.asked)
        assertEquals(listOf("j1"), covered)
    }

    @Test
    fun `anything not covered goes to the owner and the owner's own answer is returned`() {
        val e = engine()
        approved(e, job("j1"))
        val owner = CountingPrompt(answer = false)
        val prompt = BatchApprovalPrompt(owner, e)
        // No job context.
        assertFalse(prompt.requestApproval("fleet.apk.diagnostics", emptyMap(), "r"))
        // A reason-only request carries no tool, so it can never be covered.
        assertFalse(BatchJobScope.within(BatchJobContext("j1", "fleet.apk.diagnostics", args)) { prompt.requestApproval("r") })
        // Different arguments.
        assertFalse(
            BatchJobScope.within(BatchJobContext("j1", "fleet.apk.diagnostics", ArgsDigest.of(json("""{"pkg":"x"}""")))) {
                prompt.requestApproval("fleet.apk.diagnostics", emptyMap(), "r")
            },
        )
        // The context says one tool, the request is for another.
        assertFalse(
            BatchJobScope.within(BatchJobContext("j1", "fleet.apk.diagnostics", args)) {
                prompt.requestApproval("fleet.apk.install", emptyMap(), "r")
            },
        )
        assertEquals(4, owner.asked)
        owner.answer = true
        assertTrue(prompt.requestApproval("fleet.apk.diagnostics", emptyMap(), "r"))
    }

    @Test
    fun `parallel jobs never see each other's context`() {
        val e = engine()
        approved(e, job("j1"), job("j2"))
        val prompt = BatchApprovalPrompt(CountingPrompt(answer = false), e)
        val pool = Executors.newFixedThreadPool(2)
        val inside = CountDownLatch(2)
        try {
            fun run(id: String) = pool.submit<Boolean> {
                BatchJobScope.within(BatchJobContext(id, "fleet.apk.diagnostics", args)) {
                    inside.countDown()
                    inside.await(5, TimeUnit.SECONDS)
                    BatchJobScope.current()?.jobId == id && prompt.requestApproval("fleet.apk.diagnostics", emptyMap(), "r")
                }
            }
            val a = run("j1")
            val b = run("j2")
            assertTrue(a.get(5, TimeUnit.SECONDS) && b.get(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertNull(BatchJobScope.current())
    }

    // ------------------------------------------------------------------ with the real executor

    private fun executor(prompt: ApprovalPrompt, policy: SecurityPolicy = SecurityPolicy(), allowedModes: Boolean = true): Pair<SecureToolExecutor, List<Tool>> {
        val tools = listOf(simpleTool("fleet.apk.diagnostics", SecurityLevel.SENSITIVE))
        val registry = ToolRegistry().apply { tools.forEach { register(it) } }
        val machine = AgentStateMachine()
        return SecureToolExecutor(registry, ToolExecutor(registry, machine, sleep = { }), machine, SecurityPolicyEnforcer(policy), prompt, auditLog = audit) to tools
    }

    @Test
    fun `through the real executor a covered job runs without a tap, still gated by every other check`() {
        val e = engine()
        approved(e, job("j1"))
        val owner = CountingPrompt(answer = false)
        val (secure, _) = executor(BatchApprovalPrompt(owner, e))
        val result = BatchJobScope.within(BatchJobContext("j1", "fleet.apk.diagnostics", args)) {
            secure.run("fleet.apk.diagnostics", emptyMap(), initiator = Initiator.REMOTE)
        }
        assertIs<ToolResult.Success>(result)
        assertEquals(0, owner.asked)
        // The same job a second time asks the owner, who says no, and it does not run.
        val second = BatchJobScope.within(BatchJobContext("j1", "fleet.apk.diagnostics", args)) {
            secure.run("fleet.apk.diagnostics", emptyMap(), initiator = Initiator.REMOTE)
        }
        assertIs<ToolResult.Failure>(second)
        assertEquals(1, owner.asked)
    }

    @Test
    fun `a batch cannot make an initiator the tool refuses acceptable`() {
        val e = engine()
        approved(e, job("j1"))
        val (secure, _) = executor(BatchApprovalPrompt(CountingPrompt(answer = false), e))
        // The tool is for REMOTE callers only; an AI-initiated call is refused before any approval is considered.
        val result = BatchJobScope.within(BatchJobContext("j1", "fleet.apk.diagnostics", args)) {
            secure.run("fleet.apk.diagnostics", emptyMap(), initiator = Initiator.AI)
        }
        assertIs<ToolResult.Failure>(result)
        assertTrue(e.active().single().jobs.size == 1 && e.remaining(e.active().single().id) == 1, "and the job was not used up")
    }
}
