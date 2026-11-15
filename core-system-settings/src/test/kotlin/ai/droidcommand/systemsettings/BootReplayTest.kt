package ai.droidcommand.systemsettings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BootReplayTest {
    private class Rig(val h: Harness = Harness()) {
        val profile = StarterProfiles.quickUi()
        val store = InMemoryReplayStateStore()
        val coord = BootReplayCoordinator(h.engine, ProfileService(h.engine) { h.device }, { if (it == profile.id) profile else null }, store, { h.device })

        init {
            profile.entries.forEach { h.standard.put(G, it.name, "1.0") }
            h.config = AccessConfig() // standard + shizuku allowed, root not
        }
    }

    @Test
    fun offByDefaultNothingRuns() {
        val r = Rig()
        assertTrue(r.coord.runAfterBoot("boot-1").isEmpty())
        assertTrue(r.h.standard.writeLog.isEmpty())
    }

    @Test
    fun appliesOnBootAndKeepsTheOriginalBaseline() {
        val r = Rig()
        r.coord.enable(r.profile, false)
        val run = r.coord.runAfterBoot("boot-1").single()
        assertEquals(ReplayStatus.APPLIED, run.status)
        assertEquals("0.5", r.h.standard.get(G, SettingsCatalog.WINDOW_ANIMATION_SCALE))
        assertEquals(RequestSource.BOOT_REPLAY, r.h.engine.history().single().source)
        val baselineBefore = r.h.engine.baselines().mapValues { it.value.snapshot }
        // Android restored the old value at reboot: the next boot reapplies without moving the baseline.
        r.profile.entries.forEach { r.h.standard.put(G, it.name, "1.0") }
        assertEquals(ReplayStatus.APPLIED, r.coord.runAfterBoot("boot-2").single().status)
        assertEquals(baselineBefore, r.h.engine.baselines().mapValues { it.value.snapshot })
        assertTrue(r.h.engine.baselines().values.all { it.snapshot == Snapshot.Present("1.0") })
    }

    @Test
    fun upToDateWritesNothing() {
        val r = Rig()
        r.profile.entries.forEach { r.h.standard.put(G, it.name, "0.5") }
        r.coord.enable(r.profile, false)
        assertEquals(ReplayStatus.UP_TO_DATE, r.coord.runAfterBoot("boot-1").single().status)
        assertTrue(r.h.standard.writeLog.isEmpty() && r.h.journal.all().isEmpty())
    }

    @Test
    fun unavailableAuthorizationShowsPendingAndNeverElevatesOrLoops() {
        val r = Rig(Harness(shizuku = FakeBackend(AccessKind.SHIZUKU), root = FakeBackend(AccessKind.ROOT)))
        r.h.config = AccessConfig() // root not allowed
        r.h.standard.writable = emptySet() // WRITE_SECURE_SETTINGS revoked
        r.h.shizuku.state = BackendState.UNAVAILABLE // Shizuku not running after boot
        r.h.root.put(G, SettingsCatalog.WINDOW_ANIMATION_SCALE, "1.0")
        r.coord.enable(r.profile, false)
        val first = r.coord.runAfterBoot("boot-1").single()
        assertEquals(ReplayStatus.PENDING_BACKEND_UNAVAILABLE, first.status)
        assertTrue(first.message.contains("Waiting for access"))
        assertTrue(r.h.root.writeLog.isEmpty() && r.h.shizuku.writeLog.isEmpty() && r.h.standard.writeLog.isEmpty(), "no silent elevation, no regrant, no writes")
        // Same boot again: bounded, not an endless retry, and pending does not count as a failure.
        assertEquals(ReplayStatus.PENDING_BACKEND_UNAVAILABLE, r.coord.runAfterBoot("boot-1").single().status)
        val third = r.coord.runAfterBoot("boot-1").single()
        assertTrue(third.message.contains("Already tried"))
        assertEquals(0, r.store.get(r.profile.id)!!.consecutiveFailures)
    }

    @Test
    fun repeatedFailuresSuspendUntilReviewed() {
        val r = Rig()
        r.h.standard.failWriteOnKey = mapOf(r.h.key(G, SettingsCatalog.WINDOW_ANIMATION_SCALE).id to BackendFailure(FailureKind.REJECTED, "no"))
        r.coord.enable(r.profile, false)
        assertEquals(ReplayStatus.FAILED, r.coord.runAfterBoot("b1").single().status)
        assertEquals(ReplayStatus.FAILED, r.coord.runAfterBoot("b2").single().status)
        val third = r.coord.runAfterBoot("b3").single()
        assertEquals(ReplayStatus.SUSPENDED_REPEATED_FAILURES, third.status)
        val writes = r.h.standard.writeLog.size
        assertTrue(r.coord.runAfterBoot("b4").single().message.contains("Suspended"))
        assertEquals(writes, r.h.standard.writeLog.size, "a suspended profile is not tried")
        // Reviewing and enabling again re-arms it.
        r.h.standard.failWriteOnKey = emptyMap()
        r.coord.enable(r.profile, false)
        assertEquals(ReplayStatus.APPLIED, r.coord.runAfterBoot("b5").single().status)
    }

    @Test
    fun changedFirmwareOrAndroidVersionSuspendsReplay() {
        val r = Rig()
        r.coord.enable(r.profile, false)
        r.h.device = testDevice(fingerprint = "fp-after-ota")
        assertEquals(ReplayStatus.SUSPENDED_CONTEXT_CHANGED, r.coord.runAfterBoot("b1").single().status)
        assertTrue(r.h.standard.writeLog.isEmpty())
        val r2 = Rig()
        r2.coord.enable(r2.profile, false)
        r2.h.device = testDevice(sdk = 35)
        assertEquals(ReplayStatus.SUSPENDED_CONTEXT_CHANGED, r2.coord.runAfterBoot("b1").single().status)
    }

    @Test
    fun editedProfileSuspendsAndEntriesThatNoLongerApplySuspend() {
        val h = Harness()
        var p = StarterProfiles.quickUi()
        val store = InMemoryReplayStateStore()
        val coord = BootReplayCoordinator(h.engine, ProfileService(h.engine) { h.device }, { p }, store, { h.device })
        p.entries.forEach { h.standard.put(G, it.name, "1.0") }
        coord.enable(p, false)
        p = p.copy(entries = p.entries.map { it.copy(value = "0") })
        assertEquals(ReplayStatus.SUSPENDED_PROFILE_CHANGED, coord.runAfterBoot("b1").single().status)

        val h2 = Harness(device = testDevice(rates = listOf(60f, 90f)))
        val bp = StarterProfiles.batteryConsciousDisplay()
        val coord2 = BootReplayCoordinator(h2.engine, ProfileService(h2.engine) { h2.device }, { bp }, InMemoryReplayStateStore(), { h2.device })
        h2.standard.put(S, SettingsCatalog.PEAK_REFRESH_RATE, "90")
        coord2.enable(bp, false)
        h2.device = h2.device.copy(supportedRefreshRatesHz = listOf(90f, 120f)) // 60 Hz is gone, same firmware
        assertEquals(ReplayStatus.SUSPENDED_CONTEXT_CHANGED, coord2.runAfterBoot("b1").single().status)
    }

    @Test
    fun aMissingProfileSuspendsAndLockedStorageStateSurvivesRoundTrip() {
        val h = Harness()
        val store = InMemoryReplayStateStore()
        val coord = BootReplayCoordinator(h.engine, ProfileService(h.engine) { h.device }, { null }, store, { h.device })
        coord.enable(StarterProfiles.quickUi(), false)
        assertEquals(ReplayStatus.SUSPENDED_PROFILE_MISSING, coord.runAfterBoot("b1").single().status)

        val dir = java.nio.file.Files.createTempDirectory("replay")
        val fs = FileReplayStateStore(dir.resolve("replay.json"))
        val rec = ReplayRecord("p-1", true, true, "fp", 34, "hash", ReplayStatus.PENDING_BACKEND_UNAVAILABLE, 2, 1, "boot", 5L, "waiting", 9L)
        fs.save(rec)
        assertEquals(rec, FileReplayStateStore(dir.resolve("replay.json")).get("p-1"))
        fs.remove("p-1")
        assertTrue(fs.all().isEmpty())
    }

    @Test
    fun requirementTextExplainsTheBackendAndWhatHappensWhenItIsMissing() {
        val r = Rig()
        assertTrue(r.coord.requirement(r.profile, false).contains("Standard permissions"))
        r.h.standard.state = BackendState.UNAVAILABLE
        assertTrue(r.coord.requirement(r.profile, false).contains("pending at boot"))
    }
}
