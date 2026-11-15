package ai.droidcommand.systemsettings

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.InMemoryAuditLog
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendSelectionTest {
    private val ANIM = SettingsCatalog.WINDOW_ANIMATION_SCALE

    @Test
    fun leastPrivilegedCapableBackendWins() {
        val h = Harness(shizuku = FakeBackend(AccessKind.SHIZUKU), root = FakeBackend(AccessKind.ROOT))
        h.config = AccessConfig(allowed = AccessKind.entries.toSet())
        val s = assertIs<Selection.Chosen>(h.engine.select(setOf(G), write = true, userIds = setOf(0)))
        assertEquals(AccessKind.STANDARD, s.backend.kind)
    }

    @Test
    fun standardWithoutWriteSecureFallsToShizukuOnlyAtPlanTimeNotAtRunTime() {
        val h = Harness(shizuku = FakeBackend(AccessKind.SHIZUKU))
        h.standard.writable = setOf(S) // WRITE_SECURE_SETTINGS missing: Global is read-only for standard access
        assertEquals(AccessKind.SHIZUKU, assertIs<Selection.Chosen>(h.engine.select(setOf(G), write = true, userIds = setOf(0))).backend.kind)
        assertEquals(AccessKind.STANDARD, assertIs<Selection.Chosen>(h.engine.select(setOf(S), write = true, userIds = setOf(0))).backend.kind)
    }

    @Test
    fun rootIsNeverUsedUnlessTheOwnerAllowedItEvenWhenItIsTheOnlyReadyBackend() {
        val h = Harness(root = FakeBackend(AccessKind.ROOT))
        h.standard.state = BackendState.UNAVAILABLE
        h.standard.put(G, ANIM, "1.0")
        h.root.put(G, ANIM, "1.0")
        val none = assertIs<Selection.None>(h.engine.select(setOf(G), write = true, userIds = setOf(0)))
        assertTrue(none.reasons.getValue(AccessKind.ROOT).contains("not allowed"))
        val r = assertIs<ApplyResult.Rejected>(h.uiApply(h.putReq("x", RequestSource.UI, Triple(G, ANIM, "0.5"))))
        assertTrue(r.plan.blockers.any { it.code == IssueCode.NO_BACKEND })
        assertTrue(h.root.writeLog.isEmpty())
        h.config = AccessConfig(allowed = setOf(AccessKind.STANDARD, AccessKind.SHIZUKU, AccessKind.ROOT))
        assertEquals(AccessKind.ROOT, assertIs<Selection.Chosen>(h.engine.select(setOf(G), write = true, userIds = setOf(0))).backend.kind)
    }

    @Test
    fun aRevokedPermissionAtRunTimeIsReportedAndNeverSilentlyRetriedOnAHigherTier() {
        val h = Harness(shizuku = FakeBackend(AccessKind.SHIZUKU), root = FakeBackend(AccessKind.ROOT))
        h.config = AccessConfig(allowed = AccessKind.entries.toSet())
        h.standard.put(G, ANIM, "1.0")
        h.shizuku.put(G, ANIM, "1.0")
        h.root.put(G, ANIM, "1.0")
        h.standard.failWrites = BackendFailure(FailureKind.PERMISSION_DENIED, "revoked after the probe") // status still says ready
        val op = assertIs<ApplyResult.Finished>(h.uiApply(h.putReq("x", RequestSource.UI, Triple(G, ANIM, "0.5")))).operation
        assertEquals(EntryOutcome.PERMISSION_DENIED, op.entries.single().outcome)
        assertEquals(AccessKind.STANDARD, op.backend)
        assertTrue(h.shizuku.writeLog.isEmpty() && h.root.writeLog.isEmpty())
    }

    @Test
    fun aDeadBackendAtRunTimeStopsTheChange() {
        val h = Harness(shizuku = FakeBackend(AccessKind.SHIZUKU))
        h.standard.state = BackendState.UNAVAILABLE
        h.shizuku.put(G, ANIM, "1.0")
        h.shizuku.failWrites = BackendFailure(FailureKind.UNAVAILABLE, "Shizuku stopped")
        val op = assertIs<ApplyResult.Finished>(h.uiApply(h.putReq("x", RequestSource.UI, Triple(G, ANIM, "0.5")))).operation
        assertEquals(EntryOutcome.BACKEND_UNAVAILABLE, op.entries.single().outcome)
        assertEquals(OperationStatus.FAILED_ROLLED_BACK, op.status)
    }

    @Test
    fun globalIsDeviceWideAndOtherUsersNeedOwnerOptInAndACapableBackend() {
        val h = Harness(shizuku = FakeBackend(AccessKind.SHIZUKU))
        val global10 = SettingKey.of(G, ANIM, 10)
        assertEquals(0, global10.userId)
        val other = SettingKey.of(S, SettingsCatalog.SCREEN_OFF_TIMEOUT, 10)
        val req = ChangeRequest("x", listOf(Change(other, Proposed.Put("60000"))), RequestSource.UI)
        assertTrue(h.engine.plan(req).blockers.any { it.code == IssueCode.CROSS_USER })
        // Opt in: standard access still cannot reach another user, so selection moves to the capable backend, which must itself be allowed.
        h.config = AccessConfig(allowOtherUsers = true)
        h.standard.crossUser = false // real standard access only reaches the app's own user
        h.shizuku.put(S, SettingsCatalog.SCREEN_OFF_TIMEOUT, "30000", user = 10)
        val plan = h.engine.plan(req)
        assertEquals(AccessKind.SHIZUKU, plan.backend)
        assertTrue(plan.blockers.isEmpty(), plan.blockers.toString())
        // A root-only world does not authorize cross-user by itself.
        val h2 = Harness(root = FakeBackend(AccessKind.ROOT))
        h2.config = AccessConfig(allowed = AccessKind.entries.toSet(), allowOtherUsers = false)
        assertTrue(h2.engine.plan(req).blockers.isNotEmpty())
    }

    @Test
    fun readsNeverRequireAWritableBackend() {
        val h = Harness()
        h.standard.writable = emptySet()
        h.standard.put(G, ANIM, "1.0")
        assertEquals(Snapshot.Present("1.0"), (h.engine.read(h.key(G, ANIM)).result as ReadResult.Value).snapshot)
    }
}

