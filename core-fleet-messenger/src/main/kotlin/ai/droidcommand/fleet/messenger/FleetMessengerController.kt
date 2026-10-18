package ai.droidcommand.fleet.messenger

import ai.droidcommand.rootforge.FailureKind
import ai.droidcommand.rootforge.RootForgeResult
import kotlinx.serialization.json.JsonObject

/** How a catalogued EXTERNAL_NODE currently stands relative to pairing + the installed signer. */
enum class NodePairingState {
    /** No candidate package is installed (or visible). */
    NOT_INSTALLED,

    /** Installed, observed cert shown, owner has not paired. Default. */
    NOT_PAIRED,

    /** Paired, and the installed signer is exactly the paired cert. */
    TRUSTED,

    /** Paired, installed, but the current signer is not the paired cert. */
    CERT_MISMATCH,

    /** Paired but not installed / not visible / multi-signer / inspect failed. */
    UNTRUSTED,
}

data class ExternalNodeRow(
    val spec: ExternalNodeSpec,
    val installedPackage: String?,
    val observedCertSha256: String?,
    val observedSignerCount: Int,
    val paired: PairedNode?,
    val state: NodePairingState,
    val trustDetail: String?,
) {
    val canPair: Boolean
        get() = installedPackage != null && observedCertSha256 != null && observedSignerCount == 1

    val canUnpair: Boolean get() = paired != null

    val liveEnabled: Boolean get() = state == NodePairingState.TRUSTED

    val observedCertDisplay: String? get() = observedCertSha256?.let { PairedNode.displayCertSha256(it) }

    val pairedCertDisplay: String? get() = paired?.expectedCertSha256?.let { PairedNode.displayCertSha256(it) }
}

sealed class PairingResult {
    data class Paired(val node: PairedNode) : PairingResult()

    data class Refused(val reason: String) : PairingResult()
}

sealed class LiveCallResult {
    abstract val operation: String

    data class Success(override val operation: String, val payload: JsonObject) : LiveCallResult()

    data class RemoteError(override val operation: String, val message: String, val category: String?) : LiveCallResult()

    data class Timeout(override val operation: String, val message: String) : LiveCallResult()

    data class Untrusted(override val operation: String, val message: String) : LiveCallResult()

    data class Gated(override val operation: String, val message: String) : LiveCallResult()

    data class Failed(override val operation: String, val kind: FailureKind, val message: String) : LiveCallResult()

    /** Fire-and-forget `*.up` ack, then the matching `*.status` poll. */
    data class UpThenStatus(val up: LiveCallResult, val status: LiveCallResult?) : LiveCallResult() {
        override val operation: String get() = up.operation
    }

    /** One-line (or two-line, for up+poll) text the fleet screen shows under the buttons. */
    fun summary(): String = when (this) {
        is Success -> "$operation ok: $payload"
        is RemoteError -> {
            val cat = category?.let { " ($it)" } ?: ""
            "$operation REMOTE_ERROR$cat: $message"
        }
        is Timeout -> "$operation timeout: $message"
        is Untrusted -> "$operation untrusted: $message"
        is Gated -> message
        is Failed -> "$operation ${kind.name}: $message"
        is UpThenStatus -> buildString {
            append(up.summary())
            if (status != null) {
                append('\n')
                append(status.summary())
            }
        }
    }
}

/**
 * JVM-testable pairing + live-status controller. Android UI stays thin: it shows [snapshot]
 * and calls [pair] / [unpair] / the live-op methods. Nothing is contacted until the owner pairs
 * a node (empty store is the shipped default). Write/control ops other than the node's documented
 * `*.up` are refused here so they stay behind the existing approval / Kali / pentest gates.
 */
