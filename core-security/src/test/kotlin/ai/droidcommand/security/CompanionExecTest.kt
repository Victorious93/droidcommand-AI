package ai.droidcommand.security

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.ToolSpec
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class AppsTool(var output: String = "com.a.app\ncom.b.app") : Tool {
    var runs = 0
    override val spec = ToolSpec(name = "list_installed_apps", description = "Lists installed apps")
    override fun execute(input: Map<String, String>): ToolResult {
        runs++
        return ToolResult.Success(output)
    }
}

private class DangerTool : Tool {
    var runs = 0
    override val spec = ToolSpec(name = "wipe_everything", description = "d", securityLevel = SecurityLevel.ROOT, requiresRoot = true)
    override fun execute(input: Map<String, String>): ToolResult {
        runs++
        return ToolResult.Success("wiped")
    }
}

class CompanionExecTest {
    private val now = 1_000_000L
    private val caller = "com.victorious.bots"

    private class Rig(val tool: AppsTool, val danger: DangerTool, val broker: ExecBroker, val audit: InMemoryAuditLog, val store: ExecReceiptStore, val clock: LongArray)

    private fun rig(approvalAllows: Boolean = true, store: ExecReceiptStore = InMemoryExecReceiptStore(), catalog: ExecCatalog = ExecCatalog.DEFAULT): Rig {
        val tool = AppsTool()
        val danger = DangerTool()
        val registry = ToolRegistry().apply { register(tool); register(danger) }
        val sm = AgentStateMachine()
        val audit = InMemoryAuditLog()
        val secure = SecureToolExecutor(
            registry,
            ToolExecutor(registry, sm, sleep = { }),
            sm,
            SecurityPolicyEnforcer(SecurityPolicy(rootEnabled = false)),
            ApprovalPrompt { approvalAllows },
            auditLog = audit,
        )
        val clock = longArrayOf(now)
        return Rig(tool, danger, ExecBroker(catalog, secure, store, audit, { clock[0] }), audit, store, clock)
    }

    private fun exec(id: String = "req-0001-abc", cap: String = "device.apps.list", expires: Long = now + 60_000, extra: String = ""): String =
        """{"v":1,"task":"execute","request_id":"$id","capability":"$cap","expires_at":$expires,"reason":"list apps"$extra}"""

    private fun parsed(raw: String): ExecRequest.Execute =
        (assertIs<ExecParse.Valid>(CompanionExec.parse(raw, now)).request as ExecRequest.Execute)

    @Test
    fun `contract strings and version`() {
        assertEquals("ai.droidcommand.action.COMPANION_EXEC", CompanionExec.ACTION)
        assertEquals(1, CompanionExec.CONTRACT_VERSION)
        // The text-only contract must remain text-only.
        assertTrue(CompanionAssist.ACTION != CompanionExec.ACTION)
    }

    @Test
    fun `parser rejects malformed expired and over-long-lived requests`() {
        assertIs<ExecParse.Invalid>(CompanionExec.parse(null, now))
        assertIs<ExecParse.Invalid>(CompanionExec.parse("not json", now))
        assertIs<ExecParse.Invalid>(CompanionExec.parse("""{"v":2,"task":"discover","request_id":"req-0001-abc"}""", now))
        assertIs<ExecParse.Invalid>(CompanionExec.parse("""{"v":1,"task":"discover","request_id":"short"}""", now))
        assertIs<ExecParse.Invalid>(CompanionExec.parse("""{"v":1,"task":"shell","request_id":"req-0001-abc"}""", now))
        assertIs<ExecParse.Invalid>(CompanionExec.parse(exec(expires = now - 1), now))
        assertIs<ExecParse.Invalid>(CompanionExec.parse(exec(expires = now + CompanionExec.MAX_TTL_MS + 1), now))
        assertIs<ExecParse.Invalid>(CompanionExec.parse(exec().replace("\"expires_at\":${now + 60_000},", ""), now))
        assertIs<ExecParse.Invalid>(CompanionExec.parse(exec(extra = ""","args":{"a":{"nested":1}}"""), now))
        assertIs<ExecParse.Valid>(CompanionExec.parse("""{"v":1,"task":"discover","request_id":"req-0001-abc"}""", now))
        assertIs<ExecParse.Valid>(CompanionExec.parse("""{"v":1,"task":"receipt","request_id":"req-0001-abc"}""", now))
    }

