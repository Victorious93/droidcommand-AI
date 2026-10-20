package ai.droidcommand.remotecontrol

import kotlinx.serialization.Serializable

/**
 * Frozen fleet-wide remote envelope. Fields are **top-level** — there is no
 * nested `auth` object.
 *
 * [requestId] is the existing BridgeRequest `request_id` on EXTERNAL_NODE
 * (not a second identifier). [destructive] is **advisory only**: receivers
 * classify [actionRef] themselves. [trigger] is informational (VictorSMS);
 * it is never an authorization input. Source identity is package+cert
 * (`sendingUid` / Binder uid / Termux permission), never a payload field.
 */
@Serializable
data class FleetRemoteEnvelope(
    val requestId: String,
    val targetNodeRef: String,
    val actionRef: String,
    val args: Map<String, String> = emptyMap(),
    val nonce: String,
    val timestampMs: Long,
    val ttlMs: Long = DEFAULT_TTL_MS,
    val destructive: Boolean = false,
    val trigger: TriggerInfo? = null,
    val macroId: String? = null,
    val stepIndex: Int? = null,
    val onError: String? = null,
    val steps: List<MacroRunStepWire>? = null,
    val dryRun: Boolean? = null,
) {
    init {
        require(requestId.isNotBlank()) { "requestId is required" }
        require(targetNodeRef.isNotBlank()) { "targetNodeRef is required" }
        require(actionRef.isNotBlank()) { "actionRef is required" }
        require(nonce.matches(NONCE_128_BIT)) { "nonce must be 128-bit hex (32 chars)" }
        require(ttlMs in 1..MAX_TTL_MS) { "ttlMs must be 1..$MAX_TTL_MS (default $DEFAULT_TTL_MS)" }
        require(args.size <= MAX_ARGS) { "args exceeds $MAX_ARGS entries" }
        onError?.let {
            require(it == ON_ERROR_ABORT || it == ON_ERROR_CONTINUE) {
                "onError must be '$ON_ERROR_ABORT' or '$ON_ERROR_CONTINUE'"
            }
        }
    }

    /** Parameters object for BridgeRequest — [requestId] is omitted (lives on the outer request). */
    fun parameters(): FleetRemoteParameters = FleetRemoteParameters(
        targetNodeRef = targetNodeRef,
        actionRef = actionRef,
        args = args,
        nonce = nonce,
        timestampMs = timestampMs,
        ttlMs = ttlMs,
        destructive = destructive,
        trigger = trigger,
        macroId = macroId,
        stepIndex = stepIndex,
        onError = onError,
        steps = steps,
        dryRun = dryRun,
    )

    companion object {
        const val OP_CAPABILITIES_GET = "capabilities.get"
        const val OP_REMOTE_COMMAND = "remote.command"
        const val OP_MACRO_STEP = "macro.step"
        const val OP_MACRO_RUN = "macro.run"

        const val TARGET_CONTROLLER = "controller"

        const val DEFAULT_TTL_MS = 120_000L
        const val MAX_TTL_MS = 300_000L
        const val SKEW_MS = 120_000L
        const val REPLAY_RETENTION_MS = 10 * 60 * 1000L

        const val ON_ERROR_ABORT = "abort"
        const val ON_ERROR_CONTINUE = "continue"

        const val PROTOCOL_MAJOR = 1
        const val PROTOCOL_MINOR = 1
        const val MESSAGE_WHAT_REQUEST = 1
        const val MESSAGE_WHAT_RESPONSE = 2

        const val VICTOR_SMS_PACKAGE = "com.victorious.sms"
        const val VICTOR_SMS_BRIDGE_ACTION = "com.victorious.sms.action.BRIDGE"
        const val VICTOR_SMS_NODE_ID_PREFIX = "victorsms-"
        val VICTOR_SMS_NODE_ID = Regex("^victorsms-[0-9a-f]{32}$")

        val NONCE_128_BIT = Regex("^[0-9a-fA-F]{32}$")
        const val MAX_ARGS = 16

        const val TERMUX_JSON_EXTRA = "ai.droidcommand.REMOTE_ENVELOPE_JSON"
    }
}

/** Informational VictorSMS extras. Never used for authorization. */
@Serializable
data class TriggerInfo(
    val sourceNumber: String? = null,
    val matchedKeyword: Boolean? = null,
    val rawExtras: Map<String, String> = emptyMap(),
)

@Serializable
data class MacroRunStepWire(
    val targetNodeRef: String,
    val actionRef: String,
    val args: Map<String, String> = emptyMap(),
    val destructive: Boolean = false,
) {
    init {
        require(targetNodeRef.isNotBlank()) { "targetNodeRef is required" }
        require(actionRef.isNotBlank()) { "actionRef is required" }
    }
}

/**
 * Frozen fields that live at the top level of BridgeRequest.parameters.
 * [FleetRemoteEnvelope.requestId] is BridgeRequest.request_id, not repeated here.
 */
@Serializable
data class FleetRemoteParameters(
    val targetNodeRef: String,
    val actionRef: String,
    val args: Map<String, String> = emptyMap(),
    val nonce: String,
    val timestampMs: Long,
    val ttlMs: Long = FleetRemoteEnvelope.DEFAULT_TTL_MS,
    val destructive: Boolean = false,
    val trigger: TriggerInfo? = null,
    val macroId: String? = null,
    val stepIndex: Int? = null,
    val onError: String? = null,
    val steps: List<MacroRunStepWire>? = null,
    val dryRun: Boolean? = null,
)

/** Transport-authenticated sender. Payload fields are not identity. */
data class SourceIdentity(
    val sourceNodeRef: String,
    val packageName: String? = null,
    val certSha256: String? = null,
    val sendingUid: Int? = null,
) {
    init {
        require(sourceNodeRef.isNotBlank()) { "sourceNodeRef is required" }
    }
}
