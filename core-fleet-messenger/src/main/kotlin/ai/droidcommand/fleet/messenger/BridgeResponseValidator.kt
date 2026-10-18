package ai.droidcommand.fleet.messenger

import ai.droidcommand.rootforge.BRIDGE_PROTOCOL_MAJOR
import ai.droidcommand.rootforge.BridgeResponse
import ai.droidcommand.rootforge.FailureKind
import ai.droidcommand.rootforge.RootForgeResult
import kotlinx.serialization.json.JsonObject

/**
 * Checks a response against what was sent and what was paired, in the same order as
 * `RootForgeClient`: decode, protocol major, node identity, correlation id, `ok:false`, `ok:true`
 * without a result. The node id check comes before the request id check so a reply from a different
 * machine is reported as an identity problem, not a correlation glitch.
 */
internal object BridgeResponseValidator {
    fun validate(responseJson: String, node: PairedNode, requestId: String): RootForgeResult<JsonObject> {
        if (responseJson.length > MessengerWire.MAX_RESPONSE_CHARS) {
            return RootForgeResult.Failure(FailureKind.MALFORMED_RESPONSE, "response exceeds ${MessengerWire.MAX_RESPONSE_CHARS} characters")
        }
        val response = runCatching { messengerJson.decodeFromString<BridgeResponse>(responseJson) }
            .getOrElse { return RootForgeResult.Failure(FailureKind.MALFORMED_RESPONSE, "response is not a valid bridge frame") }
        if (response.protocolMajor != BRIDGE_PROTOCOL_MAJOR) {
            return RootForgeResult.Failure(
                FailureKind.PROTOCOL_MISMATCH,
                "node speaks protocol ${response.protocolMajor}, client speaks $BRIDGE_PROTOCOL_MAJOR",
            )
        }
        if (response.nodeId != node.nodeId) {
            return RootForgeResult.Failure(
                FailureKind.NODE_IDENTITY_MISMATCH,
                "response came from ${response.nodeId ?: "an unidentified node"}, expected ${node.nodeId}",
            )
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
