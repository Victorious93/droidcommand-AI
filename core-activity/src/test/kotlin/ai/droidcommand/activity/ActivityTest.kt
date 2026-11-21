package ai.droidcommand.activity

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.security.ApprovalPrompt
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val alice = ActivityActor("bot-a", "Alice")
private val bob = ActivityActor("bot-b", "Bob")

private class FakeRunner(var next: () -> ToolResult = { ToolResult.Success("ok") }) : ToolRunner {
    var lastArgs: List<Any?> = emptyList()
    override fun run(
        toolName: String,
        input: Map<String, String>,
        retryPolicy: RetryPolicy,
        isCancelled: () -> Boolean,
        mode: AgentMode?,
        initiator: Initiator?,
        grantId: String?,
    ): ToolResult {
        lastArgs = listOf(toolName, input, retryPolicy, mode, initiator, grantId)
        return next()
    }
}

class ActivityRedactorTest {
    @Test
    fun `values under secret-looking keys are masked`() {
        val s = ActivityRedactor.args(mapOf("url" to "https://x.test", "api_key" to "abc123", "Password" to "hunter2"))
        assertTrue("url=https://x.test" in s)
        assertTrue("api_key=${ActivityRedactor.MASK}" in s)
        assertTrue("Password=${ActivityRedactor.MASK}" in s)
        assertFalse("abc123" in s || "hunter2" in s)
    }

    @Test
    fun `well-known token shapes in free text are masked`() {
        val raw = "curl -H 'Authorization: Bearer abcdef123456789' https://x.test && echo sk-abcdefghijklmnopqrstuv ghp_abcdefghijklmnopqrstuvwx password=hunter2"
        val s = ActivityRedactor.text(raw, 500)
        for (secret in listOf("abcdef123456789", "sk-abcdefghijklmnopqrstuv", "ghp_abcdefghijklmnopqrstuvwx", "hunter2")) {
            assertFalse(secret in s, "leaked $secret in: $s")
        }
    }

    @Test
    fun `private key blocks are masked`() {
        val s = ActivityRedactor.text("-----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJBAKj34GkxFhD90vcNLYLInFEX6Ppy1tPf9Cnzj4p4WGeKLs1Pt8Qu\n-----END RSA PRIVATE KEY-----", 500)
        assertFalse("MIIBOg" in s)
    }

    @Test
    fun `output is bounded and whitespace is flattened`() {
        val s = ActivityRedactor.output("line1\n\n   line2\t" + "x".repeat(2_000))
        assertTrue(s.length <= 400)
        assertTrue(s.startsWith("line1 line2 "))
        assertTrue(s.endsWith("…"))
    }
}

class InMemoryActivityLogTest {
    private fun event(i: Int) = ActivityEvent(alice, "r", ActivityKind.NOTE, "e$i", null, i.toLong())

    @Test
    fun `keeps the newest events and drops the oldest, never refusing a new one`() {
        val log = InMemoryActivityLog(capacity = 3)
        (1..5).forEach { log.append(event(it)) }
        assertEquals(listOf("e3", "e4", "e5"), log.snapshot().map { it.summary })
    }

    @Test
    fun `seq is assigned by the log and strictly increasing`() {
        val log = InMemoryActivityLog()
        repeat(4) { log.append(event(it)) }
        assertEquals(listOf(1L, 2L, 3L, 4L), log.snapshot().map { it.seq })
    }

    @Test
    fun `a throwing listener does not stop the append or other listeners`() {
        val log = InMemoryActivityLog()
        val seen = mutableListOf<String>()
        log.addListener { error("boom") }
        log.addListener { seen += it.summary }
        log.append(event(1))
        assertEquals(listOf("e1"), seen)
        assertEquals(1, log.snapshot().size)
    }

    @Test
    fun `a removed listener is no longer called`() {
        val log = InMemoryActivityLog()
        val seen = mutableListOf<String>()
        val remove = log.addListener { seen += it.summary }
        log.append(event(1))
        remove()
        log.append(event(2))
        assertEquals(listOf("e1"), seen)
    }
}

class ActivityRecordingToolRunnerTest {
    private val log = InMemoryActivityLog()
    private var now = 1_000L
    private val run = ActivityRun.start(alice, "Started", log, clock = { now++ })

