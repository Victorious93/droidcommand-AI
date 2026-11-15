package ai.droidcommand.systemsettings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineApplyTest {
    private val ANIM = SettingsCatalog.WINDOW_ANIMATION_SCALE
    private val TRANS = SettingsCatalog.TRANSITION_ANIMATION_SCALE

    private fun finished(r: ApplyResult): Operation = assertIs<ApplyResult.Finished>(r).operation

    @Test
    fun editReadbackHistoryRestoreReadback() {
        val h = Harness()
        h.standard.put(G, ANIM, "1.0")
        val op = finished(h.uiApply(h.putReq("Quick", RequestSource.UI, Triple(G, ANIM, "0.5"))))
        assertEquals(OperationStatus.COMPLETED, op.status)
        assertEquals("0.5", h.standard.get(G, ANIM))
        val e = op.entries.single()
        assertEquals(Snapshot.Present("1.0"), e.previous)
        assertEquals(EntryOutcome.STORED_EFFECT_UNVERIFIED, e.outcome)
        assertEquals(Snapshot.Present("0.5"), e.observedAfterWrite)
        assertTrue(e.startsBaseline)
        assertEquals(1, h.engine.history().size)
        val row = h.engine.modified().single()
        assertEquals(Snapshot.Present("1.0"), row.baseline)
        assertEquals(Snapshot.Present("0.5"), row.current)
        assertTrue(row.differsFromBaseline)
        assertTrue(!row.changedElsewhere)

        val restored = finished(h.engine.restore(RestoreRequest(RequestSource.UI, operationId = op.id), confirm(h, RestoreRequest(RequestSource.UI, operationId = op.id))))
        assertEquals(OperationStatus.COMPLETED, restored.status)
        assertEquals("1.0", h.standard.get(G, ANIM))
        assertTrue(h.engine.modified().isEmpty(), "returning to the original value clears the baseline")
        assertEquals(RestoreStatus.RESTORED, h.engine.history().first { it.id == op.id }.entries.single().restoreStatus)
    }

    private fun confirm(h: Harness, r: RestoreRequest) = Confirmation.UserConfirmed(h.engine.previewRestore(r).digest)

    @Test
    fun absentEntryIsDeletedOnRestoreAndEmptyStringIsNot() {
        val h = Harness()
        // Absent originally: restoring must delete, not write "".
        val a = finished(h.uiApply(h.putReq("add", RequestSource.UI, Triple(G, "dca_test_key", "x"), manual = setOf(h.key(G, "dca_test_key").id))))
        assertEquals(Snapshot.Absent, a.entries.single().previous)
        val ra = RestoreRequest(RequestSource.UI, operationId = a.id)
        finished(h.engine.restore(ra, confirm(h, ra)))
        assertNull(h.standard.get(G, "dca_test_key"))
        assertEquals(listOf(h.key(G, "dca_test_key").id), h.standard.deleteLog)

        // Originally the empty string: restoring must write "" back, not delete.
        h.standard.put(G, "dca_empty", "")
        val b = finished(h.uiApply(h.putReq("set", RequestSource.UI, Triple(G, "dca_empty", "v"), manual = setOf(h.key(G, "dca_empty").id))))
        assertEquals(Snapshot.Present(""), b.entries.single().previous)
        val rb = RestoreRequest(RequestSource.UI, operationId = b.id)
        finished(h.engine.restore(rb, confirm(h, rb)))
        assertEquals("", h.standard.get(G, "dca_empty"))
        assertEquals(1, h.standard.deleteLog.size, "the empty-string entry was never deleted")
    }

    @Test
    fun readbackMismatchIsReportedAndRolledBack() {
        val h = Harness()
        h.standard.put(G, ANIM, "1.0")
        h.standard.rewrite = { _, v -> if (v == "0.5") "0.25" else v } // a provider that normalizes the value
        val op = finished(h.uiApply(h.putReq("Quick", RequestSource.UI, Triple(G, ANIM, "0.5"))))
        assertEquals(OperationStatus.FAILED_ROLLED_BACK, op.status)
        val e = op.entries.single()
        assertEquals(EntryOutcome.READBACK_MISMATCH, e.outcome)
        assertEquals(Snapshot.Present("0.25"), e.observedAfterWrite)
        assertEquals(RestoreStatus.AUTO_ROLLED_BACK, e.restoreStatus)
        assertEquals("1.0", h.standard.get(G, ANIM))
    }

    @Test
    fun swallowedWriteIsAMismatchNotASuccess() {
        val h = Harness()
        h.standard.put(G, ANIM, "1.0")
        h.standard.swallow = setOf(h.key(G, ANIM).id)
        val op = finished(h.uiApply(h.putReq("Quick", RequestSource.UI, Triple(G, ANIM, "0.5"))))
        assertEquals(EntryOutcome.READBACK_MISMATCH, op.entries.single().outcome)
    }

    @Test
    fun permissionFailureIsDistinctFromRejectedValue() {
        val h = Harness()
        h.standard.put(G, ANIM, "1.0")
        h.standard.failWrites = BackendFailure(FailureKind.PERMISSION_DENIED, "no WRITE_SECURE_SETTINGS")
        assertEquals(EntryOutcome.PERMISSION_DENIED, finished(h.uiApply(h.putReq("a", RequestSource.UI, Triple(G, ANIM, "0.5")))).entries.single().outcome)
        h.standard.failWrites = BackendFailure(FailureKind.REJECTED, "Android did not store the value.")
        assertEquals(EntryOutcome.REJECTED_VALUE, finished(h.uiApply(h.putReq("b", RequestSource.UI, Triple(G, ANIM, "0.5")))).entries.single().outcome)
        h.standard.failWrites = BackendFailure(FailureKind.TIMEOUT, "slow")
        assertEquals(EntryOutcome.TIMEOUT, finished(h.uiApply(h.putReq("c", RequestSource.UI, Triple(G, ANIM, "0.5")))).entries.single().outcome)
        assertEquals("1.0", h.standard.get(G, ANIM))
    }

    @Test
    fun effectVerifierUpgradesTheOutcomeOnlyWhenItObservesTheEffect() {
        val h = Harness(verifiers = listOf(EffectVerifier { k, v -> if (k.name == ANIM) EffectVerdict(v == "0.5", "observed") else null }))
        h.standard.put(G, ANIM, "1.0")
        h.standard.put(G, TRANS, "1.0")
        val op = finished(h.uiApply(h.putReq("both", RequestSource.UI, Triple(G, ANIM, "0.5"), Triple(G, TRANS, "0.5"))))
        assertEquals(EntryOutcome.EFFECT_VERIFIED, op.entries.first { it.key.name == ANIM }.outcome)
        assertEquals(EntryOutcome.STORED_EFFECT_UNVERIFIED, op.entries.first { it.key.name == TRANS }.outcome)
    }

    @Test
    fun multiEntryFailureRollsBackEarlierWritesNewestFirst() {
        val h = Harness()
        h.standard.put(G, ANIM, "1.0")
        h.standard.put(G, TRANS, "1.0")
        h.standard.put(G, SettingsCatalog.ANIMATOR_DURATION_SCALE, "1.0")
        h.standard.failWriteOnKey = mapOf(h.key(G, SettingsCatalog.ANIMATOR_DURATION_SCALE).id to BackendFailure(FailureKind.REJECTED, "no"))
        val op = finished(
            h.uiApply(
                h.putReq("all", RequestSource.UI, Triple(G, ANIM, "0.5"), Triple(G, TRANS, "0.5"), Triple(G, SettingsCatalog.ANIMATOR_DURATION_SCALE, "0.5")),
            ),
        )
        assertEquals(OperationStatus.FAILED_ROLLED_BACK, op.status)
        assertEquals("1.0", h.standard.get(G, ANIM))
        assertEquals("1.0", h.standard.get(G, TRANS))
        assertEquals(listOf(RestoreStatus.AUTO_ROLLED_BACK, RestoreStatus.AUTO_ROLLED_BACK, RestoreStatus.NOT_RESTORED), op.entries.map { it.restoreStatus })
        // writes: ANIM, TRANS, then the compensation TRANS back, ANIM back (newest first)
        assertEquals(listOf("global:0:$ANIM=0.5", "global:0:$TRANS=0.5", "global:0:$TRANS=1.0", "global:0:$ANIM=1.0"), h.standard.writeLog)
    }

    @Test
    fun rollbackFailureIsReportedAsPartial() {
        val h = Harness()
        h.standard.put(G, ANIM, "1.0")
        h.standard.put(G, TRANS, "1.0")
        h.standard.failWriteAfterN = 1 // the first write (ANIM) lands; every later write, including the compensation, fails
        h.standard.failWriteOnKey = mapOf(h.key(G, TRANS).id to BackendFailure(FailureKind.REJECTED, "no"))
        val op = finished(h.uiApply(h.putReq("two", RequestSource.UI, Triple(G, ANIM, "0.5"), Triple(G, TRANS, "0.5"))))
        assertEquals(OperationStatus.FAILED_PARTIAL, op.status)
        assertEquals("0.5", h.standard.get(G, ANIM), "the earlier write could not be put back and is reported, not hidden")
        assertEquals(RestoreStatus.RESTORE_FAILED, op.entries.first { it.key.name == ANIM }.restoreStatus)
    }

    @Test
    fun rollbackLeavesAValueThatSomethingElseChangedMeanwhile() {
        val h = Harness()
        h.standard.put(G, ANIM, "1.0")
        h.standard.put(G, TRANS, "1.0")
        h.standard.swallow = setOf(h.key(G, TRANS).id) // the second write does not stick, so the operation fails
        // Another app changes the first entry between our two writes.
        h.standard.beforeWrite = { k, _ -> if (k.name == TRANS) h.standard.put(G, ANIM, "3") }
        val op = finished(h.uiApply(h.putReq("two", RequestSource.UI, Triple(G, ANIM, "0.5"), Triple(G, TRANS, "0.5"))))
        assertEquals(OperationStatus.FAILED_PARTIAL, op.status)
        val anim = op.entries.first { it.key.name == ANIM }
        assertEquals(EntryOutcome.STORED_EFFECT_UNVERIFIED, anim.outcome)
        assertEquals(RestoreStatus.CONFLICT, anim.restoreStatus)
        assertEquals("3", h.standard.get(G, ANIM), "the other app's value is not overwritten")
        assertEquals(RestoreStatus.AUTO_ROLLED_BACK, op.entries.first { it.key.name == TRANS }.restoreStatus)
        assertEquals("1.0", h.standard.get(G, TRANS))
    }

    @Test
    fun nothingIsWrittenWhenTheCurrentValueIsUnreadable() {
        val h = Harness()
        h.standard.unreadable = setOf(h.key(G, ANIM).id)
        val r = h.uiApply(h.putReq("x", RequestSource.UI, Triple(G, ANIM, "0.5")))
        val plan = assertIs<ApplyResult.Rejected>(r).plan
        assertTrue(plan.blockers.any { it.code == IssueCode.UNREADABLE_CURRENT })
        assertTrue(h.standard.writeLog.isEmpty())
        assertTrue(h.journal.all().isEmpty())
    }

    @Test
    fun valuesThatCannotBeCapturedExactlyAreNeverMutated() {
        val h = Harness()
        h.standard.put(G, "dca_multiline", "a\nb")
        val r = h.uiApply(h.putReq("x", RequestSource.UI, Triple(G, "dca_multiline", "v"), manual = setOf(h.key(G, "dca_multiline").id)))
        assertTrue(assertIs<ApplyResult.Rejected>(r).plan.blockers.any { it.code == IssueCode.CANNOT_CAPTURE })
        assertTrue(h.standard.writeLog.isEmpty())
    }

    @Test
    fun noWriteHappensWithoutADurableRecord() {
        val h = Harness()
        h.standard.put(G, ANIM, "1.0")
        h.journal.failSaves = true
        val r = h.uiApply(h.putReq("x", RequestSource.UI, Triple(G, ANIM, "0.5")))
        assertTrue(assertIs<ApplyResult.Rejected>(r).plan.blockers.any { it.code == IssueCode.JOURNAL_UNWRITABLE })
        assertTrue(h.standard.writeLog.isEmpty())
        assertEquals("1.0", h.standard.get(G, ANIM))
    }

    @Test
    fun stalePreviewIsRefusedWhenThePhoneChangedAfterIt() {
        val h = Harness()
        h.standard.put(G, ANIM, "1.0")
        val req = h.putReq("x", RequestSource.UI, Triple(G, ANIM, "0.5"))
        val digest = h.engine.plan(req).digest
        h.standard.put(G, ANIM, "2") // changes between preview and tap
        val r = h.engine.apply(req, Confirmation.UserConfirmed(digest))
        assertIs<ApplyResult.PlanChanged>(r)
        assertEquals("2", h.standard.get(G, ANIM))
        assertTrue(h.standard.writeLog.isEmpty())
    }

    @Test
    fun uiApplyWithoutConfirmationIsRefused() {
        val h = Harness()
        h.standard.put(G, ANIM, "1.0")
        assertIs<ApplyResult.Rejected>(h.engine.apply(h.putReq("x", RequestSource.UI, Triple(G, ANIM, "0.5")), Confirmation.None))
        assertTrue(h.standard.writeLog.isEmpty())
    }

    @Test
    fun alreadySetValueIsANoopAndNotJournaled() {
        val h = Harness()
        h.standard.put(G, ANIM, "0.5")
        val r = h.uiApply(h.putReq("x", RequestSource.UI, Triple(G, ANIM, "0.5")))
        assertIs<ApplyResult.NothingToDo>(r)
        assertTrue(h.journal.all().isEmpty())
    }

    @Test
    fun baselineSurvivesIntermediateChangesAndTheRestoreOfAnIntermediateOne() {
        val h = Harness()
        h.standard.put(G, ANIM, "1.0")
        val op1 = finished(h.uiApply(h.putReq("one", RequestSource.UI, Triple(G, ANIM, "0.5"))))
        val op2 = finished(h.uiApply(h.putReq("two", RequestSource.UI, Triple(G, ANIM, "0"))))
        assertEquals(Snapshot.Present("1.0"), h.engine.baselines().values.single().snapshot, "baseline stays the value from before op1")
        assertTrue(!op2.entries.single().startsBaseline)
        // Restoring op1 now conflicts, because op2 changed the value after it.
        val r1 = RestoreRequest(RequestSource.UI, operationId = op1.id)
        val preview = h.engine.previewRestore(r1)
        assertEquals(RestoreState.CONFLICT, preview.items.single().state)
        // Restoring the newest first works, and keeps the baseline.
        val r2 = RestoreRequest(RequestSource.UI, operationId = op2.id)
        finished(h.engine.restore(r2, confirm(h, r2)))
        assertEquals("0.5", h.standard.get(G, ANIM))
        assertNotNull(h.engine.baselines().values.singleOrNull())
        // Restore to the original baseline.
        val rb = RestoreRequest(RequestSource.UI, baseline = true)
        finished(h.engine.restore(rb, confirm(h, rb)))
        assertEquals("1.0", h.standard.get(G, ANIM))
        assertTrue(h.engine.baselines().isEmpty())
        assertEquals(4, h.engine.history().size, "every operation is kept: two changes, a restore and the baseline restore")
    }

    @Test
    fun restoreShowsAConflictInsteadOfOverwritingAndForceOverwritesOnlyWhenChosen() {
        val h = Harness()
        h.standard.put(G, ANIM, "1.0")
        val op = finished(h.uiApply(h.putReq("one", RequestSource.UI, Triple(G, ANIM, "0.5"))))
        h.standard.put(G, ANIM, "7") // Android or another app changed it
        val req = RestoreRequest(RequestSource.UI, operationId = op.id)
        val preview = h.engine.previewRestore(req)
        assertEquals(RestoreState.CONFLICT, preview.items.single().state)
        assertTrue(!preview.actionable)
        assertIs<ApplyResult.Rejected>(h.engine.restore(req, Confirmation.UserConfirmed(preview.digest)))
        assertEquals("7", h.standard.get(G, ANIM))

        val force = req.copy(forceKeyIds = setOf(h.key(G, ANIM).id))
        val forcedPreview = h.engine.previewRestore(force)
        assertTrue(forcedPreview.actionable)
        val res = assertIs<ApplyResult.Finished>(h.engine.restore(force, Confirmation.UserConfirmed(forcedPreview.digest)))
        assertEquals(OperationStatus.COMPLETED, res.operation.status)
        assertEquals("1.0", h.standard.get(G, ANIM))
    }

    @Test
    fun bulkRestorePreviewCoversEveryEntryAndLeavesConflictsAlone() {
        val h = Harness()
        h.standard.put(G, ANIM, "1.0")
        h.standard.put(G, TRANS, "1.0")
        val op = finished(h.uiApply(h.putReq("both", RequestSource.UI, Triple(G, ANIM, "0.5"), Triple(G, TRANS, "0.5"))))
        h.standard.put(G, TRANS, "9")
        val req = RestoreRequest(RequestSource.UI, operationId = op.id)
        val preview = h.engine.previewRestore(req)
        assertEquals(setOf(RestoreState.READY, RestoreState.CONFLICT), preview.items.map { it.state }.toSet())
        val res = assertIs<ApplyResult.Finished>(h.engine.restore(req, Confirmation.UserConfirmed(preview.digest)))
        assertEquals("1.0", h.standard.get(G, ANIM))
        assertEquals("9", h.standard.get(G, TRANS))
        assertEquals(listOf(h.key(G, TRANS)), res.skipped.map { it.key })
        assertEquals(RestoreStatus.CONFLICT, h.engine.history().first { it.id == op.id }.entries.first { it.key.name == TRANS }.restoreStatus)
    }

    @Test
    fun privateDnsIsAppliedInADeliberateOrder() {
        val h = Harness()
        h.standard.put(G, SettingsCatalog.PRIVATE_DNS_MODE, "off")
        h.standard.put(G, SettingsCatalog.PRIVATE_DNS_SPECIFIER, "old.example.net")
        // Enabling hostname mode: the hostname goes in first.
        finished(h.uiApply(h.putReq("on", RequestSource.UI, Triple(G, SettingsCatalog.PRIVATE_DNS_MODE, "hostname"), Triple(G, SettingsCatalog.PRIVATE_DNS_SPECIFIER, "dns.example.net"))))
        assertEquals(listOf(SettingsCatalog.PRIVATE_DNS_SPECIFIER, SettingsCatalog.PRIVATE_DNS_MODE), h.standard.writeLog.map { it.substringAfter("global:0:").substringBefore("=") })
        h.standard.writeLog.clear()
        // Turning it off: the mode goes first and the hostname is left alone unless asked.
        finished(h.uiApply(h.putReq("off", RequestSource.UI, Triple(G, SettingsCatalog.PRIVATE_DNS_SPECIFIER, "other.example.net"), Triple(G, SettingsCatalog.PRIVATE_DNS_MODE, "off"))))
        assertEquals(listOf(SettingsCatalog.PRIVATE_DNS_MODE, SettingsCatalog.PRIVATE_DNS_SPECIFIER), h.standard.writeLog.map { it.substringAfter("global:0:").substringBefore("=") })
    }

    @Test
    fun hostnameModeNeedsAValidHostnameAndTheOldProviderIsRestoredExactly() {
        val h = Harness()
        h.standard.put(G, SettingsCatalog.PRIVATE_DNS_MODE, "opportunistic")
        h.standard.put(G, SettingsCatalog.PRIVATE_DNS_SPECIFIER, "keep.example.net")
        val bad = h.engine.plan(h.putReq("bad", RequestSource.UI, Triple(G, SettingsCatalog.PRIVATE_DNS_MODE, "hostname")))
        assertTrue(bad.executable, "switching the mode alone is fine while a valid provider hostname is already stored: ${bad.blockers}")
        val op = finished(h.uiApply(h.putReq("p", RequestSource.UI, Triple(G, SettingsCatalog.PRIVATE_DNS_MODE, "hostname"), Triple(G, SettingsCatalog.PRIVATE_DNS_SPECIFIER, "new.example.net"))))
        val r = RestoreRequest(RequestSource.UI, operationId = op.id)
        finished(h.engine.restore(r, confirm(h, r)))
        assertEquals("opportunistic", h.standard.get(G, SettingsCatalog.PRIVATE_DNS_MODE))
        assertEquals("keep.example.net", h.standard.get(G, SettingsCatalog.PRIVATE_DNS_SPECIFIER))
        val noHost = Harness()
        noHost.standard.put(G, SettingsCatalog.PRIVATE_DNS_MODE, "off")
        assertTrue(noHost.engine.plan(noHost.putReq("x", RequestSource.UI, Triple(G, SettingsCatalog.PRIVATE_DNS_MODE, "hostname"))).blockers.any { it.code == IssueCode.CROSS_FIELD })
    }

    @Test
    fun interruptedOperationIsRecoveredByReadingNeverByReplaying() {
        val h = Harness()
        h.standard.put(G, ANIM, "1.0")
        h.standard.put(G, TRANS, "1.0")
        h.standard.afterWrite = { k, _ -> if (k.name == ANIM) throw SimulatedCrash() } // dies right after the first write landed
        try {
            h.uiApply(h.putReq("two", RequestSource.UI, Triple(G, ANIM, "0.5"), Triple(G, TRANS, "0.5")))
        } catch (_: SimulatedCrash) {
        }
        assertEquals(OperationStatus.APPLYING, h.journal.all().single().status)
        h.standard.afterWrite = { _, _ -> }
        val writesBefore = h.standard.writeLog.size
        val recovered = h.engine.recoverInterrupted().single()
        assertEquals(OperationStatus.INTERRUPTED, recovered.status)
        assertEquals(EntryOutcome.UNCONFIRMED_AFTER_INTERRUPT, recovered.entries.first { it.key.name == ANIM }.outcome)
        assertEquals(EntryOutcome.NOT_ATTEMPTED, recovered.entries.first { it.key.name == TRANS }.outcome)
        assertEquals(writesBefore, h.standard.writeLog.size, "recovery wrote nothing")
        assertEquals("1.0", h.standard.get(G, TRANS))
        assertTrue(h.engine.recoverInterrupted().isEmpty(), "recovered once, not again")
        // The interrupted change can still be restored, with the normal conflict check.
        val r = RestoreRequest(RequestSource.UI, operationId = recovered.id)
        finished(h.engine.restore(r, confirm(h, r)))
        assertEquals("1.0", h.standard.get(G, ANIM))
    }

    @Test
    fun recoveryWaitsWhileTheBackendIsUnavailable() {
        val h = Harness()
        h.standard.put(G, ANIM, "1.0")
        h.standard.afterWrite = { _, _ -> throw SimulatedCrash() }
        try { h.uiApply(h.putReq("x", RequestSource.UI, Triple(G, ANIM, "0.5"))) } catch (_: SimulatedCrash) { }
        h.standard.state = BackendState.UNAVAILABLE
        assertTrue(h.engine.recoverInterrupted().isEmpty())
        assertEquals(OperationStatus.APPLYING, h.journal.all().single().status)
    }

    @Test
    fun aSecondOperationWhileOneIsRunningIsBusyNotInterleaved() {
        val h = Harness(lockWaitMs = 150)
        h.standard.put(G, ANIM, "1.0")
        h.standard.put(G, TRANS, "1.0")
        val inWrite = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        h.standard.beforeWrite = { k, _ -> if (k.name == ANIM) { inWrite.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) } }
        var first: ApplyResult? = null
        val t = Thread { first = h.uiApply(h.putReq("one", RequestSource.UI, Triple(G, ANIM, "0.5"))) }
        t.start()
        assertTrue(inWrite.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val second = h.engine.apply(h.putReq("two", RequestSource.UI, Triple(G, TRANS, "0.5")), Confirmation.UserConfirmed("whatever"))
        assertEquals(ApplyResult.Busy, second)
        release.countDown()
        t.join(5_000)
        assertEquals(OperationStatus.COMPLETED, assertIs<ApplyResult.Finished>(first).operation.status)
        assertEquals("1.0", h.standard.get(G, TRANS))
    }

    @Test
    fun auditRecordsStartAndFinishAndRefusals() {
        val h = Harness()
        h.standard.put(G, ANIM, "1.0")
        h.uiApply(h.putReq("x", RequestSource.UI, Triple(G, ANIM, "0.5")))
        h.engine.apply(h.putReq("bad", RequestSource.UI, Triple(G, ANIM, "99")), Confirmation.None)
        val types = h.audit.all().map { it.type }
        assertTrue(ai.droidcommand.security.AuditEventType.SETTINGS_CHANGED in types)
        assertTrue(ai.droidcommand.security.AuditEventType.SETTINGS_REFUSED in types)
    }
}
