package ai.droidcommand.systemsettings

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProfileCodecTest {
    private fun file(entries: String, extra: String = "") =
        """{"format":"victorious-system-settings-profile","version":1,"name":"P","description":"d"$extra,"entries":[$entries]}"""

    private val good = """{"table":"global","key":"window_animation_scale","value":"0.5"}"""

    private fun errors(text: String): List<String> = assertIs<ProfileImport.Invalid>(ProfileCodec.decode(text, "id-12345678")).errors

    @Test
    fun roundTripKeepsEntriesAndNeverApplies() {
        val h = Harness()
        h.standard.put(G, SettingsCatalog.WINDOW_ANIMATION_SCALE, "1.0")
        val p = StarterProfiles.quickUi()
        val text = ProfileCodec.encode(p)
        val back = assertIs<ProfileImport.Valid>(ProfileCodec.decode(text, "imported-0001")).profile
        assertEquals(p.entries, back.entries)
        assertEquals("imported-0001", back.id)
        assertFalse(back.builtIn)
        assertTrue(h.standard.writeLog.isEmpty() && h.journal.all().isEmpty(), "decoding never touches the phone")
        assertEquals("1.0", h.standard.get(G, SettingsCatalog.WINDOW_ANIMATION_SCALE))
    }

    @Test
    fun importAlwaysGetsAFreshIdAndAFileCannotChooseItsOwn() {
        val v = assertIs<ProfileImport.Valid>(ProfileCodec.decode(file(good), "fresh-0001"))
        assertEquals("fresh-0001", v.profile.id)
        assertTrue(errors(file(good, ""","id":"builtin-quick-ui"""")).any { it.contains("Unknown field") })
    }

    @Test
    fun rejectsBadSchemaSizesAndUnknownFields() {
        assertTrue(errors("not json").single().contains("JSON"))
        assertTrue(errors("[]").single().contains("object"))
        assertTrue(errors(file(good).replace("\"version\":1", "\"version\":2")).any { it.contains("version") })
        assertTrue(errors(file(good).replace("victorious-system-settings-profile", "other")).any { it.contains("not a") })
        assertTrue(errors(file(good, ""","script":"rm -rf /"""")).any { it.contains("Unknown field") })
        assertTrue(errors(file("""{"table":"global","key":"window_animation_scale","value":"0.5","run":"x"}""")).any { it.contains("unknown field") })
        assertTrue(errors(file("")).any { it.contains("at least one") })
        val many = (1..33).joinToString(",") { """{"table":"global","key":"k$it","value":"1"}""" }
        assertTrue(errors(file(many)).any { it.contains("at most") })
        assertTrue(errors("{" + "\"x\":\"" + "a".repeat(70_000) + "\"}").single().contains("larger"))
    }

    @Test
    fun rejectsDuplicateEntriesBadTablesBadScopesAndBadTypes() {
        assertTrue(errors(file("$good,$good")).any { it.contains("more than once") })
        assertTrue(errors(file("""{"table":"nope","key":"window_animation_scale","value":"1"}""")).any { it.contains("unknown table") })
        assertTrue(errors(file("""{"table":"global","key":"window_animation_scale","value":"0.5","scope":"user_10"}""")).any { it.contains("scope") })
        assertTrue(errors(file("""{"table":"system","key":"screen_off_timeout","value":"30000","scope":"device"}""")).any { it.contains("scope") })
        assertTrue(errors(file("""{"table":"global","key":"window_animation_scale","value":"abc"}""")).any { it.contains("plain number") })
        assertTrue(errors(file("""{"table":"global","key":"window_animation_scale","value":"99"}""")).any { it.contains("from 0") })
        assertTrue(errors(file("""{"table":"global","key":"private_dns_mode","value":"fast"}""")).any { it.contains("Choose") })
    }

    @Test
    fun rejectsShellFragmentsAndExecutableLookingContent() {
        for (v in listOf("0.5;reboot", "\$(id)", "`id`", "0.5 && id", "a|b", "0.5\n1", "0.5 0.6")) {
            assertTrue(errors(file("""{"table":"global","key":"window_animation_scale","value":${kotlinx.serialization.json.JsonPrimitive(v)}}""")).isNotEmpty(), "accepted $v")
        }
        assertTrue(errors(file(good).replace("\"name\":\"P\"", "\"name\":\"P; rm -rf /\"")).any { it.contains("name") })
        assertTrue(errors(file(good).replace("\"description\":\"d\"", "\"description\":\"\$(id)\"")).any { it.contains("description") })
        assertTrue(errors(file(good, ""","origin":{"sdk":34,"model":"`id`"}""")).any { it.contains("origin") })
    }

    @Test
    fun highImpactAndUnreviewedEntriesCannotBeInAProfile() {
        assertTrue(errors(file("""{"table":"secure","key":"enabled_accessibility_services","value":"a.b/c.D"}""")).any { it.contains("high-impact") })
        assertTrue(errors(file("""{"table":"secure","key":"default_input_method","value":"a.b/c.D"}""")).any { it.contains("high-impact") })
        assertTrue(errors(file("""{"table":"global","key":"device_provisioned","value":"1"}""")).any { it.contains("high-impact") })
        assertTrue(errors(file("""{"table":"secure","key":"android_id","value":"abc"}""")).any { it.contains("high-impact") })
        assertTrue(errors(file("""{"table":"global","key":"some_unknown_key","value":"1"}""")).any { it.contains("not a reviewed") })
    }

    @Test
    fun aProfileFromAnotherPhoneIsKeptButCheckedAgainstThisOne() {
        val text = file(
            """{"table":"system","key":"peak_refresh_rate","value":"144"},{"table":"global","key":"window_animation_scale","value":"0.5"}""",
            ""","origin":{"sdk":35,"release":"15","manufacturer":"Other","model":"Dev","fingerprint":"other/fp"}""",
        )
        val v = assertIs<ProfileImport.Valid>(ProfileCodec.decode(text, "imp-12345678"))
        assertTrue(v.warnings.single().contains("checked again"))
        val h = Harness(device = testDevice(sdk = 34, rates = listOf(60f, 90f))) // this phone has no 144 Hz
        h.standard.put(G, SettingsCatalog.WINDOW_ANIMATION_SCALE, "1.0")
        h.standard.put(S, SettingsCatalog.PEAK_REFRESH_RATE, "90")
        val pp = ProfileService(h.engine) { h.device }.plan(v.profile, RequestSource.UI)
        assertEquals(listOf("peak_refresh_rate"), pp.unsupported.map { it.entry.name })
        assertTrue(pp.unsupported.single().reason.contains("60"))
        assertEquals(listOf("window_animation_scale"), pp.plan.entries.map { it.change.key.name })
    }
}

class ProfileServiceTest {
    private fun svc(h: Harness) = ProfileService(h.engine) { h.device }

    @Test
    fun starterProfilesUseOnlyReviewedSettings() {
        for (p in StarterProfiles.all()) {
            for (e in p.entries) {
                assertEquals(RiskClass.CURATED, SettingsRisk.classify(e.table, e.name), "${p.name}/${e.name}")
                assertTrue(SettingsCatalog.find(e.table, e.name) != null)
            }
            assertTrue(p.tradeoffs.isNotBlank() && p.builtIn)
            assertTrue(ProfileCodec.decode(ProfileCodec.encode(p), "x-12345678") is ProfileImport.Valid, "${p.name} must survive its own validator")
        }
    }

    @Test
    fun previewShowsExactChangesAndUnsupportedEntriesAndSavingNeverApplies() {
        val h = Harness(device = testDevice(sdk = 28, rates = listOf(60f)))
        h.standard.put(S, SettingsCatalog.SCREEN_OFF_TIMEOUT, "60000")
        h.standard.put(S, SettingsCatalog.SCREEN_BRIGHTNESS_MODE, "0")
        val pp = svc(h).plan(StarterProfiles.batteryConsciousDisplay(), RequestSource.UI)
        assertEquals(setOf("peak_refresh_rate"), pp.unsupported.map { it.entry.name }.toSet(), "API 28 and a single-rate display cannot take the refresh entry")
        assertEquals(listOf("screen_off_timeout", "screen_brightness_mode"), pp.plan.entries.map { it.change.key.name })
        assertEquals(listOf("60000 → 30000", "0 → 1").map { it.replace(" → ", " → ") }, pp.plan.entries.map { "${it.current?.describe()} → ${it.change.proposed.describe()}" })
        assertTrue(h.standard.writeLog.isEmpty())
    }

    @Test
    fun optionalEntriesAreOnlyAppliedWhenIncluded() {
        val h = Harness()
        h.standard.put(S, SettingsCatalog.SCREEN_OFF_TIMEOUT, "30000")
        h.standard.put(G, SettingsCatalog.STAY_ON_WHILE_PLUGGED_IN, "0")
        val s = svc(h)
        val without = s.plan(StarterProfiles.appTesting(), RequestSource.UI, includeOptional = false)
        assertEquals(listOf("stay_on_while_plugged_in"), without.leftOut.map { it.name })
        assertEquals(1, without.plan.entries.size)
        val with = s.plan(StarterProfiles.appTesting(), RequestSource.UI, includeOptional = true)
        assertEquals(2, with.plan.entries.size)
        val r = s.apply(StarterProfiles.appTesting(), RequestSource.UI, true, Confirmation.UserConfirmed(with.plan.digest))
        assertEquals(OperationStatus.COMPLETED, assertIs<ApplyResult.Finished>(r).operation.status)
        assertEquals("600000", h.standard.get(S, SettingsCatalog.SCREEN_OFF_TIMEOUT))
        assertEquals("7", h.standard.get(G, SettingsCatalog.STAY_ON_WHILE_PLUGGED_IN))
    }

    @Test
    fun partialFailureInAProfileRollsTheEarlierEntriesBack() {
        val h = Harness()
        listOf(SettingsCatalog.WINDOW_ANIMATION_SCALE, SettingsCatalog.TRANSITION_ANIMATION_SCALE, SettingsCatalog.ANIMATOR_DURATION_SCALE).forEach { h.standard.put(G, it, "1.0") }
        h.standard.failWriteOnKey = mapOf(h.key(G, SettingsCatalog.ANIMATOR_DURATION_SCALE).id to BackendFailure(FailureKind.REJECTED, "no"))
        val pp = svc(h).plan(StarterProfiles.quickUi(), RequestSource.UI)
        val op = assertIs<ApplyResult.Finished>(svc(h).apply(StarterProfiles.quickUi(), RequestSource.UI, false, Confirmation.UserConfirmed(pp.plan.digest))).operation
        assertEquals(OperationStatus.FAILED_ROLLED_BACK, op.status)
        assertEquals("1.0", h.standard.get(G, SettingsCatalog.WINDOW_ANIMATION_SCALE))
        assertEquals("builtin-quick-ui", op.profileId)
    }

    @Test
    fun fileStorePersistsCustomProfilesAndRefusesPathTricks() {
        val dir = Files.createTempDirectory("profiles")
        val store = FileProfileStore(dir)
        val p = SettingsProfile("custom-0001", "Mine", "d", listOf(ProfileEntry(G, SettingsCatalog.WINDOW_ANIMATION_SCALE, "0")))
        store.save(p)
        assertEquals(p.entries, FileProfileStore(dir).get("custom-0001")!!.entries)
        assertEquals(listOf("Mine"), store.list().map { it.name })
        assertTrue(store.delete("custom-0001") && store.list().isEmpty())
        kotlin.runCatching { store.get("../../etc/passwd") }.let { assertTrue(it.isFailure) }
        kotlin.runCatching { store.save(StarterProfiles.quickUi()) }.let { assertTrue(it.isFailure) }
    }

    @Test
    fun contentHashChangesWhenAnEntryChanges() {
        val a = StarterProfiles.quickUi()
        assertNotEquals(a.contentHash, a.copy(entries = a.entries.dropLast(1)).contentHash)
        assertEquals(a.contentHash, StarterProfiles.quickUi().contentHash)
    }
}
