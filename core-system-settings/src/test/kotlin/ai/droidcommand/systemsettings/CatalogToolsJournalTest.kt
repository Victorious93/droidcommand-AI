@file:Suppress("ktlint:standard:property-naming")

package ai.droidcommand.systemsettings

import ai.droidcommand.agent.ToolResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogTest {
    private fun spec(name: String) = SettingsCatalog.specs.first { it.name == name }

    @Test
    fun everyCatalogEntryNamesItsSourceAndAnEffectNote() {
        for (s in SettingsCatalog.specs) {
            assertTrue(s.sources.contains("Settings."), s.name)
            assertTrue(s.effectNote.isNotBlank() && s.description.isNotBlank(), s.name)
        }
        assertEquals(SettingsCatalog.specs.size, SettingsCatalog.specs.map { it.id }.toSet().size)
    }

    @Test
    fun refreshRateKeysAreAbsentBeforeAndroid12AndNeedMoreThanOneDisplayRate() {
        assertNotNull(spec("peak_refresh_rate").supportProblem(testDevice(sdk = 30)))
        assertNull(spec("peak_refresh_rate").supportProblem(testDevice(sdk = 31)))
        assertTrue(spec("min_refresh_rate").supportProblem(testDevice(sdk = 34, rates = listOf(60f)))!!.contains("single refresh rate"))
        assertTrue(spec("peak_refresh_rate").supportProblem(testDevice(sdk = 34, rates = emptyList()))!!.contains("single"))
    }

    @Test
    fun refreshChoicesComeFromTheDisplayNotFromAGuess() {
        val d = testDevice(rates = listOf(60f, 90f, 120f))
        assertEquals(listOf("60", "90", "120"), spec("peak_refresh_rate").presets(d).map { it.value })
        assertNull(spec("peak_refresh_rate").type.check("90", d))
        assertNotNull(spec("peak_refresh_rate").type.check("144", d))
        assertTrue(spec("peak_refresh_rate").type.equivalent("90", "90.0"))
    }

    @Test
    fun chargingBitsFollowTheAndroidReleaseAndOddCombinationsAreRejected() {
        val t = spec("stay_on_while_plugged_in").type
        val a14 = testDevice(sdk = 34)
        val a12 = testDevice(sdk = 31)
        assertNull(t.check("7", a12))
        assertNotNull(t.check("8", a12), "dock bit was not verified before Android 14")
        assertNull(t.check("8", a14))
        assertNull(t.check("15", a14))
        assertNotNull(t.check("16", a14))
        assertNotNull(t.check("-1", a14))
        assertNull(t.check("0", a12))
    }

    @Test
    fun boundsAreLabelledAsAndroidDocumentedOrAppSafety() {
        assertEquals(BoundSource.ENGINE_SAFETY, (spec("window_animation_scale").type as ValueType.FloatRange).source)
        assertEquals(BoundSource.ANDROID_DOCUMENTED, (spec("screen_brightness").type as ValueType.IntRange).source)
        val dev = testDevice()
        assertNull(spec("screen_brightness").type.check("1", dev))
        assertNotNull(spec("screen_brightness").type.check("0", dev), "0 is allowed by older docs but not by main; the engine keeps the common range")
        assertNotNull(spec("screen_brightness").type.check("256", dev))
        assertNull(spec("screen_off_timeout").type.check("15000", dev))
        assertNotNull(spec("screen_off_timeout").type.check("100", dev))
        assertNotNull(spec("user_rotation").type.check("4", dev))
    }

    @Test
    fun hostnameRulesAndAnimationValueShapes() {
        assertNull(ValueType.Hostname.hostnameProblem("dns.example.net"))
        for (bad in listOf("", "localhost", "-a.example.com", "a..b", "a b.example.com", "x".repeat(260) + ".com", "dns.exa_mple.net")) assertNotNull(ValueType.Hostname.hostnameProblem(bad), bad)
        val f = spec("window_animation_scale").type
        for (ok in listOf("0", "0.5", "1", "10")) assertNull(f.check(ok, testDevice()), ok)
        for (bad in listOf("", "abc", "-0.5", "10.5", "1e2", "NaN", "Infinity", "0,5")) assertNotNull(f.check(bad, testDevice()), bad)
        assertTrue(f.equivalent("1", "1.0") && !f.equivalent("1", "1.5"))
    }

    @Test
    fun highImpactListCoversIdentityCredentialsAccessibilityAndInput() {
        for ((t, n) in listOf(
            SEC to "enabled_accessibility_services", SEC to "accessibility_enabled", SEC to "default_input_method", SEC to "enabled_input_methods",
            SEC to "android_id", G to "device_provisioned", SEC to "user_setup_complete", SEC to "lockscreen.password_type", G to "adb_enabled", SEC to "enabled_notification_listeners",
        )) assertEquals(RiskClass.HIGH_IMPACT, SettingsRisk.classify(t, n), n)
        assertEquals(RiskClass.CURATED, SettingsRisk.classify(G, "window_animation_scale"))
        assertEquals(RiskClass.UNREVIEWED, SettingsRisk.classify(G, "some_oem_key"))
    }

    @Test
    fun noSpeculativeTweaksAreInTheCatalog() {
        val text = SettingsCatalog.specs.joinToString(" ") { it.name + it.title + it.description }.lowercase()
        for (banned in listOf("fps", "overclock", "thermal", "ram", "boost", "turbo", "gpu_")) assertFalse(text.contains(banned), banned)
    }
}