class SourcePolicyTest {
    private val ANIM = SettingsCatalog.WINDOW_ANIMATION_SCALE

    private fun harness(): Harness = Harness().also { it.standard.put(G, ANIM, "1.0") }

    @Test
    fun assistantIsRefusedWhileItsGateIsOffAndAsksEveryTimeWhenOn() {
        val h = harness()
        val req = h.putReq("a", RequestSource.AI, Triple(G, ANIM, "0.5"))
        assertTrue(assertIs<ApplyResult.Rejected>(h.engine.apply(req, Confirmation.None)).plan.blockers.any { it.code == IssueCode.SOURCE_NOT_ALLOWED })
        assertTrue(h.approvals.isEmpty() && h.standard.writeLog.isEmpty())
        h.config = AccessConfig(assistantEnabled = true)
        h.approve = false
        assertIs<ApplyResult.Declined>(h.engine.apply(req, Confirmation.None))
        assertEquals(1, h.approvals.size)
        assertTrue(h.approvals.single().single().contains("1.0 → 0.5"), "the approval shows old and new values")
        assertTrue(h.standard.writeLog.isEmpty())
        h.approve = true
        assertIs<ApplyResult.Finished>(h.engine.apply(req, Confirmation.None))
        assertEquals("0.5", h.standard.get(G, ANIM))
        // A second identical request asks again: nothing is remembered.
        h.standard.put(G, ANIM, "1.0")
        h.engine.apply(req, Confirmation.None)
        assertEquals(3, h.approvals.size)
    }

