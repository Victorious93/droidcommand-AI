package ai.droidcommand.app.fleetworker

import ai.droidcommand.activity.ActivityActor
import ai.droidcommand.activity.ActivityLog
import ai.droidcommand.activity.ActivityRun
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.fleet.worker.Backoff
import ai.droidcommand.fleet.worker.CapabilityProbe
import ai.droidcommand.fleet.worker.FleetApprovalPrompt
import ai.droidcommand.fleet.worker.FleetJobEvent
import ai.droidcommand.fleet.worker.FleetOperation
import ai.droidcommand.fleet.worker.FleetTransport
import ai.droidcommand.fleet.worker.FleetTransportException
import ai.droidcommand.fleet.worker.FleetWorker
import ai.droidcommand.fleet.worker.HashFilesOperation
import ai.droidcommand.fleet.worker.PairingPayload
import ai.droidcommand.fleet.worker.WorkerConfig
import ai.droidcommand.fleet.worker.WorkerIdentity
import ai.droidcommand.fleet.worker.WorkerStateStore
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.AuditLog
import ai.droidcommand.security.BatchApprovalPrompt
import ai.droidcommand.security.BatchApprovals
import ai.droidcommand.security.BatchOfferHandler
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import ai.droidcommand.security.ToolBatchEligibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What the Settings screen shows. Everything here is derived from the worker's own state and the owner's two switches. */
data class FleetWorkerStatus(
    val workerEnabled: Boolean = false,
    val batchApprovalEnabled: Boolean = false,
    val paired: Boolean = false,
    val deviceId: String? = null,
    val coordinatorUrl: String? = null,
    val revoked: Boolean = false,
    /** True while this phone's connection loop is actually running (the foreground service is up). */
    val connected: Boolean = false,
    val problem: String? = null,
    val runningJobs: List<String> = emptyList(),
    val liveBatches: Int = 0,
) {
    /** One honest line for the Settings screen. A problem always wins over a friendlier state. */
    fun summary(): String = when {
        problem != null -> problem
        connected -> "Connected. ${if (runningJobs.isEmpty()) "No job running." else "Running: ${runningJobs.joinToString()}"}"
        workerEnabled && paired -> "On; waiting to connect."
        revoked -> "Revoked by the coordinator. Pair again to use this phone."
        paired -> "Off."
        else -> "Not paired."
    }
}

sealed class PairOutcome {
    data class Paired(val deviceId: String) : PairOutcome()

    data class Failed(val why: String) : PairOutcome()
}

/**
 * Everything the phone-side fleet worker needs, wired together and free of Android types so it is JVM-testable: the worker
 * itself ([FleetWorker]), its own `SecureToolExecutor` (so the phone's policy, approvals, grants and audit stay authoritative),
 * the optional batch approval layer, and the Activity feed. **Nothing here runs unless the owner turned the worker on**:
 * [runLoop] returns at once while the switch is off or the phone is unpaired.
 *
 * Fleet jobs run through a dedicated registry holding only the worker's operations, never the app's main one, and only as
 * `Initiator.REMOTE`, so nothing a local planner or helper bot does can reach them and they cannot reach anything else.
 */
