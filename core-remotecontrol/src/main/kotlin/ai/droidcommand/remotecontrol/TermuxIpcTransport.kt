package ai.droidcommand.remotecontrol

import ai.droidcommand.rootforge.BRIDGE_PROTOCOL_MAJOR
import ai.droidcommand.rootforge.BridgeErrorBody
import ai.droidcommand.rootforge.BridgeResponse
import ai.droidcommand.security.FleetIntegrationClass
import ai.droidcommand.termux.RunCommandContract
import ai.droidcommand.termux.RunCommandTarget
import ai.droidcommand.termux.RunCommandTargets
import ai.droidcommand.termux.VictorSuiteRepackageFlag

/**
 * Controller-side TERMUX_IPC sender + response parser for VictorSuite.
 * Live default is still `com.termux` / `com.termux.RUN_COMMAND`. The
 * pending `com.victorious.suite` identity is behind
 * [VictorSuiteRepackageFlag] / [useVictorSuiteRepackage] — off until FO
 * go-signal + bootstrap rebuild (VictorSuite PR #51, draft).
 *
 * Builds a `RUN_COMMAND` extras map carrying the frozen
 * [FleetRemoteEnvelope] JSON as [FleetRemoteEnvelope.TERMUX_JSON_EXTRA],
 * and parses [FleetRemoteEnvelope.TERMUX_RESPONSE_EXTRA] back into
 * [BridgeResponse] (the frozen result type). Those two extras stay
 * `ai.droidcommand.*` either way.
 *
 * Additive and non-executing: the default [TermuxIpcSender] never
 * broadcasts. Destructive ops and `config.import` apply always return
 * [RemoteErrorCategory.CONFIRMATION_REQUIRED] and are not sent.
 * [FleetRemoteEnvelope.OP_NODE_STATUS_GET] is never confirmation-gated.
 * External control stays OFF by default. Freshness follows frozen §2:
 * reject expired when `now > timestampMs + ttlMs`; reject too-far-future
 * when `timestampMs > now + 120000`; past validity is TTL-only.
 */
data class TermuxIpcRequest(
    val action: String = FleetRemoteEnvelope.TERMUX_RUN_COMMAND_ACTION,
    val packageName: String = FleetRemoteEnvelope.TERMUX_PACKAGE,
    val component: String = "$packageName/${RunCommandTargets.SERVICE_CLASS}",
    val permission: String = "com.termux.permission.RUN_COMMAND",
    val extrasPrefix: String = "com.termux.",
    val envelopeJson: String,
    val extras: Map<String, String>,
) {
    init {
        require(envelopeJson.isNotBlank()) { "envelopeJson is required" }
        require(extras[FleetRemoteEnvelope.TERMUX_JSON_EXTRA] == envelopeJson) {
            "extras must carry ${FleetRemoteEnvelope.TERMUX_JSON_EXTRA}"
        }
    }
}

sealed class TermuxIpcResult {
    data class Ready(val request: TermuxIpcRequest) : TermuxIpcResult()
    data class Accepted(val response: BridgeResponse) : TermuxIpcResult()
    data class Rejected(
        val category: RemoteErrorCategory,
        val reason: String = category.wire,
        val response: BridgeResponse? = null,
    ) : TermuxIpcResult() {
        fun asBridgeResponse(
            requestId: String,
            nodeId: String = VictorSuiteRegistry.PACKAGE,
        ): BridgeResponse = response ?: BridgeResponse(
            protocolMajor = BRIDGE_PROTOCOL_MAJOR,
            requestId = requestId,
            nodeId = nodeId,
            ok = false,
            error = BridgeErrorBody(category.wire, reason),
        )
    }
}

fun interface TermuxIpcSender {
    fun send(request: TermuxIpcRequest): TermuxIpcResult
}

/**
 * Honest controller-side stub. Never broadcasts `RUN_COMMAND` and never
 * executes a privileged action.
 */