    private fun runner(delegate: ToolRunner) = ActivityRecordingToolRunner(delegate, run, log, clock = { now++ })
    private fun kinds() = log.snapshot().map { it.kind }

    @Test
    fun `a successful call is passed through unchanged and recorded as started then finished`() {
        val delegate = FakeRunner { ToolResult.Success("42 apps") }
        val result = runner(delegate).run("list_installed_apps", mapOf("filter" to "user"), RetryPolicy(), { false }, AgentMode.FORGE, Initiator.AI, "g1")

        assertEquals(ToolResult.Success("42 apps"), result)
        assertEquals("list_installed_apps", delegate.lastArgs[0])
        assertEquals(Initiator.AI, delegate.lastArgs[4])
        assertEquals("g1", delegate.lastArgs[5])
        assertEquals(listOf(ActivityKind.RUN_STARTED, ActivityKind.TOOL_STARTED, ActivityKind.TOOL_FINISHED), kinds())
        assertEquals("filter=user", log.snapshot()[1].detail)
        assertEquals("42 apps", log.snapshot()[2].detail)
    }

    @Test
    fun `secrets in arguments and output never reach the log`() {
        val delegate = FakeRunner { ToolResult.Success("token=supersecretvalue done") }
        runner(delegate).run("http_get", mapOf("authorization" to "Bearer abcdefghijkl"))
        val text = log.snapshot().joinToString { "${it.summary} ${it.detail}" }
        assertFalse("abcdefghijkl" in text)
        assertFalse("supersecretvalue" in text)
    }

    @Test
    fun `failure and unexpected results are recorded as failed steps and returned as is`() {
        val failure = ToolResult.Failure("denied by policy")
        val unexpected = ToolResult.Unexpected("odd state")
        assertSame(failure, runner(FakeRunner { failure }).run("shell_tool", emptyMap()))
        assertSame(unexpected, runner(FakeRunner { unexpected }).run("shell_tool", emptyMap()))
        assertEquals(2, kinds().count { it == ActivityKind.TOOL_FAILED })
    }

    @Test
    fun `partial is a finished step that says it was partial`() {
        runner(FakeRunner { ToolResult.Partial("3 of 5", "disk full") }).run("copy", emptyMap())
        val finished = log.snapshot().last()
        assertEquals(ActivityKind.TOOL_FINISHED, finished.kind)
        assertTrue("disk full" in finished.detail.orEmpty())
    }

    @Test
    fun `an exception from the tool is recorded and rethrown`() {
        val boom = IllegalStateException("kaput")
        val thrown = assertFailsWith<IllegalStateException> { runner(FakeRunner { throw boom }).run("x", emptyMap()) }
        assertSame(boom, thrown)
        assertEquals(ActivityKind.TOOL_FAILED, log.snapshot().last().kind)
    }

    @Test
    fun `a log that throws never changes the tool call`() {
        val broken = object : ActivityLog {
            override fun append(event: ActivityEvent) = error("disk gone")
            override fun snapshot() = emptyList<ActivityEvent>()
            override fun addListener(listener: (ActivityEvent) -> Unit): () -> Unit = {}
        }
        val brokenRun = ActivityRun.start(alice, "s", broken)
        val result = ActivityRecordingToolRunner(FakeRunner { ToolResult.Success("fine") }, brokenRun, broken)
            .run("t", emptyMap())
        assertEquals(ToolResult.Success("fine"), result)
    }

    @Test
    fun `scope is set only during the call and restored afterwards`() {
        assertNull(ActivityScope.current())
        var inside: ActivityScope.Current? = null
        runner(
            FakeRunner {
                inside = ActivityScope.current()
                ToolResult.Success("")
            },
        ).run("t", emptyMap())
        assertEquals(alice, inside?.actor)
        assertNull(ActivityScope.current())
    }
}

class ActivityApprovalPromptTest {
    private val log = InMemoryActivityLog()