    @Test
    fun theGateIsRecheckedAtExecutionNotOnlyWhenTheRequestWasMade() {
        val h = harness()
        h.config = AccessConfig(assistantEnabled = true)
        val req = h.putReq("a", RequestSource.AI, Triple(G, ANIM, "0.5"))
        assertTrue(h.engine.plan(req).executable)
        h.config = AccessConfig(assistantEnabled = false) // owner turned it off after the suggestion
        assertIs<ApplyResult.Rejected>(h.engine.apply(req, Confirmation.None))
        assertTrue(h.standard.writeLog.isEmpty())
    }

    @Test
    fun assistantCannotTouchUnreviewedOrHighImpactSettingsOrDelete() {
        val h = harness()
        h.config = AccessConfig(assistantEnabled = true)
        val unknown = h.engine.plan(h.putReq("u", RequestSource.AI, Triple(G, "dca_unknown", "1")))
        assertTrue(unknown.blockers.any { it.code == IssueCode.NEEDS_MANUAL_REVIEW })
        val hi = h.engine.plan(h.putReq("h", RequestSource.AI, Triple(SEC, "enabled_accessibility_services", "evil/.Service")))
        assertTrue(hi.blockers.any { it.code == IssueCode.HIGH_IMPACT })
        val del = h.engine.plan(ChangeRequest("d", listOf(Change(h.key(G, ANIM), Proposed.Delete)), RequestSource.AI))
        assertTrue(del.blockers.isNotEmpty())
        // The assistant also cannot pass its own acknowledgement.
        val ack = h.engine.plan(h.putReq("h", RequestSource.AI, Triple(SEC, "default_input_method", "x/y"), ack = setOf(h.key(SEC, "default_input_method").id), manual = setOf(h.key(SEC, "default_input_method").id)))
        assertTrue(ack.blockers.any { it.code == IssueCode.HIGH_IMPACT })
    }

    @Test
    fun remoteIsRefusedBecauseNoRemoteSettingsCommandExists() {
        val h = harness()
        val r = assertIs<ApplyResult.Rejected>(h.engine.apply(h.putReq("r", RequestSource.REMOTE, Triple(G, ANIM, "0.5")), Confirmation.None))
        assertTrue(r.plan.blockers.single { it.code == IssueCode.SOURCE_NOT_ALLOWED }.message.contains("no remote settings command"))
        assertTrue(h.standard.writeLog.isEmpty())
    }

    @Test
    fun aFutureRemoteAuthorizerIsNecessaryButNotSufficientTheOwnerStillApproves() {
        val h = Harness(withRemote = true, remoteDecision = RemoteDecision.Denied("not paired"))
        h.standard.put(G, ANIM, "1.0")
        val req = h.putReq("r", RequestSource.REMOTE, Triple(G, ANIM, "0.5"))
        assertTrue(assertIs<ApplyResult.Rejected>(h.engine.apply(req, Confirmation.None)).plan.blockers.any { it.message == "not paired" })
        h.remoteDecision = RemoteDecision.Allowed
        h.approve = false
        assertIs<ApplyResult.Declined>(h.engine.apply(req, Confirmation.None))
        h.approve = true
        assertIs<ApplyResult.Finished>(h.engine.apply(req, Confirmation.None))
        // Remote requests also cannot reach high-impact entries.
        assertTrue(h.engine.plan(h.putReq("x", RequestSource.REMOTE, Triple(G, "adb_enabled", "1"))).blockers.any { it.code == IssueCode.HIGH_IMPACT })
    }

    @Test
    fun uiNeedsManualReviewForUnknownKeysAndAnAcknowledgementForHighImpactOnes() {
        val h = harness()
        val k = h.key(G, "dca_unknown")
        assertTrue(h.engine.plan(h.putReq("u", RequestSource.UI, Triple(G, "dca_unknown", "1"))).blockers.any { it.code == IssueCode.NEEDS_MANUAL_REVIEW })
        assertTrue(h.engine.plan(h.putReq("u", RequestSource.UI, Triple(G, "dca_unknown", "1"), manual = setOf(k.id))).blockers.isEmpty())
        val hi = h.key(SEC, "default_input_method")
        assertTrue(h.engine.plan(h.putReq("h", RequestSource.UI, Triple(SEC, "default_input_method", "a/b"), manual = setOf(hi.id))).blockers.any { it.code == IssueCode.HIGH_IMPACT })
        val ok = h.engine.plan(h.putReq("h", RequestSource.UI, Triple(SEC, "default_input_method", "a/b"), manual = setOf(hi.id), ack = setOf(hi.id)))
        assertTrue(ok.blockers.isEmpty(), ok.blockers.toString())
    }

