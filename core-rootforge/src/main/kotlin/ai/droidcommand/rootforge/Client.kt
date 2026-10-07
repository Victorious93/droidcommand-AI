package ai.droidcommand.rootforge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** Why a call failed, kept distinct so callers never mistake a transport fault for a node refusal. */
enum class FailureKind { TRANSPORT, MALFORMED_RESPONSE, PROTOCOL_MISMATCH, NODE_IDENTITY_MISMATCH, REMOTE_ERROR }

sealed class RootForgeResult<out T> {
    data class Success<T>(val value: T) : RootForgeResult<T>()
    data class Failure(val kind: FailureKind, val message: String, val remoteCategory: String? = null) : RootForgeResult<Nothing>()
}

/**
 * Typed client for ONE node. Every response is checked against what was sent and what was paired:
 * the correlation id, the protocol major, and — critically — the node id, so a response from a
 * different machine (a wrong host answering, a mis-pinned config) is never accepted as this
 * node's. The request's `target_node_id` is the paired id too, so the node itself also refuses
 * requests meant for someone else.
 */
class RootForgeClient(
    private val node: RootForgeNodeConfig,
    private val transport: RootForgeTransport,
    private val newRequestId: () -> String = { "dca-" + UUID.randomUUID().toString() },
) {
    fun capabilities(): RootForgeResult<JsonObject> = call("rootforge.capabilities.get")

    fun listDevices(): RootForgeResult<DeviceListResult> =
        when (val r = call("rootforge.devices.list")) {
            is RootForgeResult.Failure -> r
            is RootForgeResult.Success -> runCatching { bridgeJson.decodeFromJsonElementCompat(r.value) }
                .fold(
                    { RootForgeResult.Success(it) },
                    { RootForgeResult.Failure(FailureKind.MALFORMED_RESPONSE, "unexpected devices payload: ${it.message}") },
                )
        }

    private fun call(operation: String, timeoutMs: Int = 10_000): RootForgeResult<JsonObject> {
        val requestId = newRequestId()
        val request = BridgeRequest(requestId = requestId, targetNodeId = node.nodeId, operation = operation, timeoutMs = timeoutMs)
        val line = transport.exchange(bridgeJson.encodeToString(request), timeoutMs + 5_000L)
            .getOrElse { return RootForgeResult.Failure(FailureKind.TRANSPORT, it.message ?: "transport failure") }
        val response = runCatching { bridgeJson.decodeFromString<BridgeResponse>(line) }
            .getOrElse { return RootForgeResult.Failure(FailureKind.MALFORMED_RESPONSE, "response is not a valid bridge frame") }
        if (response.protocolMajor != BRIDGE_PROTOCOL_MAJOR) {
            return RootForgeResult.Failure(FailureKind.PROTOCOL_MISMATCH, "node speaks protocol ${response.protocolMajor}, client speaks $BRIDGE_PROTOCOL_MAJOR")
        }
        if (response.nodeId != node.nodeId) {
            return RootForgeResult.Failure(FailureKind.NODE_IDENTITY_MISMATCH, "response came from ${response.nodeId ?: "an unidentified node"}, expected ${node.nodeId}")
        }
        if (response.requestId != requestId) {
            return RootForgeResult.Failure(FailureKind.MALFORMED_RESPONSE, "response does not correlate with the request")
        }
        if (!response.ok) {
            val err = response.error
            return RootForgeResult.Failure(FailureKind.REMOTE_ERROR, err?.message ?: "node reported an error", err?.category)
        }
        return response.result?.let { RootForgeResult.Success(it) }
            ?: RootForgeResult.Failure(FailureKind.MALFORMED_RESPONSE, "ok response without a result")
    }
}

private fun kotlinx.serialization.json.Json.decodeFromJsonElementCompat(obj: JsonObject): DeviceListResult =
    decodeFromJsonElement(DeviceListResult.serializer(), obj)