class FleetWorkerEngine(
    private val settings: FleetWorkerSettings,
    private val store: WorkerStateStore,
    identity: WorkerIdentity,
    probe: CapabilityProbe,
    private val transportFor: (url: String, spkiSha256: String) -> FleetTransport,
    appPrompt: ApprovalPrompt,
    policy: SecurityPolicy,
    auditLog: AuditLog?,
    private val activityLog: ActivityLog,
    offers: BatchOfferHandler,
    workRoot: File,
    deviceName: String,
    appVersion: String,
    operations: List<FleetOperation> = listOf(HashFilesOperation()),
) {
    private val actor = ActivityActor("fleet:worker", "Fleet jobs on this phone")
    private val runs = ConcurrentHashMap<String, ActivityRun>()
    private val _status = MutableStateFlow(FleetWorkerStatus())
    val status: StateFlow<FleetWorkerStatus> = _status.asStateFlow()

    @Volatile private var connected = false

    @Volatile private var problem: String? = null

    private val registry = ToolRegistry().also { r -> operations.forEach { r.register(it.tool()) } }
    private val batchableTools = operations.filter { it.batchable }.map { it.toolName }.toSet()

    /** The owner's batch approval. Its switch is read on every use, so it can never outlive the setting. */
    val approvals = BatchApprovals(
        ToolBatchEligibility(registry) { batchableTools },
        enabled = { settings.batchApprovalEnabled.value },
        auditLog = auditLog,
    )

    private lateinit var worker: FleetWorker

    init {
        val fleetPrompt = FleetApprovalPrompt(appPrompt, { worker.approvalPending(it) }, { worker.approvalResolved(it) })
        val prompt = BatchApprovalPrompt(fleetPrompt, approvals, offers = offers, onCovered = { worker.approvalCoveredByBatch() })
        val machine = AgentStateMachine()
        val runner = SecureToolExecutor(registry, ToolExecutor(registry, machine), machine, SecurityPolicyEnforcer(policy), prompt, auditLog = auditLog)
        worker = FleetWorker(
            identity, StoredCoordinatorTransport(store, transportFor), probe, runner, store, operations, workRoot,
            WorkerConfig(deviceName, appVersion),
            batch = approvals,
            onJobEvent = ::onJobEvent,
        )
        worker.setEnabled(settings.workerEnabled.value)
        refresh()
    }

    fun shouldRun(): Boolean = FleetWorkerPolicy.shouldRun(settings.workerEnabled.value, store.load())

    /** Re-reads the owner's switches. Turning the worker off cancels running jobs and ends every live batch. */
    fun applySettings() {
        worker.setEnabled(settings.workerEnabled.value)
        if (!settings.batchApprovalEnabled.value) approvals.revokeAll("Batch approval was switched off by the owner")
        refresh()
    }

    /** Blocking (network): call off the main thread. The owner has already confirmed the coordinator's fingerprint. */
    fun pair(payload: PairingPayload): PairOutcome {
        val state = store.load()
        if (state.deviceId != null && !state.revoked) return PairOutcome.Failed("This phone is already paired. Unpair it first.")
        return try {
            when (val r = worker.enroll(payload, transportFor(payload.coordinatorUrl, payload.spkiSha256))) {
                is FleetWorker.PairingResult.Paired -> {
                    problem = null
                    PairOutcome.Paired(r.deviceId)
                }
                is FleetWorker.PairingResult.Mismatch -> PairOutcome.Failed(r.why)
            }
        } catch (e: FleetTransportException) {
            PairOutcome.Failed(friendly(e))
        } catch (e: Exception) {
            PairOutcome.Failed(e.message?.take(200) ?: "Could not reach the coordinator.")
        } finally {
            refresh()
        }
    }

    /** Forgets the coordinator on this phone (cancels jobs, ends batches, wipes the credential) and switches the worker off. */
    fun unpair() {
        worker.unpair()
        settings.setWorkerEnabled(false)
        problem = null
        refresh()
    }

    /**
     * The connection loop. Returns when [stop] says so, the owner turns the worker off, the phone is unpaired or revoked, or the
     * coordinator rejects its credential (the owner must pair again; it never hammers). Unexpected errors are shown and retried
     * with a bounded backoff.
     */
    fun runLoop(stop: () -> Boolean, sleeper: (Long) -> Unit = { Thread.sleep(it) }) {
        if (!shouldRun()) return
        connected = true
        problem = null
        refresh()
        val backoff = Backoff()
        try {
            while (!stop() && shouldRun()) {
                try {
                    worker.runLoop({ stop() || !shouldRun() }, sleeper)
                } catch (e: Exception) {
                    problem = "Connection problem: ${e.message?.take(120) ?: e.javaClass.simpleName}"
                    refresh()
                    if (!stop() && shouldRun()) sleeper(backoff.next())
                    continue
                }
                if (stop() || !shouldRun()) break
                // worker.runLoop returns on its own only when the coordinator rejected this phone's credential.
                problem = "The coordinator rejected this phone's credential. Pair again."
                break
            }
        } finally {
            connected = false
            val s = store.load()
            if (s.revoked) problem = "The coordinator revoked this phone. Pair again to use it."
            refresh()
        }
    }

    /** Shows a problem on the Settings screen (for instance the service could not be started). Cleared by the next successful run. */
    fun reportProblem(text: String) {
        problem = text
        refresh()
    }

    fun refresh() {
        val s = store.load()
        _status.value = FleetWorkerStatus(
            workerEnabled = settings.workerEnabled.value,
            batchApprovalEnabled = settings.batchApprovalEnabled.value,
            paired = s.deviceId != null && s.credential != null && !s.revoked,
            deviceId = s.deviceId,
            coordinatorUrl = s.coordinatorUrl,
            revoked = s.revoked,
            connected = connected,
            problem = problem,
            runningJobs = if (::worker.isInitialized) worker.runningJobs() else emptyList(),
            liveBatches = approvals.active().size,
        )
    }

    private fun onJobEvent(e: FleetJobEvent) {
        val key = "${e.jobId}#${e.attempt}"
        when (e.phase) {
            FleetJobEvent.Phase.STARTED -> runs[key] = ActivityRun.start(actor, "Fleet job: ${e.op}", activityLog)
            FleetJobEvent.Phase.WAITING_FOR_OWNER -> runs[key]?.awaitingOwner("Waiting for your approval", e.op)
            FleetJobEvent.Phase.OWNER_ANSWERED -> runs[key]?.ownerDecided(e.detail == "approved")
            FleetJobEvent.Phase.COVERED_BY_BATCH -> runs[key]?.note("Covered by your batch approval")
            FleetJobEvent.Phase.COMPLETED -> runs.remove(key)?.finish("Done: ${e.op}")
            FleetJobEvent.Phase.FAILED -> runs.remove(key)?.fail(e.detail ?: "Failed")
            FleetJobEvent.Phase.STOPPED -> runs.remove(key)?.cancel("Stopped")
        }
        refresh()
    }

    private fun friendly(e: FleetTransportException): String = when {
        e.isRevoked -> "The coordinator refused this phone."
        e.isUnauthorized -> "The coordinator did not accept the pairing secret. It may have expired; make a new one and try again."
        else -> e.message?.take(200) ?: "Could not reach the coordinator."
    }
}

