@file:Suppress("ktlint:standard:property-naming")

package ai.droidcommand.systemsettings

import ai.droidcommand.security.InMemoryAuditLog

/** Thrown from a fake write to simulate the process dying mid-operation. */
class SimulatedCrash : Error("process died")

/**
 * An in-memory SettingsProvider. Rows are keyed by [SettingKey.id], so absent and empty-string rows are different, and a different user is a
 * different row. Every fault a real backend can show is injectable.
 */
class FakeBackend(
    override val kind: AccessKind,
    var state: BackendState = BackendState.READY,
    var writable: Set<SettingsTable> = SettingsTable.entries.toSet(),
    var crossUser: Boolean = true,
    var detail: String = "fake",
) : SettingsBackend {
    val rows = LinkedHashMap<String, String>()
    val writeLog = mutableListOf<String>()
    val deleteLog = mutableListOf<String>()

    /** Return this from every write/delete (for example a revoked permission). */
    var failWrites: BackendFailure? = null
    var failReads: BackendFailure? = null
    var failWriteOnKey: Map<String, BackendFailure> = emptyMap()
    var failWriteAfterN: Int? = null
    var unreadable: Set<String> = emptySet()

    /** Write reports Done but changes nothing. */
    var swallow: Set<String> = emptySet()

    /** Store a different value than asked (a provider that normalizes or clamps). */
    var rewrite: (SettingKey, String) -> String = { _, v -> v }
    var beforeWrite: (SettingKey, String) -> Unit = { _, _ -> }
    var afterWrite: (SettingKey, String) -> Unit = { _, _ -> }
    private var writeCount = 0

    fun put(table: SettingsTable, name: String, value: String, user: Int = 0) {
        rows[SettingKey.of(table, name, user).id] = value
    }
    fun get(table: SettingsTable, name: String, user: Int = 0): String? = rows[SettingKey.of(table, name, user).id]

    override fun status() = BackendStatus(
        kind, state, detail,
        readableTables = if (state == BackendState.UNAVAILABLE) emptySet() else SettingsTable.entries.toSet(),
        writableTables = if (state == BackendState.UNAVAILABLE) emptySet() else writable,
        canListTables = SettingsTable.entries.toSet(),
        crossUser = crossUser,
    )

    override fun read(key: SettingKey): ReadResult {
        failReads?.let { return ReadResult.Failed(it) }
        if (key.id in unreadable) return ReadResult.Failed(BackendFailure(FailureKind.UNREADABLE, "not readable"))
        val v = rows[key.id]
        return ReadResult.Value(if (v == null) Snapshot.Absent else Snapshot.Present(v))
    }

    override fun list(table: SettingsTable, userId: Int): ListResult =
        ListResult.Entries(rows.filterKeys { it.startsWith("${table.wire}:$userId:") }.mapKeys { it.key.substringAfter("${table.wire}:$userId:") })

    override fun write(key: SettingKey, value: String): WriteResult {
        failWrites?.let { return WriteResult.Failed(it) }
        failWriteOnKey[key.id]?.let { return WriteResult.Failed(it) }
        writeCount++
        failWriteAfterN?.let { if (writeCount > it) return WriteResult.Failed(BackendFailure(FailureKind.ERROR, "injected failure on write #$writeCount")) }
        beforeWrite(key, value)
        writeLog += "${key.id}=$value"
        if (key.id !in swallow) rows[key.id] = rewrite(key, value)
        afterWrite(key, value)
        return WriteResult.Done
    }

    override fun delete(key: SettingKey): WriteResult {
        failWrites?.let { return WriteResult.Failed(it) }
        deleteLog += key.id
        rows.remove(key.id)
        return WriteResult.Done
    }
}

/** A default phone: Android 14, user 0, a display with 60/90/120 Hz. */
fun testDevice(
    sdk: Int = 34,
    fingerprint: String = "fp-1",
    rates: List<Float> = listOf(60f, 90f, 120f),
    user: Int = 0,
) = DeviceContext(sdk, "14", "Acme", "Pixelish", fingerprint, user, rates)

class Harness(
    val standard: FakeBackend = FakeBackend(AccessKind.STANDARD),
    val shizuku: FakeBackend = FakeBackend(AccessKind.SHIZUKU, state = BackendState.UNAVAILABLE),
    val root: FakeBackend = FakeBackend(AccessKind.ROOT, state = BackendState.UNAVAILABLE),
    var device: DeviceContext = testDevice(),
    var config: AccessConfig = AccessConfig(),
    val journal: InMemoryOperationJournal = InMemoryOperationJournal(),
    var approve: Boolean = true,
    withRemote: Boolean = false,
    var remoteDecision: RemoteDecision = RemoteDecision.Allowed,
    val verifiers: List<EffectVerifier> = emptyList(),
    lockWaitMs: Long = 2_000,
) {
    val approvals = mutableListOf<List<String>>()
    val audit = InMemoryAuditLog()
    private var tick = 1_000L
    private var idN = 0
    val engine: SystemSettingsEngine = SystemSettingsEngine(
        backends = listOf(standard, shizuku, root),
        journal = journal,
        device = { device },
        access = { config },
        approver = { _, _, lines, _ ->
            approvals += lines
            approve
        },
        remote = if (withRemote) RemoteSettingsAuthorizer { remoteDecision } else null,
        verifiers = verifiers,
        audit = audit,
        clock = { tick++ },
        newId = { "op-%08d".format(++idN) },
        lockWaitMs = lockWaitMs,
    )

    fun key(table: SettingsTable, name: String, user: Int = device.userId) = SettingKey.of(table, name, user)
    fun putReq(label: String, source: RequestSource, vararg pairs: Triple<SettingsTable, String, String>, profileId: String? = null, manual: Set<String> = emptySet(), ack: Set<String> = emptySet()) =
        ChangeRequest(label, pairs.map { Change(key(it.first, it.second), Proposed.Put(it.third)) }, source, profileId, manual, ack)

    /** Previews then applies as the owner in the UI. */
    fun uiApply(req: ChangeRequest): ApplyResult {
        val plan = engine.plan(req)
        return engine.apply(req, Confirmation.UserConfirmed(plan.digest))
    }
}

val G = SettingsTable.GLOBAL
val S = SettingsTable.SYSTEM
val SEC = SettingsTable.SECURE