    @Test
    fun hostileValuesAreRejectedByValidation() {
        val h = harness()
        for (bad in listOf("0.5; rm -rf /", "\$(id)", "0.5\n1", "abc", "-1", "11", "NaN", "1e3", "")) {
            val plan = h.engine.plan(h.putReq("x", RequestSource.UI, Triple(G, ANIM, bad)))
            assertTrue(plan.blockers.any { it.code == IssueCode.INVALID_VALUE }, "accepted '$bad'")
        }
        for (good in listOf("0", "0.5", "1", "1.0", "2.5", "10")) {
            assertTrue(h.engine.plan(h.putReq("x", RequestSource.UI, Triple(G, ANIM, good))).blockers.isEmpty(), good)
        }
    }

    @Test
    fun theSameDecisionComesFromEveryEntryPointForTheSameChange() {
        val h = harness()
        h.config = AccessConfig(assistantEnabled = true)
        fun blocked(source: RequestSource) = h.engine.plan(h.putReq("x", source, Triple(SEC, "android_id", "1234"))).blockers.any { it.code == IssueCode.HIGH_IMPACT }
        assertTrue(RequestSource.entries.filter { it != RequestSource.BOOT_REPLAY }.all { blocked(it) })
        // And the same plain value is accepted identically by all sources that are allowed to ask at all.
        val digestUi = h.engine.plan(h.putReq("x", RequestSource.UI, Triple(G, ANIM, "0.5")))
        val ai = h.engine.plan(h.putReq("x", RequestSource.AI, Triple(G, ANIM, "0.5")))
        assertEquals(digestUi.entries.map { it.change }, ai.entries.map { it.change })
        assertTrue(digestUi.blockers.isEmpty() && ai.blockers.isEmpty())
    }

    @Test
    fun theAssistantToolsAreRefusedForARemoteInitiatorByTheSecureExecutor() {
        val h = harness()
        val profiles = ProfileService(h.engine) { h.device }
        val registry = ToolRegistry()
        registry.register(SystemSettingsChangeTool(h.engine, { StarterProfiles.all() }, profiles, { h.device }) { true })
        registry.register(SystemSettingsReadTool(h.engine, { StarterProfiles.all() }, profiles, { h.device }) { true })
        val audit = InMemoryAuditLog()
        val sm = AgentStateMachine()
        val exec = SecureToolExecutor(
            registry = registry,
            delegate = ToolExecutor(registry, sm),
            stateMachine = sm,
            enforcer = SecurityPolicyEnforcer(SecurityPolicy(grantedCategories = setOf(ai.droidcommand.agent.PermissionCategory.DEVICE_CONTROL))),
            approvalPrompt = { true },
            auditLog = audit,
        )
        val input = mapOf("action" to "set", "table" to "global", "key" to ANIM, "value" to "0.5")
        val denied = exec.run(SystemSettingsChangeTool.NAME, input, RetryPolicy(), { false }, AgentMode.PILOT, Initiator.REMOTE)
        assertIs<ToolResult.Failure>(denied)
        assertTrue(audit.all().any { it.type == AuditEventType.INITIATOR_DENIED })
        assertTrue(h.standard.writeLog.isEmpty())
        val deniedRead = exec.run(SystemSettingsReadTool.NAME, mapOf("action" to "curated"), RetryPolicy(), { false }, AgentMode.PILOT, Initiator.REMOTE)
        assertIs<ToolResult.Failure>(deniedRead)
        assertNull(h.standard.get(G, "never"))
    }
}