object UnwiredTermuxIpcSender : TermuxIpcSender {
    override fun send(request: TermuxIpcRequest): TermuxIpcResult =
        TermuxIpcResult.Rejected(
            RemoteErrorCategory.CAPABILITY_OFF,
            "TERMUX_IPC sender is not wired. No RUN_COMMAND broadcast is sent. PENDING — not device-tested.",
        )
}

class TermuxIpcTransport(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val externalControlEnabled: Boolean = false,
    private val sender: TermuxIpcSender = UnwiredTermuxIpcSender,
    private val useVictorSuiteRepackage: Boolean = VictorSuiteRepackageFlag.isEnabled(),
) {
    private val runCommandTarget: RunCommandTarget = RunCommandContract.target(useVictorSuiteRepackage)

    fun buildRequest(envelope: FleetRemoteEnvelope): TermuxIpcRequest {
        val json = TermuxIpcRemoteBinding.encodeBlob(envelope)
        return TermuxIpcRequest(
            action = runCommandTarget.action,
            packageName = runCommandTarget.packageName,
            component = runCommandTarget.component,
            permission = runCommandTarget.permission,
            extrasPrefix = runCommandTarget.extrasPrefix,
            envelopeJson = json,
            extras = mapOf(FleetRemoteEnvelope.TERMUX_JSON_EXTRA to json),
        )
    }

    fun parseResponse(raw: String): BridgeResponse = TermuxIpcRemoteBinding.parseResponse(raw)

    /**
     * Controller-side submit. Gates first; a rejected envelope is never
     * handed to [sender]. The default sender is [UnwiredTermuxIpcSender].
     */
    fun submit(
        envelope: FleetRemoteEnvelope,
        operation: String = resolveOperation(envelope),
    ): TermuxIpcResult {
        gate(envelope, operation)?.let { return it }
        return sender.send(buildRequest(envelope))
    }

    /**
     * Build the extras without sending. Still applies freshness and
     * confirmation gates so a caller cannot encode a live destructive
     * apply by skipping [submit].
     */
    fun prepare(envelope: FleetRemoteEnvelope, operation: String = resolveOperation(envelope)): TermuxIpcResult {
        gate(envelope, operation)?.let { return it }
        return TermuxIpcResult.Ready(buildRequest(envelope))
    }

    fun gate(envelope: FleetRemoteEnvelope, operation: String = resolveOperation(envelope)): TermuxIpcResult.Rejected? {
        freshness(envelope)?.let { return reject(it, envelope) }
        val op = operation.ifBlank { resolveOperation(envelope) }
        if (op !in VictorSuiteRegistry.OPS) {
            return reject(RemoteErrorCategory.UNSUPPORTED_OP, envelope, "unsupported op '$op'")
        }
        if (op != FleetRemoteEnvelope.OP_NODE_STATUS_GET) {
            if (isConfigImportApply(envelope, op)) {
                return reject(
                    RemoteErrorCategory.CONFIRMATION_REQUIRED,
                    envelope,
                    "config.import apply requires on-device confirm and is not executed",
                )
            }
            if (isDestructive(envelope, op)) {
                return reject(
                    RemoteErrorCategory.CONFIRMATION_REQUIRED,
                    envelope,
                    "destructive op requires on-device confirm and is not executed",
                )
            }
        }
        if (!externalControlEnabled) {
            return reject(RemoteErrorCategory.EXTERNAL_CONTROL_DISABLED, envelope)
        }
        return null
    }

    private fun freshness(envelope: FleetRemoteEnvelope): RemoteErrorCategory? {
        val now = nowMs()
        val ttl = envelope.ttlMs.coerceIn(1L, FleetRemoteEnvelope.MAX_TTL_MS)
        if (now > envelope.timestampMs + ttl) return RemoteErrorCategory.EXPIRED
        if (envelope.timestampMs > now + FleetRemoteEnvelope.FUTURE_SKEW_MS) {
            return RemoteErrorCategory.EXPIRED
        }
        return null
    }

    private fun reject(
        category: RemoteErrorCategory,
        envelope: FleetRemoteEnvelope,
        reason: String = category.wire,
    ): TermuxIpcResult.Rejected = TermuxIpcResult.Rejected(
        category = category,
        reason = reason,
        response = BridgeResponse(
            protocolMajor = BRIDGE_PROTOCOL_MAJOR,
            requestId = envelope.requestId,
            nodeId = envelope.targetNodeRef,
            ok = false,
            error = BridgeErrorBody(category.wire, reason),
        ),
    )

    companion object {
        fun resolveOperation(envelope: FleetRemoteEnvelope): String {
            val fromAction = envelope.actionRef
            if (fromAction in VictorSuiteRegistry.OPS) return fromAction
            if (envelope.steps != null) return FleetRemoteEnvelope.OP_MACRO_RUN
            if (envelope.op != null || (envelope.macroId != null && envelope.stepIndex != null)) {
                return FleetRemoteEnvelope.OP_MACRO_STEP
            }
            return FleetRemoteEnvelope.OP_REMOTE_COMMAND
        }

        fun isConfigImportApply(envelope: FleetRemoteEnvelope, operation: String): Boolean {
            if (operation != FleetRemoteEnvelope.OP_CONFIG_IMPORT) return false
            if (envelope.dryRun == true) return false
            if (envelope.args["dryRun"] == "true") return false
            return true
        }

        fun isDestructive(envelope: FleetRemoteEnvelope, operation: String): Boolean {
            if (operation == FleetRemoteEnvelope.OP_NODE_STATUS_GET) return false
            if (operation == FleetRemoteEnvelope.OP_CAPABILITIES_GET) return false
            if (operation == FleetRemoteEnvelope.OP_CONFIG_EXPORT) return false
            if (looksLikeDestructiveRef(envelope.actionRef)) return true
            if (operation == FleetRemoteEnvelope.OP_MACRO_STEP && looksLikeDestructiveRef(envelope.stepOp())) {
                return true
            }
            if (operation == FleetRemoteEnvelope.OP_MACRO_RUN) {
                val steps = envelope.steps.orEmpty()
                if (steps.any { looksLikeDestructiveRef(it.op) }) return true
            }
            return envelope.destructive
        }

        fun looksLikeDestructiveRef(actionRef: String): Boolean {
            if (ActionRefClassifier.isDestructive(actionRef)) return true
            val lowered = actionRef.lowercase()
            return lowered.contains("shell") ||
                lowered.contains("exec") ||
                lowered == "su" ||
                lowered.contains(".su") ||
                lowered.contains("reboot") ||
                lowered.contains("shutdown") ||
                lowered.contains("kill") ||
                lowered.contains("delete") ||
                lowered == "rm" ||
                lowered.contains(".rm") ||
                lowered.contains("factory_reset") ||
                lowered.contains("run_command")
        }
    }
}

