package ai.droidcommand.rootforge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The RootForge bridge wire contract, major version 1. The canonical definition lives in the
 * RootForge repository (`docs/DROIDCOMMAND_INTEGRATION.md` + `tests/test_bridge.py`); this is a
 * hand-maintained Kotlin mirror of it. [BRIDGE_PROTOCOL_MAJOR] must change together with the
 * RootForge side — an incompatible major is rejected by both ends rather than guessed at.
 *
 * Framing is one JSON object per line. Nothing here carries credentials, environment overrides or
 * file contents.
 */
const val BRIDGE_PROTOCOL_MAJOR = 1

/**
 * Advertised in `capabilities.get`. Not a required field on [BridgeRequest]
 * — adding one would break existing nodes that only send `protocol_major`.
 */
const val BRIDGE_PROTOCOL_MINOR = 1

internal val bridgeJson = Json {
    ignoreUnknownKeys = true // tolerate additive minor changes in responses
    encodeDefaults = true
}

@Serializable
data class BridgeRequest(
    @SerialName("protocol_major") val protocolMajor: Int = BRIDGE_PROTOCOL_MAJOR,
    @SerialName("request_id") val requestId: String,
    @SerialName("target_node_id") val targetNodeId: String,
    val operation: String,
    @SerialName("timeout_ms") val timeoutMs: Int = 10_000,
    val parameters: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class BridgeErrorBody(val category: String, val message: String)

@Serializable
data class BridgeResponse(
    @SerialName("protocol_major") val protocolMajor: Int,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("node_id") val nodeId: String? = null,
    val ok: Boolean,
    val result: JsonObject? = null,
    val error: BridgeErrorBody? = null,
)

@Serializable
data class RemoteDevice(
    val serial: String,
    val mode: String,
    val state: String,
    val usable: Boolean,
    val note: String = "",
)

@Serializable
data class DeviceListResult(val devices: List<RemoteDevice>)

/** `rootforge.device.info` result: free-form key/value properties of the connected target. */
@Serializable
data class DeviceInfoResult(val info: Map<String, String> = emptyMap())

/** `rootforge.adb.shell` result. */
@Serializable
data class AdbShellResult(
    @SerialName("exit_code") val exitCode: Int,
    val output: String,
    val truncated: Boolean = false,
)

/** Shared shape for the reboot operations (`rootforge.adb.reboot`, `rootforge.fastboot.reboot`), which only ack the request. */
@Serializable
data class RequestedResult(val requested: String)
