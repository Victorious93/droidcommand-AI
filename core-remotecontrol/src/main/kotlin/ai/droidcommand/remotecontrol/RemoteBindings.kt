package ai.droidcommand.remotecontrol

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Controller-side encodings of the **same** [FleetRemoteEnvelope].
 * Field names do not change per class. JSON uses nested [EnvelopeAuth];
 * AIDL parcel fields stay flat (`nonce` / `timestampMs` / `ttlMs`) until
 * the live `.aidl` copies are bumped together with EDL.
 */

object ExternalNodeRemoteBinding {
    fun encode(envelope: FleetRemoteEnvelope, operation: String = FleetRemoteEnvelope.OP_REMOTE_COMMAND): String =
        RemoteCommandEnvelopeCodec.encodeBridgeRequest(envelope, operation)

    fun decode(raw: String): Pair<String, FleetRemoteEnvelope> =
        RemoteCommandEnvelopeCodec.decodeBridgeRequest(raw)
}

/**
 * 1:1 map onto existing companion [CommandEnvelope] fields, plus the
 * nonce/timestampMs/ttlMs/destructive parcel fields the live AIDL has not
 * grown yet. This skeleton does not rewrite `*.aidl` (EDL copies stay verbatim).
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
        timestampMs = envelope.issuedAt,
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
            auth = EnvelopeAuth(
                nonce = parcel.nonce,
                issuedAt = parcel.timestampMs,
                ttlMs = parcel.ttlMs,
            ),
            destructive = parcel.destructive,
            dryRun = parcel.dryRun,
        )
    }
}

object TermuxIpcRemoteBinding {
    const val EXTRA = FleetRemoteEnvelope.TERMUX_JSON_EXTRA

    fun encodeBlob(envelope: FleetRemoteEnvelope): String =
        RemoteCommandEnvelopeCodec.encodeTermuxBlob(envelope)

    fun decodeBlob(raw: String): FleetRemoteEnvelope =
        RemoteCommandEnvelopeCodec.decodeTermuxBlob(raw)
}
