package ai.droidcommand.systemsettings

import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.AuditLog
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The owner's configured access policy. Selection never goes above what is allowed here, and root is not allowed until the owner turns it on:
 * a rooted phone alone proves nothing about who may change settings.
 */
data class AccessConfig(
    val allowed: Set<AccessKind> = setOf(AccessKind.STANDARD, AccessKind.SHIZUKU),
    /** Lets privileged backends address a user other than the one the app runs as. Off by default. */
    val allowOtherUsers: Boolean = false,
    /** The assistant may preview and request changes (each one still asks). Off by default. */
    val assistantEnabled: Boolean = false,
)

data class Change(val key: SettingKey, val proposed: Proposed)

data class ChangeRequest(
    val label: String,
    val changes: List<Change>,
    val source: RequestSource,
    val profileId: String? = null,
    /** [SettingKey.id]s the owner reviewed by hand in the Advanced editor (entries the catalog knows nothing about). UI only. */
    val manualReview: Set<String> = emptySet(),
    /** [SettingKey.id]s of high-impact entries the owner deliberately confirmed. UI only. */
    val highImpactAcknowledged: Set<String> = emptySet(),
)

enum class IssueCode {
    EMPTY_REQUEST, DUPLICATE_KEY, SOURCE_NOT_ALLOWED, HIGH_IMPACT, NEEDS_MANUAL_REVIEW, INVALID_VALUE, UNSUPPORTED_ON_DEVICE, CROSS_USER,
    NO_BACKEND, UNREADABLE_CURRENT, CANNOT_CAPTURE, CROSS_FIELD, ALREADY_SET, JOURNAL_PROBLEM, JOURNAL_UNWRITABLE,
}

data class Issue(val blocking: Boolean, val code: IssueCode, val message: String, val keyId: String? = null)

data class PlannedEntry(
    val change: Change,
    /** Null when it could not be read. */
    val current: Snapshot?,
    val spec: SettingSpec?,
    val risk: RiskClass,
    /** True when the entry already has the proposed value, so nothing would be written. */
    val noop: Boolean,
    val explanation: String,
)

data class Plan(
    val request: ChangeRequest,
    val entries: List<PlannedEntry>,
    val backend: AccessKind?,
    val backendDetail: String,
    val issues: List<Issue>,
    val digest: String,
) {
    val blockers: List<Issue> get() = issues.filter { it.blocking }
    val warnings: List<Issue> get() = issues.filter { !it.blocking }
    val executable: Boolean get() = blockers.isEmpty() && backend != null && entries.any { !it.noop }

    /** Plain text for an approval dialog: exactly what would change, old and new. */
    fun summaryLines(): List<String> = entries.map { e ->
        val k = e.change.key
        val now = e.current?.describe() ?: "(unreadable)"
        "${k.table.wire}/${k.name} (${k.scopeLabel}): $now → ${e.change.proposed.describe()}" + if (e.noop) " (already set)" else ""
    }
}

/** What the person (or the owner's saved opt-in) confirmed. */
sealed interface Confirmation {
    /** The UI shows a preview with [digest]; if anything changed since, the engine refuses and shows the new preview. */
    data class UserConfirmed(val digest: String) : Confirmation

    /** The owner turned reapply-after-reboot on for [profileId]. */
    data class ReplayOptIn(val profileId: String) : Confirmation

    /** For sources that ask through [ChangeApprover] (assistant, remote). */
    data object None : Confirmation
}

/** The existing approval flow, adapted. Called at execution time with the exact plan; returns whether the owner said yes. */
fun interface ChangeApprover {
    fun approve(source: RequestSource, title: String, lines: List<String>, backend: AccessKind): Boolean
}

sealed interface RemoteDecision {
    data object Allowed : RemoteDecision
    data class Denied(val reason: String) : RemoteDecision
}

/**
 * Hook for a future remote settings command. Implementations must check pairing, device scope and the settings permission (REMOTE_SETTINGS if
 * it is ever added). None is wired today: no remote settings command exists in this app, so every remote request is refused.
 */
fun interface RemoteSettingsAuthorizer {
    fun authorize(request: ChangeRequest): RemoteDecision
}

/** An independent check that a stored value changed behaviour. Return null when this verifier does not apply to [key]. */
fun interface EffectVerifier {
    fun verify(key: SettingKey, storedValue: String?): EffectVerdict?
}

data class EffectVerdict(val verified: Boolean, val note: String)

sealed interface ApplyResult {
    /** The change set was refused before anything was written. */
    data class Rejected(val plan: Plan) : ApplyResult

    /** The phone changed since the preview; show [newPlan] and ask again. */
    data class PlanChanged(val newPlan: Plan) : ApplyResult
    data class Declined(val plan: Plan) : ApplyResult
    data class NothingToDo(val plan: Plan) : ApplyResult

    /** Another settings operation is running. Nothing was written. */
    data object Busy : ApplyResult
    data class Finished(val operation: Operation, val skipped: List<RestoreItem> = emptyList()) : ApplyResult
}

enum class RestoreState { READY, ALREADY_AT_TARGET, CONFLICT, UNREADABLE }

data class RestoreItem(
    val key: SettingKey,
    val sourceOperationId: String?,
    val target: Snapshot,
    /** What this app last recorded writing; null when unknown. */
    val expectedCurrent: Snapshot?,
    val current: Snapshot?,
    val state: RestoreState,
    val note: String,
)

data class RestorePreview(
    val title: String,
    val baseline: Boolean,
    val operationId: String?,
    val items: List<RestoreItem>,
    val backend: AccessKind?,
    val issues: List<Issue>,
    val digest: String,
    /** Conflicting entries the owner chose to overwrite. */
    val forced: Set<String> = emptySet(),
) {
    val blockers: List<Issue> get() = issues.filter { it.blocking }
    val conflicts: List<RestoreItem> get() = items.filter { it.state == RestoreState.CONFLICT }
    val actionable: Boolean
        get() = blockers.isEmpty() && backend != null &&
            items.any { it.state == RestoreState.READY || it.state == RestoreState.ALREADY_AT_TARGET || (it.state == RestoreState.CONFLICT && it.key.id in forced) }
}

