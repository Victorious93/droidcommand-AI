package ai.droidcommand.remotecontrol

import ai.droidcommand.agent.ToolResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom

/**
 * Hub-side HackerAI command channel over the frozen BOUND_COMPANION
 * envelope (v1.1 field map → [BoundCompanionCommand] JSON).
 *
 * This is the dispatcher [IHackerAIService.executeEnvelope] / [getTaskResult]
 * were added for. It does **not** call `runAgentTask` / `validateFinding` /
 * `cancelTask` / any shell method — those stay confirmation-gated on the
 * companion. No live privileged execution is added here.
 *
 * Choice (smallest honest): [submit] calls [HackerAiEnvelopeChannel.executeEnvelope]
 * **synchronously** and returns the companion reply. It does **not** poll
 * [HackerAiEnvelopeChannel.getTaskResult] in a loop. A caller that receives
 * a queued `taskId` can pass it back as `taskId` for a single [pollTask].
 * TODO: if a later slice needs to wait for completion, add a bounded poll
 * with a caller-supplied budget — do not invent that here.
 */
interface HackerAiEnvelopeChannel {
    val connected: Boolean

    fun executeEnvelope(envelopeJson: String): String

    fun getTaskResult(taskId: String): String
}

sealed class HackerAiEnvelopeResult {
    data class Dispatched(val replyJson: String) : HackerAiEnvelopeResult()

    data class Rejected(
        val category: RemoteErrorCategory,
        val reason: String,
        val preparedJson: String? = null,
    ) : HackerAiEnvelopeResult()

    data class NotConnected(val reason: String) : HackerAiEnvelopeResult()

    fun toToolResult(): ToolResult = when (this) {
        is Dispatched -> ToolResult.Success(replyJson)
        is Rejected -> ToolResult.Failure("${category.wire}: $reason")
        is NotConnected -> ToolResult.Failure(reason)
    }
}

