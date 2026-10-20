package ai.droidcommand.remotecontrol

import ai.droidcommand.rootforge.BRIDGE_PROTOCOL_MAJOR
import ai.droidcommand.rootforge.BridgeRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Encode/decode the frozen envelope. EXTERNAL_NODE wrapping reuses
 * [BridgeRequest]: [FleetRemoteEnvelope.requestId] **is** `request_id`.
 */
object RemoteCommandEnvelopeCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeParameters(envelope: FleetRemoteEnvelope): String =
        json.encodeToString(FleetRemoteParameters.serializer(), envelope.parameters())

    fun decodeParameters(raw: String, requestId: String): FleetRemoteEnvelope {
        val parsed = try {
            json.decodeFromString(FleetRemoteParameters.serializer(), raw)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid ${FleetRemoteEnvelope.OP_REMOTE_COMMAND} envelope: ${e.message}", e)
        }
        return parsed.toEnvelope(requestId)
    }

    fun encodeBridgeRequest(envelope: FleetRemoteEnvelope, operation: String = FleetRemoteEnvelope.OP_REMOTE_COMMAND): String {
        val parameters = json.encodeToJsonElement(FleetRemoteParameters.serializer(), envelope.parameters()).jsonObject
        val request = BridgeRequest(
            protocolMajor = BRIDGE_PROTOCOL_MAJOR,
            requestId = envelope.requestId,
            targetNodeId = envelope.targetNodeRef,
            operation = operation,
            timeoutMs = envelope.ttlMs.toInt().coerceAtMost(Int.MAX_VALUE),
            parameters = parameters,
        )
        return json.encodeToString(BridgeRequest.serializer(), request)
    }

    fun decodeBridgeRequest(raw: String): Pair<String, FleetRemoteEnvelope> {
        val request = try {
            json.decodeFromString(BridgeRequest.serializer(), raw)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid BridgeRequest: ${e.message}", e)
        }
        if (request.protocolMajor != BRIDGE_PROTOCOL_MAJOR) {
            throw IllegalArgumentException("unsupported protocol_major ${request.protocolMajor}")
        }
        val parameters = decodeParameters(request.parameters, request.requestId)
        return request.operation to parameters
    }

    fun encodeTermuxBlob(envelope: FleetRemoteEnvelope): String =
        json.encodeToString(FleetRemoteEnvelope.serializer(), envelope)

    fun decodeTermuxBlob(raw: String): FleetRemoteEnvelope {
        return try {
            json.decodeFromString(FleetRemoteEnvelope.serializer(), raw)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid Termux remote envelope: ${e.message}", e)
        }
    }

    private fun decodeParameters(parameters: JsonObject, requestId: String): FleetRemoteEnvelope {
        val parsed = try {
            json.decodeFromJsonElement(FleetRemoteParameters.serializer(), parameters)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid remote parameters: ${e.message}", e)
        }
        return parsed.toEnvelope(requestId)
    }
}

private fun FleetRemoteParameters.toEnvelope(requestId: String) = FleetRemoteEnvelope(
    requestId = requestId,
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
