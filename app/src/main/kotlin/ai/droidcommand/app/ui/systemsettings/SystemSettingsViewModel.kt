package ai.droidcommand.app.ui.systemsettings

import ai.droidcommand.app.systemsettings.DeviceFacts
import ai.droidcommand.app.systemsettings.SystemSettingsRuntime
import ai.droidcommand.systemsettings.AccessKind
import ai.droidcommand.systemsettings.AccessSetupInstructions
import ai.droidcommand.systemsettings.ApplyResult
import ai.droidcommand.systemsettings.BackendStatus
import ai.droidcommand.systemsettings.Change
import ai.droidcommand.systemsettings.ChangeRequest
import ai.droidcommand.systemsettings.Confirmation
import ai.droidcommand.systemsettings.DeviceContext
import ai.droidcommand.systemsettings.EntryOutcome
import ai.droidcommand.systemsettings.ModifiedRow
import ai.droidcommand.systemsettings.Operation
import ai.droidcommand.systemsettings.Plan
import ai.droidcommand.systemsettings.ProfileCodec
import ai.droidcommand.systemsettings.ProfileEntry
import ai.droidcommand.systemsettings.ProfileImport
import ai.droidcommand.systemsettings.Proposed
import ai.droidcommand.systemsettings.ReadResult
import ai.droidcommand.systemsettings.ReplayRecord
import ai.droidcommand.systemsettings.RequestSource
import ai.droidcommand.systemsettings.RestoreItem
import ai.droidcommand.systemsettings.RestorePreview
import ai.droidcommand.systemsettings.RestoreRequest
import ai.droidcommand.systemsettings.RiskClass
import ai.droidcommand.systemsettings.SettingKey
import ai.droidcommand.systemsettings.SettingsProfile
import ai.droidcommand.systemsettings.SettingsRisk
import ai.droidcommand.systemsettings.SettingsTable
import ai.droidcommand.systemsettings.Snapshot
import ai.droidcommand.systemsettings.SystemSettingsEngine
import ai.droidcommand.systemsettings.UnsupportedEntry
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/** A change waiting for the owner to read and confirm. [plan] is rebuilt by the engine when the owner confirms, so a stale preview is refused. */
data class PendingPlan(
    val title: String,
    val request: ChangeRequest,
    val plan: Plan,
    val unsupported: List<UnsupportedEntry> = emptyList(),
    val profile: SettingsProfile? = null,
    val includeOptional: Boolean = false,
)

data class PendingRestore(val request: RestoreRequest, val preview: RestorePreview)

data class ApplyOutcome(val title: String, val operation: Operation?, val skipped: List<RestoreItem>, val note: String?)

data class ImportReview(val profile: SettingsProfile?, val errors: List<String>, val warnings: List<String>)

data class BrowseState(
    val table: SettingsTable = SettingsTable.GLOBAL,
    val via: AccessKind? = null,
    val rows: List<SystemSettingsEngine.Row> = emptyList(),
    val partial: Boolean = false,
    val error: String? = null,
    val servedBy: AccessKind? = null,
    val loaded: Boolean = false,
)

data class AdvancedReadout(val text: String, val risk: RiskClass, val known: Boolean)