class SetupInstructionsTest {
    @Test
    fun usesTheRealApplicationIdAndAndroidVersion() {
        val steps = AccessSetupInstructions.steps("ai.droidcommand.app", 34, "14")
        val all = steps.joinToString("\n") { it.title + it.body + (it.command ?: "") }
        assertTrue(all.contains("adb shell pm grant ai.droidcommand.app android.permission.WRITE_SECURE_SETTINGS"))
        assertTrue(all.contains("adb shell appops set ai.droidcommand.app WRITE_SETTINGS allow"))
        assertTrue(all.contains("Android 14 (API 34)"))
        assertTrue(all.contains("Root is off"))
        assertFalse(all.contains("com.example"))
    }
}

class ToolsTest {
    private val ANIM = SettingsCatalog.WINDOW_ANIMATION_SCALE

    private class Rig {
        val h = Harness().also { it.standard.put(G, SettingsCatalog.WINDOW_ANIMATION_SCALE, "1.0") }
        var enabled = false
        val svc = ProfileService(h.engine) { h.device }
        val read = SystemSettingsReadTool(h.engine, { StarterProfiles.all() }, svc, { h.device }) { enabled }
        val change = SystemSettingsChangeTool(h.engine, { StarterProfiles.all() }, svc, { h.device }) { enabled }
        fun sync() {
            h.config = AccessConfig(assistantEnabled = enabled)
        }
    }

    @Test
    fun bothToolsRefuseWhileTheGateIsOff() {
        val r = Rig()
        assertIs<ToolResult.Failure>(r.read.execute(mapOf("action" to "curated")))
        assertIs<ToolResult.Failure>(r.change.execute(mapOf("action" to "set", "table" to "global", "key" to ANIM, "value" to "0.5")))
        assertTrue(r.h.standard.writeLog.isEmpty() && r.h.approvals.isEmpty())
    }

    @Test
    fun readSharesOnlyReviewedSettingsAndNeverATableDump() {
        val r = Rig()
        r.enabled = true
        r.sync()
        r.h.standard.put(SEC, "android_id", "deadbeef")
        r.h.standard.put(G, "wifi_secret_thing", "hunter2")
        val curated = (r.read.execute(mapOf("action" to "curated")) as ToolResult.Success).output
        assertTrue(curated.contains("window_animation_scale") && curated.contains("1.0"))
        assertFalse(curated.contains("deadbeef") || curated.contains("hunter2"))
        assertIs<ToolResult.Failure>(r.read.execute(mapOf("action" to "read", "table" to "secure", "key" to "android_id")))
        assertIs<ToolResult.Failure>(r.read.execute(mapOf("action" to "read", "table" to "global", "key" to "wifi_secret_thing")))
        assertIs<ToolResult.Failure>(r.read.execute(mapOf("action" to "list", "table" to "global")))
        assertEquals("global/window_animation_scale: 1.0", (r.read.execute(mapOf("action" to "read", "table" to "global", "key" to ANIM)) as ToolResult.Success).output)
    }