    @Test
    fun `an approval inside a recorded call is attributed to that bot and the answer is unchanged`() {
        val run = ActivityRun.start(alice, "Started", log)
        val prompt = ActivityApprovalPrompt(ApprovalPrompt { true }, log)
        val runner = ActivityRecordingToolRunner(
            object : ToolRunner {
                override fun run(toolName: String, input: Map<String, String>, retryPolicy: RetryPolicy, isCancelled: () -> Boolean, mode: AgentMode?, initiator: Initiator?, grantId: String?): ToolResult =
                    if (prompt.requestApproval(toolName, input, "wipe cache")) ToolResult.Success("done") else ToolResult.Failure("declined")
            },
            run,
            log,
        )
        assertEquals(ToolResult.Success("done"), runner.run("clear_cache", emptyMap()))
        val kinds = log.snapshot().map { it.kind }
        assertEquals(
            listOf(
                ActivityKind.RUN_STARTED,
                ActivityKind.TOOL_STARTED,
                ActivityKind.NEEDS_APPROVAL,
                ActivityKind.APPROVAL_GRANTED,
                ActivityKind.TOOL_FINISHED,
            ),
            kinds,
        )
        assertTrue(log.snapshot().all { it.actor == alice })
    }

    @Test
    fun `a declined approval is recorded as declined`() {
        val run = ActivityRun.start(alice, "s", log)
        val prompt = ActivityApprovalPrompt(ApprovalPrompt { false }, log)
        ActivityScope.within(run.scope()) { assertFalse(prompt.requestApproval("reason only")) }
        assertEquals(ActivityKind.APPROVAL_DENIED, log.snapshot().last().kind)
    }

