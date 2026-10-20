package ai.droidcommand.remotecontrol

import ai.droidcommand.rootforge.BridgeResponse
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Controller-side encodings of the **same** [FleetRemoteEnvelope].
 * Field names do not change per class. JSON and the Termux blob use
 * top-level `nonce` / `timestampMs` / `ttlMs` / `destructive`.
 *
 * BOUND_COMPANION is **not** one parcel type for every companion:
 * - EDL (`ai.edlcommander.companion`): [BoundCompanionCommand] maps
 *   1:1 onto `CommandEnvelope` / `ResultEnvelope` parcelables (copied
 *   AIDL). Live copies are not rewritten here; copy when EDL PR #10
 *   merges (`bridgeProtocolMajor`/`Minor`, `op`, `actionRef`,
 *   `paramsJson`, `ResultEnvelope.errorCode`).
 * - HackerAI (`ai.hackerai.companion`) and Pentest-Swarm: existing
 *   domain AIDL methods take/return **JSON strings**
 *   (`IHackerAIService` / `IPentestSwarmService`). No
 *   CommandEnvelope/ResultEnvelope. Encode the same logical fields
 *   into JSON for those calls. Do not invent new parcelables.
 */

object ExternalNodeRemoteBinding {
    fun encode(envelope: FleetRemoteEnvelope, operation: String = FleetRemoteEnvelope.OP_REMOTE_COMMAND): String =
        RemoteCommandEnvelopeCodec.encodeBridgeRequest(envelope, operation)

    fun decode(raw: String): Pair<String, FleetRemoteEnvelope> =
        RemoteCommandEnvelopeCodec.decodeBridgeRequest(raw)
}

/**
 * Logical 1:1 map of frozen envelope fields for BOUND_COMPANION.
 * Used by **both** encodings: EDL parcelables (`command` ← `actionRef`,
 * `argsJson` ← `args`, plus nonce/timestampMs/ttlMs/destructive) and
 * HackerAI/Pentest JSON-string AIDL. This skeleton does not rewrite
 * `*.aidl` (EDL copies stay verbatim until EDL PR #10 merges).
 */
data class BoundCompanionCommand(
    val requestId: String,
    val command: String,
    val argsJson: String,
    val nonce: String,
    val timestampMs: Long,
    val ttlMs: Long,
    val destructive: Boolean,
    val targetNodeRef: String,
    val dryRun: Boolean = false,
)

/**
 * Encodes [FleetRemoteEnvelope] to [BoundCompanionCommand] and back.
 * That record is the logical field map; the wire is EDL parcelables or
 * HackerAI/Pentest JSON-string AIDL, per Appendix B.
 */
object BoundCompanionRemoteBinding {
    private val json = Json { encodeDefaults = true }

    fun encode(envelope: FleetRemoteEnvelope): BoundCompanionCommand = BoundCompanionCommand(
        requestId = envelope.requestId,
        command = envelope.actionRef,
        argsJson = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            envelope.args,
        ),
        nonce = envelope.nonce,
        timestampMs = envelope.timestampMs,
        ttlMs = envelope.ttlMs,
        destructive = envelope.destructive,
        targetNodeRef = envelope.targetNodeRef,
        dryRun = envelope.dryRun == true,
    )

    fun decode(parcel: BoundCompanionCommand): FleetRemoteEnvelope {
        val args: Map<String, String> = if (parcel.argsJson.isBlank() || parcel.argsJson == "{}") {
            emptyMap()
        } else {
            json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                parcel.argsJson,
            )
        }
        return FleetRemoteEnvelope(
            requestId = parcel.requestId,
            targetNodeRef = parcel.targetNodeRef,
            actionRef = parcel.command,
            args = args,
            nonce = parcel.nonce,
            timestampMs = parcel.timestampMs,
            ttlMs = parcel.ttlMs,
            destructive = parcel.destructive,
            dryRun = parcel.dryRun,
        )
    }
}

/**
 * TERMUX_IPC extras: request [EXTRA] on `com.termux.RUN_COMMAND`,
 * response [RESPONSE_EXTRA] via the result PendingIntent. Live
 * broadcast is [UnwiredTermuxIpcSender] by default — encode / decode /
 * parse only unless a test injects a sender.
 */
object TermuxIpcRemoteBinding {
    const val EXTRA = FleetRemoteEnvelope.TERMUX_JSON_EXTRA
    const val RESPONSE_EXTRA = FleetRemoteEnvelope.TERMUX_RESPONSE_EXTRA
    const val ACTION = FleetRemoteEnvelope.TERMUX_RUN_COMMAND_ACTION
    const val PACKAGE = FleetRemoteEnvelope.TERMUX_PACKAGE

    fun encodeBlob(envelope: FleetRemoteEnvelope): String =
        RemoteCommandEnvelopeCodec.encodeTermuxBlob(envelope)

    fun decodeBlob(raw: String): FleetRemoteEnvelope =
        RemoteCommandEnvelopeCodec.decodeTermuxBlob(raw)

    /** VictorSuite returns the frozen [BridgeResponse] JSON. */
    fun parseResponse(raw: String): BridgeResponse =
        RemoteCommandEnvelopeCodec.decodeTermuxResponse(raw)
}