@HiltViewModel
class SystemSettingsViewModel @Inject constructor(
    private val rt: SystemSettingsRuntime,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val engine get() = rt.engine

    var loading by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set
    var device by mutableStateOf(rt.device())
        private set
    var statuses by mutableStateOf<List<BackendStatus>>(emptyList())
        private set
    var curated by mutableStateOf<List<SystemSettingsEngine.CuratedRow>>(emptyList())
        private set
    var modified by mutableStateOf<List<ModifiedRow>>(emptyList())
        private set
    var history by mutableStateOf<List<Operation>>(emptyList())
        private set
    var profiles by mutableStateOf<List<SettingsProfile>>(emptyList())
        private set
    var replay by mutableStateOf<Map<String, ReplayRecord>>(emptyMap())
        private set
    var requirements by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var observedRate by mutableStateOf<Float?>(null)
        private set
    var dnsLine by mutableStateOf<String?>(null)
        private set
    var browse by mutableStateOf(BrowseState())
        private set
    var readout by mutableStateOf<AdvancedReadout?>(null)
        private set
    var pending by mutableStateOf<PendingPlan?>(null)
        private set
    var pendingRestore by mutableStateOf<PendingRestore?>(null)
        private set
    var forced by mutableStateOf<Set<String>>(emptySet())
        private set
    var outcome by mutableStateOf<ApplyOutcome?>(null)
    var importReview by mutableStateOf<ImportReview?>(null)
    var banner by mutableStateOf<String?>(null)
    var config by mutableStateOf(rt.prefs.config())
        private set

    val applicationId: String get() = context.packageName
    fun setupSteps() = AccessSetupInstructions.steps(applicationId, device.sdkInt, device.release)

    init {
        viewModelScope.launch(Dispatchers.IO) { rt.recoverInterrupted() }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            val snapshot = withContext(Dispatchers.IO) {
                fun <T> timed(name: String, block: () -> T): T {
                    val t0 = System.currentTimeMillis()
                    val r = block()
                    Log.i(TAG, "refresh step $name took ${System.currentTimeMillis() - t0} ms")
                    return r
                }
                val dev = timed("device") { rt.device() }
                val st = timed("statuses") { engine.statuses() }
                val cur = timed("curated") { engine.curated() }
                val mod = timed("modified") { engine.modified() }
                val his = timed("history") { engine.history() }
                val profs = timed("profiles") { rt.profiles() }
                val rep = timed("replay") { rt.replay.records().associateBy { it.profileId } }
                val reqs = timed("requirements") { profs.associate { it.id to rt.replay.requirement(it, includeOptional = false) } }
                val rate = timed("rate") { DeviceFacts.observedRate(context) }
                val dns = timed("dns") {
                    rt.dnsVerifier.status()?.let { if (it.active) "Encrypted DNS is in use${it.serverName?.let { n -> " ($n)" } ?: ""}." else "Encrypted DNS is not in use on the active network." }
                        ?: "No active network, so the active resolver cannot be checked."
                }
                Snapshots(dev, st, cur, mod, his, profs, rep, rate, reqs, dns)
            }
            device = snapshot.device
            statuses = snapshot.statuses
            curated = snapshot.curated
            modified = snapshot.modified
            history = snapshot.history
            profiles = snapshot.profiles
            replay = snapshot.replay
            requirements = snapshot.requirements
            observedRate = snapshot.rate
            dnsLine = snapshot.dns
            config = rt.prefs.config()
            loading = false
        }
    }

    private class Snapshots(
        val device: DeviceContext,
        val statuses: List<BackendStatus>,
        val curated: List<SystemSettingsEngine.CuratedRow>,
        val modified: List<ModifiedRow>,
        val history: List<Operation>,
        val profiles: List<SettingsProfile>,
        val replay: Map<String, ReplayRecord>,
        val rate: Float?,
        val requirements: Map<String, String>,
        val dns: String?,
    )

    // ---- changes ---------------------------------------------------------------------------------------------------------------

    fun previewChange(label: String, changes: List<Change>, manual: Set<String> = emptySet(), ack: Set<String> = emptySet()) {
        val request = ChangeRequest(label, changes, RequestSource.UI, manualReview = manual, highImpactAcknowledged = ack)
        viewModelScope.launch {
            busy = true
            val plan = withContext(Dispatchers.IO) { engine.plan(request) }
            busy = false
            pending = PendingPlan(label, request, plan)
        }
    }

    fun previewProfile(profile: SettingsProfile, includeOptional: Boolean) {
        viewModelScope.launch {
            busy = true
            val pp = withContext(Dispatchers.IO) { rt.profileService.plan(profile, RequestSource.UI, includeOptional) }
            busy = false
            pending = PendingPlan("Apply profile: ${profile.name}", pp.plan.request, pp.plan, pp.unsupported, profile, includeOptional)
        }
    }

    fun setIncludeOptional(on: Boolean) {
        val p = pending?.profile ?: return
        previewProfile(p, on)
    }

    fun cancelPending() {
        pending = null
    }

    fun confirmPending() {
        val p = pending ?: return
        pending = null
        viewModelScope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { engine.apply(p.request, Confirmation.UserConfirmed(p.plan.digest)) }
            busy = false
            when (result) {
                is ApplyResult.Finished -> outcome = ApplyOutcome(p.title, result.operation, emptyList(), null)
                is ApplyResult.PlanChanged -> {
                    pending = p.copy(plan = result.newPlan)
                    banner = "The phone changed after the preview. Check the new values and confirm again."
                }
                is ApplyResult.Rejected -> outcome = ApplyOutcome(p.title, null, emptyList(), "Nothing was changed. " + result.plan.blockers.joinToString(" ") { it.message })
                is ApplyResult.NothingToDo -> banner = "Nothing to change: everything already has those values."
                is ApplyResult.Declined -> banner = "Cancelled. Nothing changed."
                ApplyResult.Busy -> banner = "Another settings operation is running. Nothing was changed. Try again in a moment."
            }
            refresh()
        }
    }

    // ---- restoring -------------------------------------------------------------------------------------------------------------

    fun previewRestore(request: RestoreRequest) {
        forced = emptySet()
        viewModelScope.launch {
            busy = true
            val preview = withContext(Dispatchers.IO) { engine.previewRestore(request) }
            busy = false
            pendingRestore = PendingRestore(request, preview)
        }
    }

    fun toggleForce(keyId: String) {
        val cur = pendingRestore ?: return
        forced = if (keyId in forced) forced - keyId else forced + keyId
        previewRestore(cur.request.copy(forceKeyIds = forced), keepForced = true)
    }

    private fun previewRestore(request: RestoreRequest, keepForced: Boolean) {
        if (!keepForced) forced = emptySet()
        viewModelScope.launch {
            val preview = withContext(Dispatchers.IO) { engine.previewRestore(request) }
            pendingRestore = PendingRestore(request, preview)
        }
    }

    fun cancelRestore() {
        pendingRestore = null
        forced = emptySet()
    }

    fun confirmRestore() {
        val p = pendingRestore ?: return
        pendingRestore = null
        viewModelScope.launch {
            busy = true
            val req = p.request.copy(forceKeyIds = forced)
            val result = withContext(Dispatchers.IO) { engine.restore(req, Confirmation.UserConfirmed(p.preview.digest)) }
            busy = false
            forced = emptySet()
            when (result) {
                is ApplyResult.Finished -> outcome = ApplyOutcome(p.preview.title, result.operation, result.skipped, null)
                is ApplyResult.PlanChanged -> {
                    banner = "The phone changed after the preview. Review the new preview."
                    previewRestore(p.request)
                }
                is ApplyResult.Rejected -> outcome = ApplyOutcome(p.preview.title, null, emptyList(), "Nothing was restored. " + result.plan.blockers.joinToString(" ") { it.message })
                is ApplyResult.NothingToDo -> banner = "Nothing to restore."
                is ApplyResult.Declined -> banner = "Cancelled."
                ApplyResult.Busy -> banner = "Another settings operation is running. Try again in a moment."
            }
            refresh()
        }
    }

    // ---- browsing and the advanced editor ------------------------------------------------------------------------------------------

    fun loadTable(table: SettingsTable, via: AccessKind?) {
        viewModelScope.launch {
            busy = true
            browse = BrowseState(table = table, via = via)
            val listing = withContext(Dispatchers.IO) { engine.list(table, device.userId, via) }
            busy = false
            browse = when (listing) {
                is SystemSettingsEngine.Listing.Rows -> BrowseState(table, via, listing.rows, listing.partial, null, listing.backend, true)
                is SystemSettingsEngine.Listing.Failed -> BrowseState(table, via, emptyList(), false, failureText(listing), listing.backend, true)
            }
        }
    }

    private fun failureText(f: SystemSettingsEngine.Listing.Failed) =
        "Could not list this table: ${f.failure.message} You can still read a single setting by name in the Advanced tab."

    fun clearReadout() {
        readout = null
    }

    fun readKey(table: SettingsTable, name: String) {
        val key = SettingKey.parse(table, name.trim(), device.userId)
        if (key == null) {
            readout = AdvancedReadout("Setting names use letters, digits, _ . : - only (max 128).", RiskClass.UNREVIEWED, false)
            return
        }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { engine.read(key) }
            readout = AdvancedReadout(
                when (val x = r.result) {
                    is ReadResult.Value -> when (val s = x.snapshot) {
                        Snapshot.Absent -> "Not set (the entry does not exist)."
                        is Snapshot.Present -> if (s.value.isEmpty()) "Set to the empty string." else "Current value: ${s.value}"
                    }
                    is ReadResult.Failed -> "Cannot read it (${x.failure.kind.name.lowercase().replace('_', ' ')}): ${x.failure.message}"
                },
                r.risk, r.spec != null,
            )
        }
    }

    fun previewAdvanced(table: SettingsTable, name: String, value: String, remove: Boolean, manualReviewed: Boolean, highImpactAck: Boolean) {
        val key = SettingKey.parse(table, name.trim(), device.userId)
        if (key == null) {
            banner = "Setting names use letters, digits, _ . : - only (max 128)."
            return
        }
        val proposed = if (remove) Proposed.Delete else Proposed.Put(value)
        previewChange(
            "Manual edit: ${key.name}", listOf(Change(key, proposed)),
            manual = if (manualReviewed) setOf(key.id) else emptySet(),
            ack = if (highImpactAck) setOf(key.id) else emptySet(),
        )
    }

    fun riskOf(table: SettingsTable, name: String): RiskClass =
        if (SettingKey.NAME_PATTERN.matches(name.trim())) SettingsRisk.classify(table, name.trim()) else RiskClass.UNREVIEWED

    fun previewCurated(spec: ai.droidcommand.systemsettings.SettingSpec, value: String) {
        val key = SettingKey.of(spec.table, spec.name, device.userId)
        previewChange("${spec.title}: $value", listOf(Change(key, Proposed.Put(value))))
    }

    // ---- profiles --------------------------------------------------------------------------------------------------------------

    fun importProfile(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { s ->
                        val bytes = s.readNBytes(ProfileCodec.MAX_BYTES + 1)
                        String(bytes, Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text == null) {
                importReview = ImportReview(null, listOf("The file could not be read."), emptyList())
                return@launch
            }
            importReview = when (val r = ProfileCodec.decode(text, UUID.randomUUID().toString())) {
                is ProfileImport.Valid -> ImportReview(r.profile, emptyList(), r.warnings)
                is ProfileImport.Invalid -> ImportReview(null, r.errors, emptyList())
            }
        }
    }

    fun keepImportedProfile() {
        val p = importReview?.profile ?: return
        importReview = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { rt.saveProfile(p) }
            banner = "Saved \"${p.name}\". It has not been applied."
            refresh()
        }
    }

    fun exportProfile(profile: SettingsProfile, uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(ProfileCodec.encode(profile.copy(origin = originOf(device))).toByteArray()) } != null
                }.getOrDefault(false)
            }
            banner = if (ok) "Exported \"${profile.name}\". Reapply-after-reboot is never exported." else "The file could not be written."
        }
    }

    private fun originOf(d: DeviceContext) = ai.droidcommand.systemsettings.ProfileOrigin(d.sdkInt, d.release, d.manufacturer, d.model, d.fingerprint)

    /** Saves the current values of the chosen supported catalog settings as a custom profile. Saving never applies anything. */
    fun saveCurrentAsProfile(name: String, specIds: Set<String>) {
        val entries = curated.filter { it.spec.id in specIds && it.unsupportedReason == null }.mapNotNull { row ->
            val v = ((row.current as? ReadResult.Value)?.snapshot as? Snapshot.Present)?.value ?: return@mapNotNull null
            ProfileEntry(row.spec.table, row.spec.name, v)
        }
        if (name.isBlank() || entries.isEmpty()) {
            banner = "Give the profile a name and choose at least one setting that currently has a value."
            return
        }
        val profile = SettingsProfile(UUID.randomUUID().toString(), name.trim().take(ProfileCodec.MAX_NAME), "Saved from this phone's current values.", entries, origin = originOf(device))
        when (val check = ProfileCodec.decode(ProfileCodec.encode(profile), profile.id)) {
            is ProfileImport.Invalid -> banner = "Could not save: " + check.errors.first()
            is ProfileImport.Valid -> viewModelScope.launch {
                withContext(Dispatchers.IO) { rt.saveProfile(profile) }
                banner = "Saved \"${profile.name}\". It has not been applied."
                refresh()
            }
        }
    }

    fun deleteProfile(profile: SettingsProfile) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                rt.replay.disable(profile.id)
                rt.deleteProfile(profile.id)
            }
            refresh()
        }
    }

    fun setReplay(profile: SettingsProfile, on: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { if (on) rt.replay.enable(profile, includeOptional = false) else rt.replay.disable(profile.id) }
            refresh()
        }
    }

    // ---- access ----------------------------------------------------------------------------------------------------------------

    fun setShizukuAllowed(on: Boolean) {
        rt.prefs.setShizukuAllowed(on)
        refresh()
    }
    fun setRootAllowed(on: Boolean) {
        rt.prefs.setRootAllowed(on)
        refresh()
    }
    fun setOtherUsers(on: Boolean) {
        rt.prefs.setOtherUsersAllowed(on)
        refresh()
    }
    fun setAssistant(on: Boolean) {
        rt.prefs.setAssistantEnabled(on)
        refresh()
    }

    fun hasCuratedValue(specId: String): Boolean =
        curated.firstOrNull { it.spec.id == specId }?.let { ((it.current as? ReadResult.Value)?.snapshot) is Snapshot.Present } == true

    companion object {
        private const val TAG = "SSFLOW"

        fun outcomeText(o: EntryOutcome): String = when (o) {
            EntryOutcome.NOT_ATTEMPTED -> "Not attempted"
            EntryOutcome.PERMISSION_DENIED -> "Permission failure"
            EntryOutcome.REJECTED_VALUE -> "Android rejected the value"
            EntryOutcome.READBACK_MISMATCH -> "Read back a different value"
            EntryOutcome.BACKEND_UNAVAILABLE -> "Access method unavailable"
            EntryOutcome.TIMEOUT -> "Timed out"
            EntryOutcome.ERROR -> "Failed"
            EntryOutcome.STORED_EFFECT_UNVERIFIED -> "Stored and read back; effect not verified"
            EntryOutcome.EFFECT_VERIFIED -> "Stored, read back, effect verified"
            EntryOutcome.ALREADY_AT_TARGET -> "Already had that value"
            EntryOutcome.UNCONFIRMED_AFTER_INTERRUPT -> "Interrupted; value is on the phone but unconfirmed"
        }
    }
}