    @Test
    fun `an approval outside any recorded run is passed through without an entry`() {
        val prompt = ActivityApprovalPrompt(ApprovalPrompt { true }, log)
        assertTrue(prompt.requestApproval("x"))
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun `an exception from the prompt is recorded as no answer and rethrown`() {
        val run = ActivityRun.start(alice, "s", log)
        val prompt = ActivityApprovalPrompt(ApprovalPrompt { error("dialog crashed") }, log)
        assertFailsWith<IllegalStateException> { ActivityScope.within(run.scope()) { prompt.requestApproval("r") } }
        assertEquals(ActivityKind.APPROVAL_DENIED, log.snapshot().last().kind)
    }

    @Test
    fun `parallel bots on different threads are never mixed up`() {
        val aliceRun = ActivityRun.start(alice, "s", log)
        val bobRun = ActivityRun.start(bob, "s", log)
        val bothInside = CountDownLatch(2)
        val prompt = ActivityApprovalPrompt(
            ApprovalPrompt {
                bothInside.countDown()
                assertTrue(bothInside.await(5, TimeUnit.SECONDS))
                true
            },
            log,
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit(Callable { ActivityScope.within(aliceRun.scope()) { prompt.requestApproval("a-reason") } })
            val b = pool.submit(Callable { ActivityScope.within(bobRun.scope()) { prompt.requestApproval("b-reason") } })
            assertTrue(a.get(5, TimeUnit.SECONDS) && b.get(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        val needs = log.snapshot().filter { it.kind == ActivityKind.NEEDS_APPROVAL }
        assertEquals("a-reason", needs.single { it.actor == alice }.detail)
        assertEquals("b-reason", needs.single { it.actor == bob }.detail)
    }
}

class ActivityBoardTest {
    private val log = InMemoryActivityLog()
    private var now = 0L
    private fun at(t: Long) {
        now = t
    }
    private fun add(actor: ActivityActor, runId: String, kind: ActivityKind, summary: String = kind.name, detail: String? = null) =
        log.append(ActivityEvent(actor, runId, kind, summary, detail, now))

    @Test
    fun `a bot in the middle of a tool call is working and names the step`() {
        add(alice, "r1", ActivityKind.RUN_STARTED)
        add(alice, "r1", ActivityKind.TOOL_STARTED, "web_search")
        val row = ActivityBoard.overview(log.snapshot(), nowMillis = 1_000).single()
        assertEquals(ActivityStatus.WORKING, row.status)
        assertEquals("web_search", row.currentStep)
        assertEquals(1, row.steps)
    }

    @Test
    fun `waiting for approval shows what is being asked, and clears when answered`() {
        add(alice, "r1", ActivityKind.RUN_STARTED)
        add(alice, "r1", ActivityKind.TOOL_STARTED, "clear_cache")
        add(alice, "r1", ActivityKind.NEEDS_APPROVAL, "Waiting for you: clear_cache", "wipe cache")
        val waiting = ActivityBoard.overview(log.snapshot(), 1_000).single()
        assertEquals(ActivityStatus.WAITING_FOR_YOU, waiting.status)
        assertEquals("wipe cache", waiting.pendingApproval)

        add(alice, "r1", ActivityKind.APPROVAL_GRANTED)
        val after = ActivityBoard.overview(log.snapshot(), 1_000).single()
        assertEquals(ActivityStatus.WORKING, after.status)
        assertNull(after.pendingApproval)
    }

    @Test
    fun `terminal kinds map to done failed and stopped and clear the current step`() {
        add(alice, "r1", ActivityKind.TOOL_STARTED, "t")
        add(alice, "r1", ActivityKind.RUN_FINISHED)
        add(bob, "r2", ActivityKind.RUN_FAILED, "model unreachable")
        val byId = ActivityBoard.overview(log.snapshot(), 1_000).associateBy { it.actor.id }
        assertEquals(ActivityStatus.DONE, byId.getValue("bot-a").status)
        assertNull(byId.getValue("bot-a").currentStep)
        assertEquals(ActivityStatus.FAILED, byId.getValue("bot-b").status)
        assertEquals("model unreachable", byId.getValue("bot-b").lastSummary)
    }

    @Test
    fun `working with no news for too long is stalled, not failed`() {
        at(0)
        add(alice, "r1", ActivityKind.TOOL_STARTED, "long_tool")
        val row = ActivityBoard.overview(log.snapshot(), nowMillis = ActivityBoard.DEFAULT_STALL_MS + 1).single()
        assertEquals(ActivityStatus.STALLED, row.status)
        assertEquals("long_tool", row.currentStep)
    }

    @Test
    fun `waiting for the owner is never reported as stalled`() {
        at(0)
        add(alice, "r1", ActivityKind.NEEDS_APPROVAL, "Waiting for you: t", "r")
        val row = ActivityBoard.overview(log.snapshot(), nowMillis = ActivityBoard.DEFAULT_STALL_MS * 5).single()
        assertEquals(ActivityStatus.WAITING_FOR_YOU, row.status)
    }

    @Test
    fun `only the newest run of a bot is summarised`() {
        at(1)
        add(alice, "old", ActivityKind.RUN_FAILED, "old failure")
        at(2)
        add(alice, "new", ActivityKind.RUN_STARTED)
        val row = ActivityBoard.overview(log.snapshot(), 10).single()
        assertEquals("new", row.runId)
        assertEquals(ActivityStatus.WORKING, row.status)
    }

    @Test
    fun `rows that need the owner come first, then working, then finished`() {
        val carol = ActivityActor("bot-c", "Carol")
        at(1)
        add(alice, "r1", ActivityKind.RUN_FINISHED)
        at(2)
        add(bob, "r2", ActivityKind.TOOL_STARTED, "t")
        at(3)
        add(carol, "r3", ActivityKind.NEEDS_APPROVAL, "Waiting for you: t", "why")
        val order = ActivityBoard.overview(log.snapshot(), 10).map { it.actor.name }
        assertEquals(listOf("Carol", "Bob", "Alice"), order)
    }

    @Test
    fun `timeline is newest first and limited to one actor and run`() {
        at(1)
        add(alice, "r1", ActivityKind.RUN_STARTED)
        at(2)
        add(bob, "r9", ActivityKind.RUN_STARTED)
        at(3)
        add(alice, "r1", ActivityKind.TOOL_STARTED, "t")
        val timeline = ActivityBoard.timeline(log.snapshot(), "bot-a")
        assertEquals(listOf(ActivityKind.TOOL_STARTED, ActivityKind.RUN_STARTED), timeline.map { it.kind })
        assertTrue(ActivityBoard.timeline(log.snapshot(), "nobody").isEmpty())
    }

    @Test
    fun `a run ends only once`() {
        val run = ActivityRun.start(alice, "s", log)
        run.finish("first")
        run.fail("late failure must be ignored")
        assertTrue(run.isEnded)
        assertEquals(listOf(ActivityKind.RUN_STARTED, ActivityKind.RUN_FINISHED), log.snapshot().map { it.kind })
        assertNotNull(log.snapshot().last().summary)
    }
}