/**
 * VictorSuite TERMUX_IPC catalogue. Live package stays `com.termux`
 * until [VictorSuiteRepackageFlag] is flipped (VictorSuite PR #51 draft,
 * PENDING-REPACKAGE). Envelope v1, BRIDGE_PROTOCOL 1.1. Node PR #48
 * (`a6f4e2a`) merged; this controller slice is not device-tested.
 */
object VictorSuiteRegistry {
    const val ID = "victorsuite"
    const val DISPLAY_NAME = "VictorSuite"
    const val PACKAGE = FleetRemoteEnvelope.TERMUX_PACKAGE
    const val ACTION = FleetRemoteEnvelope.TERMUX_RUN_COMMAND_ACTION
    const val STATUS = "PENDING-REPACKAGE"
    const val NODE_SOURCE = "VictorSuite PR #48 a6f4e2a; PR #51 draft pending Don"

    val OPS: Set<String> = setOf(
        FleetRemoteEnvelope.OP_CAPABILITIES_GET,
        FleetRemoteEnvelope.OP_REMOTE_COMMAND,
        FleetRemoteEnvelope.OP_MACRO_STEP,
        FleetRemoteEnvelope.OP_MACRO_RUN,
        FleetRemoteEnvelope.OP_NODE_STATUS_GET,
        FleetRemoteEnvelope.OP_CONFIG_EXPORT,
        FleetRemoteEnvelope.OP_CONFIG_IMPORT,
    )