class HackerAiEnvelopeTransport(
    private val channel: HackerAiEnvelopeChannel,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    fun submit(envelope: FleetRemoteEnvelope): HackerAiEnvelopeResult {
        if (!channel.connected) {
            return HackerAiEnvelopeResult.NotConnected(NOT_CONNECTED)
        }
        freshness(envelope)?.let { category ->
            return HackerAiEnvelopeResult.Rejected(category, category.wire)
        }
        val json = BoundCompanionRemoteBinding.encodeJson(envelope)
        if (isConfirmationRequired(envelope.actionRef)) {
            // Never auto-execute destructive / remote-trigger actionRefs.
            // The frozen JSON is formed so a later on-device confirm can
            // send this exact payload via executeEnvelope.
            // TODO: after on-device confirm exists, call executeEnvelope(json)
            // here instead of returning confirmation_required.
            return HackerAiEnvelopeResult.Rejected(
                category = RemoteErrorCategory.CONFIRMATION_REQUIRED,
                reason = "destructive actionRef '${envelope.actionRef}' is confirmation_required " +
                    "and is not auto-executed; external control stays OFF by default",
                preparedJson = json,
            )
        }
        return runCatching {
            HackerAiEnvelopeResult.Dispatched(channel.executeEnvelope(json))
        }.getOrElse { e ->
            HackerAiEnvelopeResult.Rejected(
                RemoteErrorCategory.INTERNAL,
                "executeEnvelope failed: ${e.message ?: e::class.simpleName}",
            )
        }
    }

    /**
     * One-shot poll. Not a wait-loop — see class KDoc.
     */
    fun pollTask(taskId: String): HackerAiEnvelopeResult {
        if (!channel.connected) {
            return HackerAiEnvelopeResult.NotConnected(NOT_CONNECTED)
        }
        val id = taskId.trim()
        if (id.isEmpty()) {
            return HackerAiEnvelopeResult.Rejected(
                RemoteErrorCategory.INVALID_PARAMETERS,
                "taskId is required",
            )
        }
        return runCatching {
            HackerAiEnvelopeResult.Dispatched(channel.getTaskResult(id))
        }.getOrElse { e ->
            HackerAiEnvelopeResult.Rejected(
                RemoteErrorCategory.INTERNAL,
                "getTaskResult failed: ${e.message ?: e::class.simpleName}",
            )
        }
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

    companion object {
        const val ACTION_AGENT_RUN = "agent.run"
        const val ACTION_FINDING_VALIDATE = "finding.validate"
        const val ACTION_TASK_CANCEL = "task.cancel"
        const val ACTION_SHIZUKU_SHELL = "shizuku.shell"
        const val TARGET_HACKERAI = "hackerai"
        const val NOT_CONNECTED =
            "HackerAI companion APK is not connected. " +
                "Install ai.hackerai.companion and ensure DroidCommand AI has bound to its service."

        val CONFIRMATION_REQUIRED_ACTION_REFS: Set<String> = setOf(
            ACTION_AGENT_RUN,
            ACTION_FINDING_VALIDATE,
            ACTION_TASK_CANCEL,
            ACTION_SHIZUKU_SHELL,
        )

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val reservedInputKeys = setOf(
            "envelope",
            "input",
            "taskId",
            "actionRef",
            "requestId",
            "targetNodeRef",
            "nonce",
            "timestampMs",
            "ttlMs",
            "destructive",
            "dryRun",
        )

        fun isConfirmationRequired(actionRef: String): Boolean {
            val lowered = actionRef.lowercase()
            if (CONFIRMATION_REQUIRED_ACTION_REFS.any { it == lowered }) return true
            if (ActionRefClassifier.isDestructive(actionRef)) return true
            return lowered.contains("shizuku") && lowered.contains("shell")
        }

        fun newNonce(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Build a frozen envelope from hub-tool input. Accepts the existing
         * [BoundCompanionCommand] JSON, a [FleetRemoteEnvelope] JSON object,
         * or a legacy task-string map (defaults to [ACTION_AGENT_RUN]).
         */
        fun envelopeFromToolInput(
            input: Map<String, String>,
            nowMs: Long = System.currentTimeMillis(),
            defaultActionRef: String = ACTION_AGENT_RUN,
        ): FleetRemoteEnvelope {
            val raw = input["envelope"] ?: input["input"]
            if (!raw.isNullOrBlank() && raw.trim().startsWith("{")) {
                return decodeEnvelopeJson(
                    raw.trim(),
                    fallbackRequestId = input["requestId"] ?: "hackerai-inline",
                )
            }
            val actionRef = input["actionRef"]?.takeIf { it.isNotBlank() } ?: defaultActionRef
            val args = input.filterKeys { it !in reservedInputKeys }
            return FleetRemoteEnvelope(
                requestId = input["requestId"]?.takeIf { it.isNotBlank() } ?: "hackerai-${newNonce()}",
                targetNodeRef = input["targetNodeRef"]?.takeIf { it.isNotBlank() } ?: TARGET_HACKERAI,
                actionRef = actionRef,
                args = args,
                nonce = input["nonce"]?.takeIf { FleetRemoteEnvelope.isValidNonce(it) } ?: newNonce(),
                timestampMs = input["timestampMs"]?.toLongOrNull() ?: nowMs,
                ttlMs = input["ttlMs"]?.toLongOrNull() ?: FleetRemoteEnvelope.DEFAULT_TTL_MS,
                destructive = input["destructive"] == "true" || isConfirmationRequired(actionRef),
                dryRun = input["dryRun"] == "true",
            )
        }

        fun decodeEnvelopeJson(raw: String, fallbackRequestId: String = "hackerai-inline"): FleetRemoteEnvelope {
            try {
                return BoundCompanionRemoteBinding.decodeJson(raw)
            } catch (_: IllegalArgumentException) {
                // fall through — may be FleetRemoteEnvelope / parameters JSON
            }
            try {
                return json.decodeFromString(FleetRemoteEnvelope.serializer(), raw)
            } catch (_: SerializationException) {
                // fall through
            }
            return RemoteCommandEnvelopeCodec.decodeParameters(raw, fallbackRequestId)
        }

        fun queuedTaskId(replyJson: String): String? {
            val obj = try {
                json.parseToJsonElement(replyJson) as? JsonObject
            } catch (_: SerializationException) {
                return null
            } ?: return null
            val status = obj["status"]?.jsonPrimitive?.content?.lowercase()
            if (status != "queued" && status != "running") return null
            return obj["taskId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: obj["result"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        }

        fun defaultActionRefForCapability(capabilityId: String): String = when {
            capabilityId.endsWith(".validate_finding") -> ACTION_FINDING_VALIDATE
            capabilityId.endsWith(".skill_catalog") -> FleetRemoteEnvelope.OP_CAPABILITIES_GET
            else -> ACTION_AGENT_RUN
        }
    }
}

object DisconnectedHackerAiChannel : HackerAiEnvelopeChannel {
    override val connected: Boolean = false

    override fun executeEnvelope(envelopeJson: String): String =
        error("HackerAI companion is not connected")

    override fun getTaskResult(taskId: String): String =
        error("HackerAI companion is not connected")
}