    @Test
    fun historyRedactsValuesOfSettingsThatAreNotReviewed() {
        val r = Rig()
        r.enabled = true
        r.sync()
        r.h.standard.put(G, "dca_secret", "supersecret")
        r.h.uiApply(r.h.putReq("manual", RequestSource.UI, Triple(G, "dca_secret", "newsecret"), manual = setOf(r.h.key(G, "dca_secret").id)))
        r.h.uiApply(r.h.putReq("quick", RequestSource.UI, Triple(G, ANIM, "0.5")))
        val out = (r.read.execute(mapOf("action" to "history")) as ToolResult.Success).output
        assertFalse(out.contains("supersecret") || out.contains("newsecret") || out.contains("dca_secret"))
        assertTrue(out.contains("1.0 -> 0.5"))
    }

    @Test
    fun previewChangesNothing() {
        val r = Rig()
        r.enabled = true
        r.sync()
        val out = (r.read.execute(mapOf("action" to "preview_set", "table" to "global", "key" to ANIM, "value" to "0.5")) as ToolResult.Success).output
        assertTrue(out.contains("nothing has been changed") && out.contains("1.0 → 0.5"))
        val bad = (r.read.execute(mapOf("action" to "preview_set", "table" to "secure", "key" to "default_input_method", "value" to "a/b")) as ToolResult.Success).output
        assertTrue(bad.contains("Would NOT run") && bad.contains("high-impact"))
        val prof = (r.read.execute(mapOf("action" to "preview_profile", "profile" to "builtin-quick-ui")) as ToolResult.Success).output
        assertTrue(prof.contains("window_animation_scale"))
        assertTrue(r.h.standard.writeLog.isEmpty() && r.h.journal.all().isEmpty() && r.h.approvals.isEmpty())
    }

    @Test
    fun changeAsksEveryTimeAndRechecksTheGateAtExecution() {
        val r = Rig()
        r.enabled = true
        r.sync()
        val input = mapOf("action" to "set", "table" to "global", "key" to ANIM, "value" to "0.5")
        r.h.approve = false
        assertTrue((r.change.execute(input) as ToolResult.Failure).reason.contains("said no"))
        assertEquals("1.0", r.h.standard.get(G, ANIM))
        r.h.approve = true
        assertIs<ToolResult.Success>(r.change.execute(input))
        assertEquals("0.5", r.h.standard.get(G, ANIM))
        assertEquals(2, r.h.approvals.size)
        r.enabled = false
        r.sync()
        assertIs<ToolResult.Failure>(r.change.execute(input.plus("value" to "0")))
        assertEquals("0.5", r.h.standard.get(G, ANIM))
        assertEquals(2, r.h.approvals.size, "no new prompt is shown when the gate is off")
    }

    @Test
    fun changeCannotReachUnreviewedOrHighImpactSettingsEvenWhenApproved() {
        val r = Rig()
        r.enabled = true
        r.sync()
        val u = r.change.execute(mapOf("action" to "set", "table" to "global", "key" to "dca_other", "value" to "1"))
        assertTrue((u as ToolResult.Failure).reason.contains("not a reviewed"))
        val hi = r.change.execute(mapOf("action" to "set", "table" to "secure", "key" to "enabled_accessibility_services", "value" to "x/y"))
        assertTrue((hi as ToolResult.Failure).reason.contains("high-impact"))
        assertTrue(r.h.approvals.isEmpty() && r.h.standard.writeLog.isEmpty(), "refused before the owner is even asked")
    }

    @Test
    fun assistantCanApplyAProfileAndThenRestoreItThroughTheSameEngine() {
        val r = Rig()
        listOf(SettingsCatalog.TRANSITION_ANIMATION_SCALE, SettingsCatalog.ANIMATOR_DURATION_SCALE).forEach { r.h.standard.put(G, it, "1.0") }
        r.enabled = true
        r.sync()
        assertIs<ToolResult.Success>(r.change.execute(mapOf("action" to "apply_profile", "profile" to "builtin-quick-ui")))
        assertEquals("0.5", r.h.standard.get(G, ANIM))
        val opId = r.h.engine.history().first().id
        assertIs<ToolResult.Success>(r.change.execute(mapOf("action" to "restore", "operation" to opId)))
        assertEquals("1.0", r.h.standard.get(G, ANIM))
        assertEquals(2, r.h.approvals.size)
    }
}