/** A transport that always talks to the coordinator this phone was paired with (address and key pin read from the store). */
internal class StoredCoordinatorTransport(
    private val store: WorkerStateStore,
    private val factory: (url: String, spkiSha256: String) -> FleetTransport,
) : FleetTransport {
    private var cachedFor: Pair<String, String>? = null
    private var cached: FleetTransport? = null

    @Synchronized
    private fun current(): FleetTransport {
        val s = store.load()
        val url = s.coordinatorUrl
        val spki = s.coordinatorSpkiSha256
        if (url == null || spki == null) throw FleetTransportException(0, null, "This phone is not paired with a coordinator.")
        val key = url to spki
        if (cachedFor != key) {
            cached = factory(url, spki)
            cachedFor = key
        }
        return cached!!
    }

    override fun enroll(envelope: kotlinx.serialization.json.JsonObject) = current().enroll(envelope)

    override fun exchange(credential: String, body: kotlinx.serialization.json.JsonObject) = current().exchange(credential, body)

    override fun upload(credential: String, jobId: String, attempt: Int, name: String, sha256: String, bytes: ByteArray) =
        current().upload(credential, jobId, attempt, name, sha256, bytes)

    override fun download(credential: String, artifactId: String) = current().download(credential, artifactId)

    override fun rotate(credential: String) = current().rotate(credential)
}
