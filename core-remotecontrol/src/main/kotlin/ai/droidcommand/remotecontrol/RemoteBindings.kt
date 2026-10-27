package ai.droidcommand.remotecontrol

import ai.droidcommand.rootforge.BridgeResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
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
@Serializable
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

    /**
     * Frozen BOUND_COMPANION JSON field map for HackerAI
     * `IHackerAIService.executeEnvelope`. Same logical fields as
     * [FleetRemoteEnvelope]; not a new envelope shape.
     */
    fun encodeJson(envelope: FleetRemoteEnvelope): String =
        json.encodeToString(BoundCompanionCommand.serializer(), encode(envelope))

    fun decodeJson(raw: String): FleetRemoteEnvelope {
        val parsed = try {
            json.decodeFromString(BoundCompanionCommand.serializer(), raw)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid BoundCompanionCommand JSON: ${e.message}", e)
        }
        return decode(parsed)
    }
}

/**
 * TERMUX_IPC extras: request [EXTRA] on the active `RUN_COMMAND` action
 * (live default `com.termux.RUN_COMMAND`; pending `com.victorious.suite`
 * behind [ai.droidcommand.termux.VictorSuiteRepackageFlag]),
 * response [RESPONSE_EXTRA] via the result PendingIntent. Envelope extra
 * names stay `ai.droidcommand.*` either way. Live broadcast is
 * [UnwiredTermuxIpcSender] by default — encode / decode / parse only
 * unless a test injects a sender.
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