    val OPS_LIST: List<OpDescriptor> = listOf(
        OpDescriptor(FleetRemoteEnvelope.OP_CAPABILITIES_GET, OpKind.READ, remoteAllowed = true, requiresConfirm = false),
        OpDescriptor(FleetRemoteEnvelope.OP_REMOTE_COMMAND, OpKind.ACTION, remoteAllowed = true, requiresConfirm = false),
        OpDescriptor(FleetRemoteEnvelope.OP_MACRO_STEP, OpKind.ACTION, remoteAllowed = true, requiresConfirm = false),
        OpDescriptor(FleetRemoteEnvelope.OP_MACRO_RUN, OpKind.ACTION, remoteAllowed = true, requiresConfirm = false),
        OpDescriptor(FleetRemoteEnvelope.OP_NODE_STATUS_GET, OpKind.READ, remoteAllowed = true, requiresConfirm = false),
        OpDescriptor(FleetRemoteEnvelope.OP_CONFIG_EXPORT, OpKind.ACTION, remoteAllowed = false, requiresConfirm = false),
        OpDescriptor(FleetRemoteEnvelope.OP_CONFIG_IMPORT, OpKind.ACTION, remoteAllowed = false, requiresConfirm = true),
    )

    val TARGET: KnownRemoteTarget get() = target()

    fun runCommandTarget(
        useVictorSuiteRepackage: Boolean = VictorSuiteRepackageFlag.isEnabled(),
    ): RunCommandTarget = RunCommandContract.target(useVictorSuiteRepackage)

    fun target(
        useVictorSuiteRepackage: Boolean = VictorSuiteRepackageFlag.isEnabled(),
    ): KnownRemoteTarget {
        val ipc = runCommandTarget(useVictorSuiteRepackage)
        return KnownRemoteTarget(
            id = ID,
            displayName = DISPLAY_NAME,
            packageName = ipc.packageName,
            bridgeAction = ipc.action,
            nodeIdPrefix = FleetRemoteEnvelope.VICTORSUITE_NODE_ID_PREFIX,
            integrationClass = FleetIntegrationClass.TERMUX_IPC,
            documentedDebugCertSha256 = null,
            enforcePin = false,
            status = STATUS,
            isolatedFromDestructive = false,
            cataloguedOps = OPS,
            remoteCommandDefaultOff = true,
            notes = "TERMUX_IPC controller transport. Live default remains " +
                "${RunCommandTargets.TERMUX.packageName} / ${RunCommandTargets.TERMUX.action}. " +
                "Pending package rename to ${RunCommandTargets.VICTORSUITE_REPACKAGE.packageName} " +
                "is behind VictorSuiteRepackageFlag " +
                "(${VictorSuiteRepackageFlag.CONFIG_KEY}, default off). " +
                "Action ${ipc.action} extra ${FleetRemoteEnvelope.TERMUX_JSON_EXTRA}; " +
                "response ${FleetRemoteEnvelope.TERMUX_RESPONSE_EXTRA} " +
                "(envelope extras unchanged either way). Envelope v1, " +
                "BRIDGE_PROTOCOL 1.1. Ops ${OPS.joinToString()}. External " +
                "control OFF by default. Destructive and config.import apply " +
                "always confirmation_required and are not executed. " +
                "node.status.get is never confirmation-gated. $NODE_SOURCE. " +
                "$STATUS — not yet switched, not device-tested. Signing certs stay on " +
                "KnownFleet.VICTORSUITE (do not invent a fingerprint).",
        )
    }

    fun isRegisteredOp(name: String): Boolean = name in OPS
}
