package ai.droidcommand.remotecontrol

import kotlinx.serialization.Serializable

/**
 * Frozen fleet-wide remote envelope. Anti-replay fields are **top-level**
 * (`nonce`, `timestampMs`, `ttlMs`) — not nested under `auth`.
 *
 * [requestId] is the existing BridgeRequest `request_id` on EXTERNAL_NODE
 * (not a second identifier). [destructive] is **advisory only**: receivers
 * classify [actionRef] themselves. [trigger] is informational (VictorSMS);
 * it is never an authorization input. Source identity is package+cert
 * (`sendingUid` / Binder uid / Termux permission), never a payload field.
 *
 * An inbound envelope is an **untrusted request**. Delivery (e.g. a
 * VictorSMS whitelist + code word) is not authorization.
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
    val op: String? = null,
    val params: Map<String, String> = emptyMap(),
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
        require(isValidNonce(nonce)) {
            "nonce must be 128-bit hex (32 chars) or base64"
        }
        require(ttlMs in 1..MAX_TTL_MS) {
            "ttlMs must be 1..$MAX_TTL_MS (default $DEFAULT_TTL_MS)"
        }
        require(args.size <= MAX_ARGS) { "args exceeds $MAX_ARGS entries" }
        require(params.size <= MAX_ARGS) { "params exceeds $MAX_ARGS entries" }
        onError?.let {
            require(it == ON_ERROR_ABORT || it == ON_ERROR_CONTINUE) {
                "onError must be '$ON_ERROR_ABORT' or '$ON_ERROR_CONTINUE'"
            }
        }
    }

    fun stepOp(): String = op?.takeIf { it.isNotBlank() } ?: actionRef

    fun stepParams(): Map<String, String> = if (params.isNotEmpty()) params else args

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
        op = op,
        params = params,
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

        /** VictorSMS inbound SMS-trigger source op. Delivery, not authorization. */
        const val OP_SMS_REMOTE_COMMAND_FORWARD = "sms.remote_command.forward"

        const val TARGET_CONTROLLER = "controller"

        const val DEFAULT_TTL_MS = 120_000L
        const val MAX_TTL_MS = 300_000L
        /** Future-skew allowance only. Past validity is TTL-only. */
        const val FUTURE_SKEW_MS = 120_000L
        const val REPLAY_RETENTION_MS = 10 * 60 * 1000L

        const val ON_ERROR_ABORT = "abort"
        const val ON_ERROR_CONTINUE = "continue"

        const val PROTOCOL_MAJOR = 1
        const val PROTOCOL_MINOR = 1
        const val MESSAGE_WHAT_REQUEST = 1
        const val MESSAGE_WHAT_RESPONSE = 2

        const val CONTROLLER_PACKAGE = "ai.droidcommand.app"
        const val CONTROLLER_BRIDGE_ACTION = "ai.droidcommand.app.action.BRIDGE"

        const val VICTOR_SMS_PACKAGE = "com.victorious.sms"
        const val VICTOR_SMS_BRIDGE_ACTION = "com.victorious.sms.action.BRIDGE"
        const val VICTOR_SMS_NODE_ID_PREFIX = "victorsms-"
        val VICTOR_SMS_NODE_ID = Regex("^victorsms-[0-9a-f]{32}$")

        const val VICTOR_SPOOF_PACKAGE = "com.victorious.spoof"
        const val VICTOR_SPOOF_BRIDGE_ACTION = "com.victorious.spoof.action.BRIDGE"

        const val KYC_PACKAGE = "com.identityshield.companion"
        const val KYC_BRIDGE_ACTION = "com.identityshield.companion.action.BRIDGE"

        val NONCE_128_BIT = Regex("^[0-9a-fA-F]{32}$")
        const val MAX_ARGS = 16

        const val TERMUX_JSON_EXTRA = "ai.droidcommand.REMOTE_ENVELOPE_JSON"

        fun isValidNonce(value: String): Boolean {
            if (value.matches(NONCE_128_BIT)) return true
            return decode128BitBase64(value)
        }

        private fun decode128BitBase64(value: String): Boolean {
            if (value.isBlank()) return false
            val decoded = decodeBase64(value) ?: return false
            return decoded.size == 16
        }

        private fun decodeBase64(value: String): ByteArray? {
            val padded = when (value.length % 4) {
                0 -> value
                2 -> "$value=="
                3 -> "$value="
                else -> return null
            }
            return try {
                java.util.Base64.getDecoder().decode(padded)
            } catch (_: IllegalArgumentException) {
                try {
                    java.util.Base64.getUrlDecoder().decode(padded)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }
    }
}

/** Informational VictorSMS extras. Never used for authorization. */
@Serializable
data class TriggerInfo(
    val sourceNumber: String? = null,
    val matchedKeyword: Boolean? = null,
    val rawExtras: Map<String, String> = emptyMap(),
)

/**
 * Generic node `macro.run` step. [op] must be advertised on **this** node.
 * `onError = abort` is atomic: stop-on-first-error + report partial; no rollback.
 */
@Serializable
data class MacroRunStepWire(
    val op: String,
    val params: Map<String, String> = emptyMap(),
    val onError: String = FleetRemoteEnvelope.ON_ERROR_ABORT,
    val timeoutMs: Long? = null,
) {
    init {
        require(op.isNotBlank()) { "op is required" }
        require(params.size <= FleetRemoteEnvelope.MAX_ARGS) {
            "params exceeds ${FleetRemoteEnvelope.MAX_ARGS} entries"
        }
        require(
            onError == FleetRemoteEnvelope.ON_ERROR_ABORT ||
                onError == FleetRemoteEnvelope.ON_ERROR_CONTINUE,
        ) { "onError must be abort or continue" }
        timeoutMs?.let { require(it > 0) { "timeoutMs must be positive" } }
    }
}

/**
 * Frozen fields that live in BridgeRequest.parameters. Anti-replay fields
 * are top-level (`nonce` / `timestampMs` / `ttlMs`), not nested.
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
    val op: String? = null,
    val params: Map<String, String> = emptyMap(),
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

/** Controller-side pairing of a source node (package + optional cert). Empty cert = fail-open / unpinned. */
data class DevicePairing(
    val sourceNodeRef: String,
    val packageName: String,
    val certSha256: String? = null,
) {
    init {
        require(sourceNodeRef.isNotBlank()) { "sourceNodeRef is required" }
        require(packageName.isNotBlank()) { "packageName is required" }
    }
}