class JournalTest {
    private fun op(id: String, vararg e: JournalEntry, createdAt: Long = 1, status: OperationStatus = OperationStatus.COMPLETED) =
        Operation(id, OperationKind.APPLY, RequestSource.UI, "l", createdAt, AccessKind.STANDARD, "fp", status, e.toList())

    @Test
    fun fileJournalRoundTripsEveryFieldIncludingAbsentAndEmpty() {
        val dir = Files.createTempDirectory("journal")
        val j = FileOperationJournal(dir)
        val k = SettingKey.of(G, "window_animation_scale", 0)
        val entry = JournalEntry(
            k, Snapshot.Absent, Proposed.Put(""), EntryPhase.DONE, EntryOutcome.EFFECT_VERIFIED, "n", Snapshot.Present(""), "fx",
            RestoreStatus.CONFLICT, "rn", 9L, startsBaseline = true, clearsBaseline = false, restoresOperationId = "op-x",
        )
        val o = op("op-00000001", entry, JournalEntry(SettingKey.of(S, "k", 10), Snapshot.Present("a\"b\\c"), Proposed.Delete)).copy(profileId = "p", finishedAt = 5, note = "x")
        j.save(o)
        assertEquals(o, FileOperationJournal(dir).load("op-00000001"))
        assertEquals(listOf(o), FileOperationJournal(dir).all())
    }

    @Test
    fun aCorruptFileIsReportedNotSilentlyDropped() {
        val dir = Files.createTempDirectory("journal")
        val j = FileOperationJournal(dir)
        j.save(op("op-00000001"))
        Files.writeString(dir.resolve("op-garbage.json"), "{not json")
        assertEquals(1, j.all().size)
        assertEquals(listOf("op-garbage.json"), j.unreadable())
        val h = Harness(journal = InMemoryOperationJournal())
        assertTrue(h.engine.plan(h.putReq("x", RequestSource.UI, Triple(G, SettingsCatalog.WINDOW_ANIMATION_SCALE, "0.5"))).issues.none { it.code == IssueCode.JOURNAL_PROBLEM })
    }

    @Test
    fun noTempFilesAreLeftAndSavesReplaceAtomically() {
        val dir = Files.createTempDirectory("journal")
        val j = FileOperationJournal(dir)
        repeat(5) { j.save(op("op-0000000$it", createdAt = it.toLong())) }
        j.save(op("op-00000001", createdAt = 1, status = OperationStatus.FAILED_PARTIAL))
        assertTrue(Files.list(dir).use { s -> s.noneMatch { it.fileName.toString().startsWith(".tmp") } })
        assertEquals(OperationStatus.FAILED_PARTIAL, j.load("op-00000001")!!.status)
        assertEquals(5, j.all().size)
        assertTrue(kotlin.runCatching { j.load("../../x") }.isFailure)
    }

    @Test
    fun baselineIsTheFirstCapturedStateAndLastKnownFollowsTheNewestWrite() {
        val k = SettingKey.of(G, "window_animation_scale", 0)
        fun e(prev: String, new: String, base: Boolean) = JournalEntry(k, Snapshot.Present(prev), Proposed.Put(new), EntryPhase.DONE, EntryOutcome.STORED_EFFECT_UNVERIFIED, observedAfterWrite = Snapshot.Present(new), startsBaseline = base)
        val ops = listOf(op("op-00000001", e("1.0", "0.5", true), createdAt = 1), op("op-00000002", e("0.5", "0", false), createdAt = 2))
        assertEquals(Snapshot.Present("1.0"), Baselines.compute(ops).getValue(k.id).snapshot)
        assertEquals(Snapshot.Present("0"), Baselines.lastKnown(ops, k.id))
        assertNull(Baselines.lastKnown(ops, "global:0:other"))
    }
}