class FleetMessengerController(
    private val store: MutablePairedNodeStore,
    private val resolver: PackageIdentityResolver,
    private val gateway: PairedNodeGateway,
    private val catalog: List<ExternalNodeSpec> = ExternalNodeCatalog.all,
    private val trust: NodeTrustVerifier = NodeTrustVerifier(resolver),
    private val timeoutMs: Int = MessengerWire.DEFAULT_TIMEOUT_MS,
) {
    fun snapshot(): List<ExternalNodeRow> = catalog.map { spec -> row(spec) }

    fun row(catalogId: String): ExternalNodeRow? = catalog.find { it.id == catalogId }?.let { row(it) }

    fun pair(catalogId: String): PairingResult {
        val spec = catalog.find { it.id == catalogId }
            ?: return PairingResult.Refused("unknown EXTERNAL_NODE $catalogId")
        val installed = resolveInstalled(spec)
            ?: return PairingResult.Refused("${spec.displayName} is not installed (or not visible to this app)")
        val (packageName, identity) = installed
        val signers = identity.signingCertSha256.map { PairedNode.normalizeCertSha256(it) }.toSet()
        val cert = when {
            signers.isEmpty() -> return PairingResult.Refused("$packageName reports no signing certificate")
            signers.size > 1 -> return PairingResult.Refused(
                "$packageName has multiple signers; pair only a single-signer APK after confirming the cert",
            )
            else -> signers.single()
        }
        // Re-pair replaces the previous pairing for this catalog member / package. The cert is
        // whatever is installed *now* — never an older stored value, never a hardcoded one.
        store.unpair(spec.nodeId)
        store.findByPackage(packageName)?.let { store.unpair(it.nodeId) }
        val node = PairedNode.of(packageName, spec.nodeId, cert)
        return runCatching {
            store.pair(node)
            PairingResult.Paired(node)
        }.getOrElse { PairingResult.Refused(it.message ?: "could not store pairing") }
    }

    fun unpair(catalogId: String): Boolean {
        val spec = catalog.find { it.id == catalogId } ?: return false
        val byId = store.unpair(spec.nodeId)
        val extras = spec.packages.map { pkg -> store.findByPackage(pkg)?.nodeId }.filterNotNull().toSet()
        val extraRemoved = extras.map { store.unpair(it) }.any { it }
        return byId || extraRemoved
    }

    fun refreshCapabilities(catalogId: String): LiveCallResult = runReadOnly(catalogId) { it.capabilitiesOperation }

    fun refreshStatus(catalogId: String): LiveCallResult = runReadOnly(catalogId) { it.statusOperation }

    /**
     * Fire-and-forget `*.up` (empty `{}`, ACK), then poll `*.status`. Refused when the catalog
     * member has no up op. Does not bypass Kali/pentest/DependencyGuard — those gates are a
     * different path; this only talks to a paired EXTERNAL_NODE.
     */
    fun bringUpAndPoll(catalogId: String): LiveCallResult {
        val spec = catalog.find { it.id == catalogId }
            ?: return LiveCallResult.Gated(catalogId, "unknown EXTERNAL_NODE $catalogId")
        val upOp = spec.upOperation
            ?: return LiveCallResult.Gated(
                spec.statusOperation,
                "${spec.displayName} has no *.up op; this screen only runs ${spec.capabilitiesOperation} / ${spec.statusOperation}",
            )
        prepareLive(spec, upOp)?.let { return it }
        val up = mapResult(upOp, gateway.fireAndForget(spec.nodeId, upOp, timeoutMs))
        if (up !is LiveCallResult.Success) return LiveCallResult.UpThenStatus(up, status = null)
        val statusOp = NodeOperations.statusOperation(upOp)
        val status = mapResult(statusOp, gateway.pollStatus(spec.nodeId, upOp, timeoutMs))
        return LiveCallResult.UpThenStatus(up, status)
    }

    fun runReadOnly(catalogId: String, operation: String): LiveCallResult {
        val spec = catalog.find { it.id == catalogId }
            ?: return LiveCallResult.Gated(operation, "unknown EXTERNAL_NODE $catalogId")
        if (!spec.isReadOnly(operation)) {
            return LiveCallResult.Gated(
                operation,
                "write/control op $operation stays behind the normal approval gates; " +
                    "this screen only runs ${spec.capabilitiesOperation} and ${spec.statusOperation}",
            )
        }
        return callAllowed(spec, operation)
    }

    private fun runReadOnly(catalogId: String, op: (ExternalNodeSpec) -> String): LiveCallResult {
        val spec = catalog.find { it.id == catalogId }
            ?: return LiveCallResult.Gated(catalogId, "unknown EXTERNAL_NODE $catalogId")
        return callAllowed(spec, op(spec))
    }

    private fun callAllowed(spec: ExternalNodeSpec, operation: String): LiveCallResult {
        prepareLive(spec, operation)?.let { return it }
        return mapResult(operation, gateway.call(spec.nodeId, operation, NodeOperations.EMPTY_PARAMETERS, timeoutMs))
    }

    /** Null means go ahead and call the gateway. */
    private fun prepareLive(spec: ExternalNodeSpec, operation: String): LiveCallResult? {
        if (!spec.isLiveAllowed(operation)) {
            return LiveCallResult.Gated(
                operation,
                "write/control op $operation stays behind the normal approval gates",
            )
        }
        val paired = store.find(spec.nodeId)
            ?: return untrustedNotPaired(spec, operation)
        return when (val decision = trust.verify(paired)) {
            is TrustDecision.Refused -> LiveCallResult.Untrusted(operation, decision.reason)
            is TrustDecision.Trusted -> null
        }
    }

    private fun untrustedNotPaired(spec: ExternalNodeSpec, operation: String) =
        LiveCallResult.Untrusted(operation, "${spec.displayName} is not paired")

    private fun mapResult(operation: String, result: BridgeResult<JsonObject>): LiveCallResult = when (result) {
        is RootForgeResult.Success -> LiveCallResult.Success(operation, result.value)
        is RootForgeResult.Failure -> when {
            result.kind == FailureKind.UNTRUSTED_NODE -> LiveCallResult.Untrusted(operation, result.message)
            result.kind == FailureKind.REMOTE_ERROR -> LiveCallResult.RemoteError(operation, result.message, result.remoteCategory)
            result.kind == FailureKind.TRANSPORT && result.message.contains("no reply within") ->
                LiveCallResult.Timeout(operation, result.message)
            else -> LiveCallResult.Failed(operation, result.kind, result.message)
        }
    }

    private fun row(spec: ExternalNodeSpec): ExternalNodeRow {
        val paired = store.find(spec.nodeId)
            ?: spec.packages.firstNotNullOfOrNull { store.findByPackage(it) }
        val installed = resolveInstalled(spec)
        val observedCerts = installed?.second?.signingCertSha256.orEmpty().map { PairedNode.normalizeCertSha256(it) }.toSet()
        val observedCert = observedCerts.singleOrNull()
        val (state, detail) = pairingState(spec, paired, installed, observedCerts)
        return ExternalNodeRow(
            spec = spec,
            installedPackage = installed?.first,
            observedCertSha256 = observedCert,
            observedSignerCount = observedCerts.size,
            paired = paired,
            state = state,
            trustDetail = detail,
        )
    }

    private fun pairingState(
        spec: ExternalNodeSpec,
        paired: PairedNode?,
        installed: Pair<String, PackageIdentity>?,
        observedCerts: Set<String>,
    ): Pair<NodePairingState, String?> {
        if (paired == null) {
            return if (installed == null) {
                NodePairingState.NOT_INSTALLED to "${spec.displayName} is not installed (or not visible to this app)"
            } else {
                NodePairingState.NOT_PAIRED to null
            }
        }
        return when (val decision = trust.verify(paired)) {
            is TrustDecision.Trusted -> NodePairingState.TRUSTED to null
            is TrustDecision.Refused -> {
                val mismatch = installed != null &&
                    observedCerts.size == 1 &&
                    observedCerts.single() != paired.expectedCertSha256
                val state = if (mismatch) NodePairingState.CERT_MISMATCH else NodePairingState.UNTRUSTED
                state to decision.reason
            }
        }
    }

    /** First installed candidate: the already-paired package if still present, else production then aliases. */
    private fun resolveInstalled(spec: ExternalNodeSpec): Pair<String, PackageIdentity>? {
        val pairedPkg = store.find(spec.nodeId)?.packageName
        val order = buildList {
            if (pairedPkg != null) add(pairedPkg)
            addAll(spec.packages)
        }.distinct()
        for (pkg in order) {
            val identity = runCatching { resolver.resolve(pkg) }.getOrNull() ?: continue
            return pkg to identity
        }
        return null
    }
}