    @Test
    fun `discover lists only catalogued read-only capabilities and runs nothing`() {
        val r = rig()
        val json = r.broker.discover(caller)
        assertTrue(json.contains("device.apps.list"))
        assertFalse(json.contains("wipe_everything"))
        assertFalse(json.contains("list_installed_apps"), "internal tool names are not exposed")
        assertEquals(0, r.tool.runs)
        assertTrue(ExecCatalog.DEFAULT.all().all { it.risk == RiskTier.READ_ONLY })
    }

    @Test
    fun `a request without a caller identity is refused`() {
        val r = rig()
        assertIs<ExecPrepared.Rejected>(r.broker.prepare("", parsed(exec())))
        assertEquals(0, r.tool.runs)
    }

    @Test
    fun `unknown capability and unlisted arguments are refused before any consent`() {
        val r = rig()
        assertIs<ExecPrepared.Rejected>(r.broker.prepare(caller, parsed(exec(cap = "wipe_everything"))))
        assertIs<ExecPrepared.Rejected>(r.broker.prepare(caller, parsed(exec(cap = "list_installed_apps"))))
        assertIs<ExecPrepared.Rejected>(r.broker.prepare(caller, parsed(exec(extra = ""","args":{"cmd":"rm -rf /"}"""))))
        assertEquals(0, r.tool.runs)
        assertEquals(0, r.danger.runs)
        assertTrue(r.audit.all().any { it.type == AuditEventType.ACCESS_DENIED })
    }

    @Test
    fun `nothing runs until the owner allows, and Allow produces a genuine receipt`() {
        val r = rig()
        val prep = assertIs<ExecPrepared.NeedsConsent>(r.broker.prepare(caller, parsed(exec())))
        assertEquals(0, r.tool.runs, "preparing a request never runs it")
        assertEquals("device.apps.list", prep.consent.capability.id)
        val receipt = r.broker.finish(prep.consent, allowed = true)
        assertEquals(ExecOutcome.EXECUTED, receipt.outcome)
        assertEquals(1, r.tool.runs)
        assertTrue(receipt.output.contains("com.a.app"))
        assertTrue(r.audit.all().any { it.type == AuditEventType.APPROVAL_APPROVED })
        val json = r.broker.receiptJson(caller, "req-0001-abc")
        assertTrue(json.contains("\"outcome\":\"executed\""))
    }

    @Test
    fun `deny means nothing ran and the receipt says so`() {
        val r = rig()
        val prep = assertIs<ExecPrepared.NeedsConsent>(r.broker.prepare(caller, parsed(exec())))
        val receipt = r.broker.finish(prep.consent, allowed = false)
        assertEquals(ExecOutcome.DENIED, receipt.outcome)
        assertEquals(0, r.tool.runs)
        assertTrue(r.audit.all().any { it.type == AuditEventType.APPROVAL_DENIED })
    }

    @Test
    fun `a request that expires while waiting for the owner never runs`() {
        val r = rig()
        val prep = assertIs<ExecPrepared.NeedsConsent>(r.broker.prepare(caller, parsed(exec(expires = now + 1_000))))
        r.clock[0] = now + 2_000
        val receipt = r.broker.finish(prep.consent, allowed = true)
        assertEquals(ExecOutcome.EXPIRED, receipt.outcome)
        assertEquals(0, r.tool.runs)
        assertTrue(r.audit.all().any { it.type == AuditEventType.APPROVAL_TIMED_OUT })
    }

    @Test
    fun `expire records an expired receipt without running anything`() {
        val r = rig()
        val prep = assertIs<ExecPrepared.NeedsConsent>(r.broker.prepare(caller, parsed(exec())))
        val receipt = r.broker.expire(prep.consent)
        assertEquals(ExecOutcome.EXPIRED, receipt.outcome)
        assertEquals(0, r.tool.runs)
        assertEquals(ExecOutcome.EXPIRED, r.broker.finish(prep.consent, allowed = true).outcome, "a late Allow cannot revive it")
        assertEquals(0, r.tool.runs)
    }