data class RestoreRequest(
    val source: RequestSource,
    val operationId: String? = null,
    /** Restore the original baseline instead of one operation's previous state. */
    val baseline: Boolean = false,
    /** For a baseline restore: only these entries; null means every baseline. */
    val keyIds: Set<String>? = null,
    /** Conflicting entries the owner explicitly chose to overwrite. UI only. */
    val forceKeyIds: Set<String> = emptySet(),
)

data class ModifiedRow(
    val key: SettingKey,
    val baseline: Snapshot,
    val current: Snapshot?,
    val lastKnown: Snapshot?,
    val unreadableReason: String?,
) {
    /** True when the value on the phone is not what this app last recorded: something else changed it. */
    val changedElsewhere: Boolean get() = current != null && lastKnown != null && current != lastKnown
    val differsFromBaseline: Boolean get() = current == null || current != baseline
}

sealed interface Selection {
    data class Chosen(val backend: SettingsBackend, val status: BackendStatus) : Selection
    data class None(val reasons: Map<AccessKind, String>) : Selection {
        val text: String get() = reasons.entries.joinToString(" ") { "${it.key.label}: ${it.value}" }
    }
}

class SystemSettingsEngine(
    private val backends: List<SettingsBackend>,
    private val journal: OperationJournal,
    private val device: () -> DeviceContext,
    private val access: () -> AccessConfig,
    private val approver: ChangeApprover = ChangeApprover { _, _, _, _ -> false },
    private val remote: RemoteSettingsAuthorizer? = null,
    private val verifiers: List<EffectVerifier> = emptyList(),
    private val audit: AuditLog? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val lockWaitMs: Long = 5_000,
) {
    private val lock = ReentrantLock()

    init {
        require(backends.map { it.kind }.toSet().size == backends.size) { "one backend per access kind" }
    }

    // ---- access ----------------------------------------------------------------------------------------------------------------

    fun statuses(): List<BackendStatus> = backends.sortedBy { it.kind.ordinal }.map { it.status() }

    /**
     * The least-privileged backend that is allowed by the owner's access settings, ready, and able to do the job. Failing at run time does not
     * move on to the next tier: the caller reports the failure and the owner decides.
     */
    fun select(tables: Set<SettingsTable>, write: Boolean, userIds: Set<Int>): Selection {
        val cfg = access()
        val dev = device()
        val needsCrossUser = tables.any { !it.deviceWide } && userIds.any { it != dev.userId }
        val reasons = LinkedHashMap<AccessKind, String>()
        for (b in backends.sortedBy { it.kind.ordinal }) {
            if (b.kind !in cfg.allowed) {
                reasons[b.kind] = "not allowed in your access settings."
                continue
            }
            val s = b.status()
            val reason = when {
                s.state == BackendState.UNAVAILABLE -> s.detail
                needsCrossUser && !cfg.allowOtherUsers -> "changing another user's settings is turned off."
                needsCrossUser && !s.crossUser -> "cannot reach another user."
                write && !s.writableTables.containsAll(tables) ->
                    "cannot write " + (tables - s.writableTables).joinToString { it.wire } + ". " + s.detail
                !write && !s.readableTables.containsAll(tables) -> "cannot read " + (tables - s.readableTables).joinToString { it.wire } + "."
                else -> null
            }
            if (reason == null) return Selection.Chosen(b, s)
            reasons[b.kind] = reason
        }
        return Selection.None(reasons)
    }

    private fun backendOf(kind: AccessKind): SettingsBackend? = backends.firstOrNull { it.kind == kind }

    // ---- reading ---------------------------------------------------------------------------------------------------------------

    data class EntryRead(
        val key: SettingKey,
        val result: ReadResult,
        val spec: SettingSpec?,
        val risk: RiskClass,
        val backend: AccessKind?,
    )

    /** Reads one entry. [via] picks a specific allowed backend; otherwise the least privileged one that can read it. */
    fun read(key: SettingKey, via: AccessKind? = null): EntryRead {
        val spec = SettingsCatalog.find(key)
        val risk = SettingsRisk.classify(key)
        val backend = pickReader(setOf(key.table), setOf(key.userId), via)
        return when (backend) {
            is Selection.None -> EntryRead(
                key,
                ReadResult.Failed(BackendFailure(FailureKind.UNAVAILABLE, "No allowed access method can read this. ${backend.text}")),
                spec, risk, null,
            )
            is Selection.Chosen -> EntryRead(key, backend.backend.read(key), spec, risk, backend.backend.kind)
        }
    }

    sealed interface Listing {
        data class Rows(val backend: AccessKind, val rows: List<Row>, val partial: Boolean) : Listing
        data class Failed(val failure: BackendFailure, val backend: AccessKind?) : Listing
    }

    data class Row(val key: SettingKey, val value: String, val spec: SettingSpec?, val risk: RiskClass)

    fun list(table: SettingsTable, userId: Int, via: AccessKind? = null): Listing {
        val sel = pickReader(setOf(table), setOf(userId), via)
        if (sel is Selection.None) return Listing.Failed(BackendFailure(FailureKind.UNAVAILABLE, "No allowed access method can list this table. ${sel.text}"), null)
        sel as Selection.Chosen
        return when (val r = sel.backend.list(table, userId)) {
            is ListResult.Failed -> Listing.Failed(r.failure, sel.backend.kind)
            is ListResult.Entries -> Listing.Rows(
                sel.backend.kind,
                r.values.entries.sortedBy { it.key }.mapNotNull { (name, value) ->
                    SettingKey.parse(table, name, userId)?.let { k -> Row(k, value, SettingsCatalog.find(k), SettingsRisk.classify(k)) }
                },
                r.partial,
            )
        }
    }

    private fun pickReader(tables: Set<SettingsTable>, userIds: Set<Int>, via: AccessKind?): Selection {
        if (via != null) {
            val b = backendOf(via) ?: return Selection.None(mapOf(via to "not present in this build."))
            if (via !in access().allowed) return Selection.None(mapOf(via to "not allowed in your access settings."))
            val s = b.status()
            if (s.state == BackendState.UNAVAILABLE) return Selection.None(mapOf(via to s.detail))
            return Selection.Chosen(b, s)
        }
        return select(tables, write = false, userIds = userIds)
    }

    data class CuratedRow(
        val spec: SettingSpec,
        val key: SettingKey,
        val unsupportedReason: String?,
        val current: ReadResult?,
        val presets: List<Preset>,
    )

    /** Every catalog setting with its support on this phone and its current value. */
    fun curated(): List<CuratedRow> {
        val dev = device()
        return SettingsCatalog.specs.map { spec ->
            val key = SettingKey.of(spec.table, spec.name, dev.userId)
            val problem = spec.supportProblem(dev)
            CuratedRow(spec, key, problem, if (problem == null) read(key).result else null, if (problem == null) spec.presets(dev) else emptyList())
        }
    }

    // ---- planning --------------------------------------------------------------------------------------------------------------

    fun plan(request: ChangeRequest): Plan {
        val dev = device()
        val cfg = access()
        val issues = mutableListOf<Issue>()
        fun block(code: IssueCode, msg: String, key: SettingKey? = null) { issues += Issue(true, code, msg, key?.id) }
        fun warn(code: IssueCode, msg: String, key: SettingKey? = null) { issues += Issue(false, code, msg, key?.id) }

        if (request.changes.isEmpty()) block(IssueCode.EMPTY_REQUEST, "There is nothing to change.")
        val dup = request.changes.groupBy { it.key.id }.filter { it.value.size > 1 }.keys
        dup.forEach { block(IssueCode.DUPLICATE_KEY, "$it appears more than once.") }

        sourceBlockers(request, cfg).forEach { block(IssueCode.SOURCE_NOT_ALLOWED, it) }
        if (journal.unreadable().isNotEmpty()) {
            warn(IssueCode.JOURNAL_PROBLEM, "${journal.unreadable().size} history file(s) could not be read, so some original values may be missing from history.")
        }

        val ordered = orderChanges(request.changes)
        for (c in ordered) {
            val k = c.key
            val spec = SettingsCatalog.find(k)
            val risk = SettingsRisk.classify(k)
            when (risk) {
                RiskClass.HIGH_IMPACT -> when {
                    request.source != RequestSource.UI -> block(IssueCode.HIGH_IMPACT, "${k.name} is a high-impact setting (identity, credentials, provisioning, accessibility or input). Only a deliberate edit in the app can change it.", k)
                    k.id !in request.highImpactAcknowledged -> block(IssueCode.HIGH_IMPACT, "${k.name} is a high-impact setting. Confirm that you really want to change it.", k)
                    else -> warn(IssueCode.HIGH_IMPACT, "${k.name} is a high-impact setting.", k)
                }
                RiskClass.UNREVIEWED -> when {
                    request.source != RequestSource.UI -> block(IssueCode.NEEDS_MANUAL_REVIEW, "${k.name} is not a reviewed setting, so only a manual edit in the app can change it.", k)
                    k.id !in request.manualReview -> block(IssueCode.NEEDS_MANUAL_REVIEW, "${k.name} is not in the reviewed catalog. Review it and confirm the manual edit.", k)
                    else -> warn(IssueCode.NEEDS_MANUAL_REVIEW, "${k.name} is not in the reviewed catalog; nothing is known about what it does.", k)
                }
                RiskClass.CURATED -> Unit
            }
            if (request.source != RequestSource.UI && c.proposed is Proposed.Delete) {
                block(IssueCode.SOURCE_NOT_ALLOWED, "Only the owner in the app can remove an entry.", k)
            }
            if (!k.table.deviceWide && k.userId != dev.userId) {
                if (!cfg.allowOtherUsers) block(IssueCode.CROSS_USER, "${k.name} is for user ${k.userId}; changing other users' settings is off.", k)
                if (request.source != RequestSource.UI) block(IssueCode.CROSS_USER, "Only the owner in the app can change another user's settings.", k)
            }
            if (c.proposed is Proposed.Put) {
                SettingsCommands.valueProblem(c.proposed.value)?.let { block(IssueCode.INVALID_VALUE, "${k.name}: $it", k) }
                if (spec != null) {
                    spec.supportProblem(dev)?.let { block(IssueCode.UNSUPPORTED_ON_DEVICE, "${k.name}: $it", k) }
                    spec.type.check(c.proposed.value, dev)?.let { block(IssueCode.INVALID_VALUE, "${spec.title}: $it", k) }
                }
            }
        }

        val tables = ordered.map { it.key.table }.toSet()
        val users = ordered.map { it.key.userId }.toSet()
        val sel = if (ordered.isEmpty()) null else select(tables, write = true, userIds = users)
        var backendKind: AccessKind? = null
        var detail = ""
        val current = LinkedHashMap<String, Snapshot?>()
        when (sel) {
            null -> Unit
            is Selection.None -> block(IssueCode.NO_BACKEND, "No allowed access method can write this. ${sel.text}")
            is Selection.Chosen -> {
                backendKind = sel.backend.kind
                detail = sel.status.detail
                for (c in ordered) {
                    when (val r = sel.backend.read(c.key)) {
                        is ReadResult.Failed -> {
                            current[c.key.id] = null
                            block(IssueCode.UNREADABLE_CURRENT, "${c.key.name}: the current value cannot be read (${r.failure.message}), so its original state cannot be saved and it will not be changed.", c.key)
                        }
                        is ReadResult.Value -> {
                            current[c.key.id] = r.snapshot
                            val s = r.snapshot
                            if (s is Snapshot.Present && SettingsCommands.valueProblem(s.value) != null) {
                                block(IssueCode.CANNOT_CAPTURE, "${c.key.name}: the current value has line breaks or control characters or is very long, so it cannot be saved and restored exactly. It will not be changed.", c.key)
                            }
                        }
                    }
                }
            }
        }

        val final = HashMap<String, Snapshot>()
        for (c in ordered) final[c.key.id] = c.proposed.asSnapshot()
        val currentNonNull = current.filterValues { it != null }.mapValues { it.value!! }.toMutableMap()
        // Rules that look at a partner entry (Private DNS mode and hostname, minimum and peak refresh rate) need its current value even
        // when the change set does not touch it. A partner that cannot be read is simply left out.
        if (sel is Selection.Chosen) {
            val partners = relatedKeys(ordered.map { it.key }, dev)
            for (k in partners) {
                if (k.id in currentNonNull) continue
                (sel.backend.read(k) as? ReadResult.Value)?.let { currentNonNull[k.id] = it.snapshot }
            }
        }
        CrossFieldRules.check(final, currentNonNull, dev, ordered.map { it.key.id }.toSet()).forEach {
            if (it.blocking) block(IssueCode.CROSS_FIELD, it.message) else warn(IssueCode.CROSS_FIELD, it.message)
        }

        val entries = ordered.map { c ->
            val cur = current[c.key.id]
            val noop = cur != null && cur == c.proposed.asSnapshot()
            if (noop) warn(IssueCode.ALREADY_SET, "${c.key.name} already has that value.", c.key)
            val spec = SettingsCatalog.find(c.key)
            PlannedEntry(c, cur, spec, SettingsRisk.classify(c.key), noop, explain(c, spec, cur))
        }
        return Plan(request, entries, backendKind, detail, issues, digestOf(request.source, backendKind, entries))
    }

    private fun relatedKeys(touched: List<SettingKey>, dev: DeviceContext): Set<SettingKey> {
        val names = touched.map { it.table to it.name }.toSet()
        val out = LinkedHashSet<SettingKey>()
        if (names.any { it.first == SettingsTable.GLOBAL && it.second in DNS_KEYS }) DNS_KEYS.forEach { out += SettingKey.of(SettingsTable.GLOBAL, it, dev.userId) }
        if (names.any { it.first == SettingsTable.SYSTEM && it.second in REFRESH_KEYS }) REFRESH_KEYS.forEach { out += SettingKey.of(SettingsTable.SYSTEM, it, dev.userId) }
        return out
    }

    private companion object {
        val DNS_KEYS = listOf(SettingsCatalog.PRIVATE_DNS_MODE, SettingsCatalog.PRIVATE_DNS_SPECIFIER)
        val REFRESH_KEYS = listOf(SettingsCatalog.MIN_REFRESH_RATE, SettingsCatalog.PEAK_REFRESH_RATE)
    }

    private fun explain(c: Change, spec: SettingSpec?, current: Snapshot?): String {
        if (spec == null) return "Unknown setting. The app has no description for it and cannot say what it does."
        val now = current?.describe() ?: "(unreadable)"
        return "${spec.title}: $now → ${c.proposed.describe()}. ${spec.effectNote}"
    }

    private fun sourceBlockers(request: ChangeRequest, cfg: AccessConfig): List<String> = when (request.source) {
        RequestSource.UI -> emptyList()
        RequestSource.AI ->
            if (cfg.assistantEnabled) emptyList() else listOf("The assistant is not allowed to change system settings. Turn it on in System Settings → Access.")
        RequestSource.BOOT_REPLAY -> if (request.profileId == null) listOf("Reapply after reboot only runs saved profiles.") else emptyList()
        RequestSource.REMOTE -> when (val r = remote?.authorize(request)) {
            null -> listOf("Remote settings changes are not available: no remote settings command exists on this device.")
            RemoteDecision.Allowed -> emptyList()
            is RemoteDecision.Denied -> listOf(r.reason)
        }
    }

    /** A deliberate order: for Private DNS, the hostname goes in before the mode that needs it, and the mode goes out before the hostname is touched. Everything else keeps the requested order. */
    private fun orderChanges(changes: List<Change>): List<Change> {
        val mode = changes.firstOrNull { it.key.table == SettingsTable.GLOBAL && it.key.name == SettingsCatalog.PRIVATE_DNS_MODE }
        val spec = changes.firstOrNull { it.key.table == SettingsTable.GLOBAL && it.key.name == SettingsCatalog.PRIVATE_DNS_SPECIFIER }
        if (mode == null || spec == null) return changes
        val modeFirst = (mode.proposed as? Proposed.Put)?.value != "hostname"
        val rest = changes.filter { it !== mode && it !== spec }
        return if (modeFirst) listOf(mode, spec) + rest else listOf(spec, mode) + rest
    }

    private fun digestOf(source: RequestSource, backend: AccessKind?, entries: List<PlannedEntry>): String {
        val text = buildString {
            append(source).append('|').append(backend).append('\n')
            entries.forEach { e ->
                append(e.change.key.id).append('|').append(e.current?.let { it.toString() } ?: "?").append('|').append(e.change.proposed).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // ---- applying --------------------------------------------------------------------------------------------------------------

    private inline fun <T> serialized(busy: () -> T, block: () -> T): T {
        if (!lock.tryLock(lockWaitMs, TimeUnit.MILLISECONDS)) return busy()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Applies [request]. The plan is rebuilt here, at execution time, so a stale preview, a revoked permission or a changed phone cannot slip
     * through. Every change set is recorded durably before the first write, each write is read back, and a failure puts earlier writes back.
     * Android settings have no transaction: this is write-ahead recording plus compensation, not an atomic database operation.
     */
    fun apply(request: ChangeRequest, confirmation: Confirmation): ApplyResult = serialized({ ApplyResult.Busy }) {
        val plan = plan(request)
        if (plan.blockers.isNotEmpty() || plan.backend == null) {
            record(AuditEventType.SETTINGS_REFUSED, request.label, "refused: " + plan.blockers.joinToString("; ") { it.message }.take(300))
            return@serialized ApplyResult.Rejected(plan)
        }
        if (!plan.executable) return@serialized ApplyResult.NothingToDo(plan)
        when (val gate = authorize(request.source, request.profileId, confirmation, plan.digest, request.label, plan.summaryLines(), plan.backend)) {
            AuthResult.Ok -> Unit
            AuthResult.Changed -> return@serialized ApplyResult.PlanChanged(plan)
            AuthResult.Declined -> {
                record(AuditEventType.SETTINGS_REFUSED, request.label, "declined by the owner")
                return@serialized ApplyResult.Declined(plan)
            }
            is AuthResult.Refused -> return@serialized ApplyResult.Rejected(plan.copy(issues = plan.issues + Issue(true, IssueCode.SOURCE_NOT_ALLOWED, gate.reason)))
        }
        val backend = backendOf(plan.backend)!!
        val all = journal.all()
        val baselines = Baselines.compute(all)
        val entries = plan.entries.filter { !it.noop }.map { pe ->
            JournalEntry(
                key = pe.change.key,
                previous = pe.current!!,
                proposed = pe.change.proposed,
                startsBaseline = pe.change.key.id !in baselines,
            )
        }
        var op = Operation(
            id = newId(), kind = OperationKind.APPLY, source = request.source, label = request.label, createdAt = clock(),
            backend = plan.backend, deviceFingerprint = device().fingerprint, status = OperationStatus.APPLYING, entries = entries,
            profileId = request.profileId,
        )
        try {
            journal.save(op)
        } catch (e: IOException) {
            val blocked = plan.copy(issues = plan.issues + Issue(true, IssueCode.JOURNAL_UNWRITABLE, "The change history cannot be saved, so nothing was changed."))
            return@serialized ApplyResult.Rejected(blocked)
        }
        record(AuditEventType.SETTINGS_CHANGED, request.label, "started via ${plan.backend.label}: " + entries.joinToString { it.key.name })
        op = runApply(op, backend)
        record(AuditEventType.SETTINGS_CHANGED, request.label, "finished ${op.status}: " + op.entries.joinToString { "${it.key.name}=${it.outcome}" })
        ApplyResult.Finished(op)
    }

    private sealed interface AuthResult {
        data object Ok : AuthResult
        data object Changed : AuthResult
        data object Declined : AuthResult
        data class Refused(val reason: String) : AuthResult
    }

    private fun authorize(
        source: RequestSource,
        profileId: String?,
        confirmation: Confirmation,
        digest: String,
        title: String,
        lines: List<String>,
        backend: AccessKind,
    ): AuthResult = when (source) {
        RequestSource.UI -> when {
            confirmation is Confirmation.UserConfirmed && confirmation.digest == digest -> AuthResult.Ok
            confirmation is Confirmation.UserConfirmed -> AuthResult.Changed
            else -> AuthResult.Refused("A change from the app needs the owner's confirmation of the preview.")
        }
        RequestSource.AI, RequestSource.REMOTE ->
            if (approver.approve(source, title, lines, backend)) AuthResult.Ok else AuthResult.Declined
        RequestSource.BOOT_REPLAY ->
            if (confirmation is Confirmation.ReplayOptIn && confirmation.profileId == profileId) AuthResult.Ok else AuthResult.Refused("Reapply needs the profile's own reapply setting.")
    }

    /** Runs the recorded entries in order, verifying each. Returns the final recorded operation. */
    private fun runApply(start: Operation, backend: SettingsBackend): Operation {
        var op = start
        fun persist(): Boolean = try { journal.save(op); true } catch (e: IOException) { false }
        fun update(i: Int, f: (JournalEntry) -> JournalEntry): Boolean {
            op = op.copy(entries = op.entries.toMutableList().also { it[i] = f(it[i]) })
            return persist()
        }

        var failedAt = -1
        for (i in op.entries.indices) {
            if (!update(i) { it.copy(phase = EntryPhase.WRITE_STARTED) }) {
                update(i) { it.copy(phase = EntryPhase.DONE, outcome = EntryOutcome.ERROR, note = "The change history could not be saved, so this write was not attempted.") }
                failedAt = i
                break
            }
            val e = op.entries[i]
            val written = when (val p = e.proposed) {
                is Proposed.Put -> backend.write(e.key, p.value)
                Proposed.Delete -> backend.delete(e.key)
            }
            if (written is WriteResult.Failed) {
                update(i) { it.copy(phase = EntryPhase.DONE, outcome = outcomeOf(written.failure.kind), note = written.failure.message) }
                failedAt = i
                break
            }
            val read = backend.read(e.key)
            val observed = (read as? ReadResult.Value)?.snapshot
            val spec = SettingsCatalog.find(e.key)
            val matches = observed != null && when (val p = e.proposed) {
                is Proposed.Put -> observed is Snapshot.Present && (spec?.type?.equivalent(observed.value, p.value) ?: (observed.value == p.value))
                Proposed.Delete -> observed == Snapshot.Absent
            }
            if (!matches) {
                val note = if (observed == null) "The value could not be read back after writing." else "Read back ${observed.describe()}, expected ${e.proposed.describe()}."
                update(i) { it.copy(phase = EntryPhase.DONE, outcome = EntryOutcome.READBACK_MISMATCH, note = note, observedAfterWrite = observed) }
                failedAt = i
                break
            }
            val storedText = (e.proposed as? Proposed.Put)?.value
            val verdict = verifiers.firstNotNullOfOrNull { it.verify(e.key, storedText) }
            update(i) {
                it.copy(
                    phase = EntryPhase.DONE, observedAfterWrite = observed,
                    outcome = if (verdict?.verified == true) EntryOutcome.EFFECT_VERIFIED else EntryOutcome.STORED_EFFECT_UNVERIFIED,
                    effectNote = verdict?.note ?: "Stored and read back. No check shows that the phone's behaviour changed.",
                )
            }
        }
        if (failedAt < 0) {
            op = op.copy(status = OperationStatus.COMPLETED, finishedAt = clock())
            persist()
            return op
        }
        // Compensating rollback, newest first. The failing entry is included because a timeout or mismatch may still have changed it.
        var allBack = true
        for (i in failedAt downTo 0) {
            val e = op.entries[i]
            val mayHaveChanged = e.outcome.stored || e.outcome == EntryOutcome.READBACK_MISMATCH || e.outcome == EntryOutcome.TIMEOUT || e.outcome == EntryOutcome.ERROR ||
                e.outcome == EntryOutcome.BACKEND_UNAVAILABLE
            if (!mayHaveChanged || e.phase == EntryPhase.PENDING) continue
            val r = rollbackEntry(backend, e)
            if (r.first != RestoreStatus.AUTO_ROLLED_BACK) allBack = false
            update(i) { it.copy(restoreStatus = r.first, restoreNote = r.second, restoredAt = clock()) }
        }
        op = op.copy(status = if (allBack) OperationStatus.FAILED_ROLLED_BACK else OperationStatus.FAILED_PARTIAL, finishedAt = clock())
        persist()
        return op
    }

    private fun rollbackEntry(backend: SettingsBackend, e: JournalEntry): Pair<RestoreStatus, String> {
        val current = when (val r = backend.read(e.key)) {
            is ReadResult.Failed -> return RestoreStatus.RESTORE_FAILED to "Could not read the value to check it before undoing: ${r.failure.message}"
            is ReadResult.Value -> r.snapshot
        }
        if (current == e.previous) return RestoreStatus.AUTO_ROLLED_BACK to "Already at the previous value."
        val expected = e.observedAfterWrite ?: e.proposed.asSnapshot()
        val spec = SettingsCatalog.find(e.key)
        val sameAsWritten = current == expected || (current is Snapshot.Present && expected is Snapshot.Present && spec?.type?.equivalent(current.value, expected.value) == true)
        if (!sameAsWritten) return RestoreStatus.CONFLICT to "Changed to ${current.describe()} by something else, so it was left alone."
        return when (val w = writeTarget(backend, e.key, e.previous)) {
            is WriteResult.Failed -> RestoreStatus.RESTORE_FAILED to "Could not put the previous value back: ${w.failure.message}"
            WriteResult.Done -> RestoreStatus.AUTO_ROLLED_BACK to "Put back to ${e.previous.describe()}."
        }
    }

    /** Writes [target] and reads it back; an exact match is required because restoration returns the exact previous text. */
    private fun writeTarget(backend: SettingsBackend, key: SettingKey, target: Snapshot): WriteResult {
        val w = when (target) {
            Snapshot.Absent -> backend.delete(key)
            is Snapshot.Present -> backend.write(key, target.value)
        }
        if (w is WriteResult.Failed) return w
        val back = backend.read(key)
        return if (back is ReadResult.Value && back.snapshot == target) {
            WriteResult.Done
        } else {
            WriteResult.Failed(BackendFailure(FailureKind.ERROR, "The value read back after writing was not ${target.describe()}."))
        }
    }

    private fun outcomeOf(kind: FailureKind) = when (kind) {
        FailureKind.PERMISSION_DENIED -> EntryOutcome.PERMISSION_DENIED
        FailureKind.UNAVAILABLE -> EntryOutcome.BACKEND_UNAVAILABLE
        FailureKind.TIMEOUT -> EntryOutcome.TIMEOUT
        FailureKind.REJECTED -> EntryOutcome.REJECTED_VALUE
        FailureKind.UNSUPPORTED, FailureKind.UNREADABLE, FailureKind.ERROR -> EntryOutcome.ERROR
    }

    // ---- history ---------------------------------------------------------------------------------------------------------------

    /** Newest first. */
    fun history(limit: Int = 200): List<Operation> = journal.all().sortedByDescending { it.createdAt }.take(limit)

    fun baselines(): Map<String, Baseline> = Baselines.compute(journal.all())

    /** Entries this app changed that still have an active original baseline, with their current value. */
    fun modified(): List<ModifiedRow> {
        val all = journal.all()
        return Baselines.compute(all).values.map { b ->
            val r = read(b.key)
            val cur = (r.result as? ReadResult.Value)?.snapshot
            ModifiedRow(b.key, b.snapshot, cur, Baselines.lastKnown(all, b.key.id), (r.result as? ReadResult.Failed)?.failure?.message)
        }
    }

    // ---- restoring -------------------------------------------------------------------------------------------------------------

    fun previewRestore(request: RestoreRequest): RestorePreview {
        val all = journal.all()
        val issues = mutableListOf<Issue>()
        val dev = device()
        val sourceIssues = sourceBlockers(ChangeRequest("restore", emptyList(), request.source, profileId = if (request.source == RequestSource.BOOT_REPLAY) "restore" else null), access())
        sourceIssues.forEach { issues += Issue(true, IssueCode.SOURCE_NOT_ALLOWED, it) }
        if (request.source == RequestSource.BOOT_REPLAY) issues += Issue(true, IssueCode.SOURCE_NOT_ALLOWED, "Reapply does not restore.")

        data class Raw(val key: SettingKey, val opId: String?, val target: Snapshot, val expected: Snapshot?)
        val raws = mutableListOf<Raw>()
        val title: String
        if (request.baseline) {
            title = "Restore recorded original values"
            val bs = Baselines.compute(all).values.filter { request.keyIds == null || it.key.id in request.keyIds }
            for (b in bs) raws += Raw(b.key, b.operationId, b.snapshot, Baselines.lastKnown(all, b.key.id))
        } else {
            val op = request.operationId?.let { id -> all.firstOrNull { it.id == id } }
            if (op == null) {
                issues += Issue(true, IssueCode.EMPTY_REQUEST, "That operation is not in the history.")
                title = "Restore"
            } else {
                title = "Restore \"${op.label}\""
                for (e in op.entries.asReversed()) {
                    val restorable = (e.outcome.stored || e.outcome == EntryOutcome.READBACK_MISMATCH) &&
                        e.restoreStatus in setOf(RestoreStatus.NOT_RESTORED, RestoreStatus.CONFLICT, RestoreStatus.RESTORE_FAILED) &&
                        e.phase != EntryPhase.PENDING
                    if (restorable) raws += Raw(e.key, op.id, e.previous, e.observedAfterWrite ?: e.proposed.asSnapshot())
                }
            }
        }
        if (raws.isEmpty() && issues.none { it.blocking }) issues += Issue(true, IssueCode.EMPTY_REQUEST, "There is nothing to restore.")
        if (request.source != RequestSource.UI) {
            raws.filter { SettingsRisk.classify(it.key) != RiskClass.CURATED }.forEach {
                issues += Issue(true, IssueCode.HIGH_IMPACT, "${it.key.name} is not a reviewed setting; only the owner in the app can restore it.", it.key.id)
            }
        }

        val sel = if (raws.isEmpty()) null else select(raws.map { it.key.table }.toSet(), write = true, userIds = raws.map { it.key.userId }.toSet())
        var backendKind: AccessKind? = null
        val items = mutableListOf<RestoreItem>()
        when (sel) {
            null -> Unit
            is Selection.None -> issues += Issue(true, IssueCode.NO_BACKEND, "No allowed access method can write this. ${sel.text}")
            is Selection.Chosen -> {
                backendKind = sel.backend.kind
                for (r in raws) {
                    val cur = (sel.backend.read(r.key) as? ReadResult.Value)?.snapshot
                    val spec = SettingsCatalog.find(r.key)
                    val (state, note) = when {
                        cur == null -> RestoreState.UNREADABLE to "The current value cannot be read, so it cannot be checked."
                        cur == r.target -> RestoreState.ALREADY_AT_TARGET to "Already has the recorded value."
                        r.expected == null -> RestoreState.READY to "No recorded value to compare; it will be overwritten."
                        cur == r.expected || (cur is Snapshot.Present && r.expected is Snapshot.Present && spec?.type?.equivalent(cur.value, r.expected.value) == true) ->
                            RestoreState.READY to "Unchanged since it was set."
                        else -> RestoreState.CONFLICT to "Now ${cur.describe()}; this app last recorded ${r.expected.describe()}. Something else changed it."
                    }
                    items += RestoreItem(r.key, r.opId, r.target, r.expected, cur, state, note)
                }
            }
        }
        val digestText = items.joinToString("\n") { "${it.key.id}|${it.current}|${it.target}|${it.state}" } + "|" + backendKind + "|" + request.forceKeyIds.sorted()
        val digest = MessageDigest.getInstance("SHA-256").digest(digestText.toByteArray()).joinToString("") { "%02x".format(it) }
        if (dev.sdkInt < 0) issues += Issue(true, IssueCode.EMPTY_REQUEST, "unknown device")
        val forced = if (request.source == RequestSource.UI) request.forceKeyIds else emptySet()
        return RestorePreview(title, request.baseline, request.operationId, items, backendKind, issues, digest, forced)
    }

    /** Restores recorded values. Conflicting entries are left alone unless the owner listed them in [RestoreRequest.forceKeyIds] (UI only). */
    fun restore(request: RestoreRequest, confirmation: Confirmation): ApplyResult = serialized({ ApplyResult.Busy }) {
        val preview = previewRestore(request)
        val asPlan = Plan(ChangeRequest(preview.title, emptyList(), request.source), emptyList(), preview.backend, "", preview.issues, preview.digest)
        if (!preview.actionable) {
            record(AuditEventType.SETTINGS_REFUSED, preview.title, "restore refused")
            return@serialized ApplyResult.Rejected(asPlan)
        }
        if (request.source == RequestSource.BOOT_REPLAY) return@serialized ApplyResult.Rejected(asPlan)
        val lines = preview.items.map { "${it.key.table.wire}/${it.key.name}: ${it.current?.describe() ?: "(unreadable)"} → ${it.target.describe()} [${it.state}]" }
        when (authorize(request.source, null, confirmation, preview.digest, preview.title, lines, preview.backend!!)) {
            AuthResult.Ok -> Unit
            AuthResult.Changed -> return@serialized ApplyResult.PlanChanged(asPlan)
            AuthResult.Declined -> return@serialized ApplyResult.Declined(asPlan)
            is AuthResult.Refused -> return@serialized ApplyResult.Rejected(asPlan)
        }
        val force = preview.forced
        val backend = backendOf(preview.backend)!!
        val skipped = preview.items.filter { it.state == RestoreState.CONFLICT && it.key.id !in force } +
            preview.items.filter { it.state == RestoreState.UNREADABLE }
        val run = preview.items.filter {
            it.state == RestoreState.READY || it.state == RestoreState.ALREADY_AT_TARGET || (it.state == RestoreState.CONFLICT && it.key.id in force)
        }
        // Mark conflicts on the source entries now so the history shows why they were left alone.
        markSources(preview.items.filter { it in skipped && it.state == RestoreState.CONFLICT }, RestoreStatus.CONFLICT, null)
        if (run.isEmpty()) return@serialized ApplyResult.NothingToDo(asPlan)

        val baselines = Baselines.compute(journal.all())
        val entries = run.map { it ->
            JournalEntry(
                key = it.key, previous = it.current ?: Snapshot.Absent, proposed = it.target.asProposed(),
                startsBaseline = !request.baseline && it.key.id !in baselines && it.state != RestoreState.ALREADY_AT_TARGET,
                clearsBaseline = request.baseline || baselines[it.key.id]?.let { b -> b.snapshot == it.target && b.operationId == it.sourceOperationId } == true,
                restoresOperationId = it.sourceOperationId,
            )
        }
        var op = Operation(
            id = newId(), kind = OperationKind.RESTORE, source = request.source, label = preview.title, createdAt = clock(),
            backend = preview.backend, deviceFingerprint = device().fingerprint, status = OperationStatus.APPLYING, entries = entries,
            restoresOperationId = request.operationId,
        )
        try {
            journal.save(op)
        } catch (e: IOException) {
            return@serialized ApplyResult.Rejected(asPlan.copy(issues = asPlan.issues + Issue(true, IssueCode.JOURNAL_UNWRITABLE, "The change history cannot be saved, so nothing was changed.")))
        }
        record(AuditEventType.SETTINGS_RESTORED, preview.title, "started via ${preview.backend.label}: " + run.joinToString { it.key.name })
        var failures = 0
        fun persist() { try { journal.save(op) } catch (_: IOException) { } }
        for ((i, item) in run.withIndex()) {
            fun set(f: (JournalEntry) -> JournalEntry) { op = op.copy(entries = op.entries.toMutableList().also { it[i] = f(it[i]) }); persist() }
            if (item.state == RestoreState.ALREADY_AT_TARGET) {
                set { it.copy(phase = EntryPhase.DONE, outcome = EntryOutcome.ALREADY_AT_TARGET, note = "Already had the recorded value.") }
                markSources(listOf(item), RestoreStatus.RESTORED, "Already had the recorded value.")
                continue
            }
            set { it.copy(phase = EntryPhase.WRITE_STARTED) }
            when (val w = writeTarget(backend, item.key, item.target)) {
                is WriteResult.Failed -> {
                    failures++
                    set { it.copy(phase = EntryPhase.DONE, outcome = outcomeOf(w.failure.kind), note = w.failure.message) }
                    markSources(listOf(item), RestoreStatus.RESTORE_FAILED, w.failure.message)
                }
                WriteResult.Done -> {
                    set { it.copy(phase = EntryPhase.DONE, outcome = EntryOutcome.STORED_EFFECT_UNVERIFIED, observedAfterWrite = item.target, effectNote = "Restored and read back exactly.") }
                    markSources(listOf(item), RestoreStatus.RESTORED, "Restored to ${item.target.describe()}.")
                }
            }
        }
        op = op.copy(status = if (failures == 0) OperationStatus.COMPLETED else OperationStatus.FAILED_PARTIAL, finishedAt = clock())
        persist()
        record(AuditEventType.SETTINGS_RESTORED, preview.title, "finished ${op.status}")
        ApplyResult.Finished(op, skipped)
    }

    private fun markSources(items: List<RestoreItem>, status: RestoreStatus, note: String?) {
        for (item in items) {
            val srcId = item.sourceOperationId ?: continue
            val src = journal.load(srcId) ?: continue
            val idx = src.entries.indexOfFirst { it.key.id == item.key.id && it.restoreStatus != RestoreStatus.RESTORED }
            if (idx < 0) continue
            val updated = src.entries.toMutableList().also {
                it[idx] = it[idx].copy(restoreStatus = status, restoreNote = note ?: item.note, restoredAt = clock())
            }
            try { journal.save(src.copy(entries = updated)) } catch (_: IOException) { }
        }
    }

    // ---- recovery --------------------------------------------------------------------------------------------------------------

    /**
     * After a crash or kill, finds operations still marked APPLYING and records what is actually on the phone. It never writes and never replays
     * a change: it only reads, so an interrupted change cannot be applied twice. Returns the operations it updated.
     */
    fun recoverInterrupted(): List<Operation> = serialized({ emptyList() }) {
        val pending = journal.all().filter { it.status == OperationStatus.APPLYING }
        val out = mutableListOf<Operation>()
        for (op in pending) {
            val backend = backendOf(op.backend)
            if (backend == null || backend.status().state == BackendState.UNAVAILABLE) continue
            var changed = op
            val entries = op.entries.map { e ->
                if (e.phase == EntryPhase.DONE || e.phase == EntryPhase.PENDING) {
                    if (e.phase == EntryPhase.PENDING) e.copy(phase = EntryPhase.DONE, outcome = EntryOutcome.NOT_ATTEMPTED, note = "Not started before the interruption.") else e
                } else {
                    val cur = (backend.read(e.key) as? ReadResult.Value)?.snapshot
                    when {
                        cur == null -> e.copy(phase = EntryPhase.DONE, outcome = EntryOutcome.ERROR, note = "Interrupted; the value cannot be read now, so its state is unknown.")
                        cur == e.proposed.asSnapshot() ->
                            e.copy(phase = EntryPhase.DONE, outcome = EntryOutcome.UNCONFIRMED_AFTER_INTERRUPT, observedAfterWrite = cur, note = "Interrupted; the phone has the proposed value but the write was not seen to finish.")
                        cur == e.previous -> e.copy(phase = EntryPhase.DONE, outcome = EntryOutcome.NOT_ATTEMPTED, note = "Interrupted before the value changed.")
                        else -> e.copy(phase = EntryPhase.DONE, outcome = EntryOutcome.ERROR, observedAfterWrite = cur, note = "Interrupted; the value is now ${cur.describe()}, which is neither the old nor the proposed value.")
                    }
                }
            }
            changed = changed.copy(entries = entries, status = OperationStatus.INTERRUPTED, finishedAt = clock(), note = "Recovered after an interruption. Nothing was replayed.")
            try { journal.save(changed) } catch (_: IOException) { continue }
            out += changed
        }
        out
    }

    private fun record(type: AuditEventType, subject: String, detail: String) {
        audit?.record(AuditEvent(type, "system_settings:$subject".take(120), detail.take(400)))
    }
}
