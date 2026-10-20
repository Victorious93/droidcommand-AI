package ai.droidcommand.rootforge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Why a call failed, kept distinct so callers never mistake a transport fault for a node refusal.
 * [UNTRUSTED_NODE] is the controller-side refusal raised before anything is sent: the target is not
 * paired, not installed, or its installed signing certificate is not the paired one (Messenger transport).
 */
enum class FailureKind { TRANSPORT, MALFORMED_RESPONSE, PROTOCOL_MISMATCH, NODE_IDENTITY_MISMATCH, REMOTE_ERROR, UNTRUSTED_NODE }

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
        decoded("rootforge.devices.list", DeviceListResult.serializer()) { "unexpected devices payload: $it" }

    /** Read op: properties of the currently connected target (`{}` if none is attached). */
    fun deviceInfo(): RootForgeResult<DeviceInfoResult> =
        decoded("rootforge.device.info", DeviceInfoResult.serializer()) { "unexpected device.info payload: $it" }

    /**
     * Control op: runs [command] in an adb shell on the paired node's connected target.
     * The node itself prompts its operator before running it ("Allow `rootforge.adb.shell`?") —
     * this call does not bypass that; it only reaches the node.
     */
    fun adbShell(command: String): RootForgeResult<AdbShellResult> =
        decoded(
            "rootforge.adb.shell",
            AdbShellResult.serializer(),
            parameters = buildJsonObject { put("command", command) },
        ) { "unexpected adb.shell payload: $it" }

    /**
     * Control op: reboots the connected target **to** [target] (`""`/`bootloader`/`recovery`/
     * `sideload`/`fastboot`/`edl`) via adb — not a sideload-of-a-zip.
     */
    fun adbReboot(target: String = ""): RootForgeResult<RequestedResult> =
        decoded(
            "rootforge.adb.reboot",
            RequestedResult.serializer(),
            parameters = buildJsonObject { put("target", target) },
        ) { "unexpected adb.reboot payload: $it" }

    /** Control op: reboots the connected target (already in fastboot) to [target] (`system`/`bootloader`/`recovery`/`fastbootd`). */
    fun fastbootReboot(target: String): RootForgeResult<RequestedResult> =
        decoded(
            "rootforge.fastboot.reboot",
            RequestedResult.serializer(),
            parameters = buildJsonObject { put("target", target) },
        ) { "unexpected fastboot.reboot payload: $it" }

    private fun <T> decoded(
        operation: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        parameters: JsonObject = JsonObject(emptyMap()),
        malformedMessage: (String) -> String,
    ): RootForgeResult<T> = when (val r = call(operation, parameters)) {
        is RootForgeResult.Failure -> r
        is RootForgeResult.Success -> runCatching { bridgeJson.decodeFromJsonElement(serializer, r.value) }
            .fold(
                { RootForgeResult.Success(it) },
                { RootForgeResult.Failure(FailureKind.MALFORMED_RESPONSE, malformedMessage(it.message ?: it.toString())) },
            )
    }

    private fun call(operation: String, parameters: JsonObject = JsonObject(emptyMap()), timeoutMs: Int = 10_000): RootForgeResult<JsonObject> {
        val requestId = newRequestId()
        val request = BridgeRequest(requestId = requestId, targetNodeId = node.nodeId, operation = operation, parameters = parameters, timeoutMs = timeoutMs)
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