    @Test
    fun `a repeated request id never executes twice and returns the stored receipt`() {
        val r = rig()
        val prep = assertIs<ExecPrepared.NeedsConsent>(r.broker.prepare(caller, parsed(exec())))
        r.broker.finish(prep.consent, allowed = true)
        val again = r.broker.prepare(caller, parsed(exec()))
        val done = assertIs<ExecPrepared.Done>(again)
        assertEquals(ExecOutcome.EXECUTED, done.receipt.outcome)
        // Even a stale consent object cannot trigger a second run.
        r.broker.finish(prep.consent, allowed = true)
        assertEquals(1, r.tool.runs)
    }

    @Test
    fun `receipts are scoped to the caller that made the request`() {
        val r = rig()
        val prep = assertIs<ExecPrepared.NeedsConsent>(r.broker.prepare(caller, parsed(exec())))
        r.broker.finish(prep.consent, allowed = true)
        assertTrue(r.broker.receiptJson("com.victorious.keyboard", "req-0001-abc").contains("\"known\":false"))
        assertTrue(r.broker.receiptJson(caller, "req-unknown-000").contains("\"known\":false"))
        // Another caller reusing the same id is a different request.
        assertIs<ExecPrepared.NeedsConsent>(r.broker.prepare("com.victorious.keyboard", parsed(exec())))
    }

    @Test
    fun `a tool failure is a failed receipt, never executed`() {
        val registry = ToolRegistry()
        val failing = object : Tool {
            override val spec = ToolSpec(name = "list_installed_apps", description = "d")
            override fun execute(input: Map<String, String>): ToolResult = ToolResult.Failure("permission missing")
        }
        registry.register(failing)
        val sm = AgentStateMachine()
        val runner: ToolRunner = SecureToolExecutor(registry, ToolExecutor(registry, sm, sleep = { }), sm, SecurityPolicyEnforcer(SecurityPolicy(rootEnabled = false)), ApprovalPrompt { true })
        val broker = ExecBroker(ExecCatalog.DEFAULT, runner, InMemoryExecReceiptStore(), null, { now })
        val prep = assertIs<ExecPrepared.NeedsConsent>(broker.prepare(caller, parsed(exec())))
        val receipt = broker.finish(prep.consent, allowed = true)
        assertEquals(ExecOutcome.FAILED, receipt.outcome)
        assertTrue(receipt.detail.contains("permission"))
    }

    @Test
    fun `the broker cannot be pointed at a tool outside the catalogue even if one is misconfigured to need approval`() {
        // A catalogue entry that maps to a root tool is still gated by the existing policy: rootEnabled=false denies it.
        val bad = ExecCatalog(listOf(ExecCapability("x.wipe", "wipe_everything", "bad", RiskTier.DESTRUCTIVE)))
        val r = rig(catalog = bad)
        val prep = assertIs<ExecPrepared.NeedsConsent>(r.broker.prepare(caller, parsed(exec(cap = "x.wipe"))))
        val receipt = r.broker.finish(prep.consent, allowed = true)
        assertEquals(ExecOutcome.FAILED, receipt.outcome)
        assertEquals(0, r.danger.runs, "DroidCommand's own policy still denies root when root is off")
    }

    @Test
    fun `receipts survive a restart with the file store and a full store fails closed`() {
        val file = Files.createTempDirectory("exec").resolve("receipts.jsonl")
        val s1 = JsonFileExecReceiptStore(file)
        val r1 = rig(store = s1)
        val prep = assertIs<ExecPrepared.NeedsConsent>(r1.broker.prepare(caller, parsed(exec())))
        r1.broker.finish(prep.consent, allowed = true)
        val s2 = JsonFileExecReceiptStore(file)
        val got = assertNotNull(s2.get(caller, "req-0001-abc"))
        assertEquals(ExecOutcome.EXECUTED, got.outcome)
        assertNull(s2.get("other", "req-0001-abc"))
        val tiny = JsonFileExecReceiptStore(Files.createTempDirectory("exec2").resolve("r.jsonl"), capacity = 1)
        tiny.putIfAbsent(got.copy(requestId = "req-aaaa-0001"))
        assertEquals(ExecOutcome.REFUSED, tiny.putIfAbsent(got.copy(requestId = "req-aaaa-0002")).outcome)
    }

    @Test
    fun `receipt output is bounded`() {
        val r = rig()
        r.tool.output = "x".repeat(50_000)
        val prep = assertIs<ExecPrepared.NeedsConsent>(r.broker.prepare(caller, parsed(exec())))
        assertEquals(CompanionExec.MAX_OUTPUT_CHARS, r.broker.finish(prep.consent, allowed = true).output.length)
    }
}
