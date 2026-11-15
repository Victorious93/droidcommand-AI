package ai.droidcommand.shizuku.bridge

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import java.util.Base64
import java.util.UUID

/**
 * Victorious Bridge operations as DCA [Tool]s. Every tool is registered behind `SecureToolExecutor`, so DCA's own
 * owner approval, risk classification and audit apply *before* the bridge is asked; the bridge then applies its own
 * grant check. Two independent gates, and neither can substitute for the other.
 *
 * Tool names contain `bridge`; mutating operations and shell are SENSITIVE and always require confirmation.
 * Reads are NORMAL. Shell is additionally named `..._shell_...` so DCA's classifier treats it like other shell tools.
 */
class BridgeTool(
    private val transport: BridgeTransport,
    val operation: BridgeOperation,
    private val backend: BridgeBackend = BridgeBackend.ANY,
    private val timeoutMs: Long = 60_000,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : Tool {
    override val spec = ToolSpec(
        name = toolName(operation),
        description = operation.description + " Runs locally; requires an owner-approved grant in Victorious Bridge.",
        securityLevel = if (operation.mutating || operation == BridgeOperation.FILES_READ) SecurityLevel.SENSITIVE else SecurityLevel.NORMAL,
        requiresConfirmation = operation.mutating || operation.capability == BridgeCapability.FILES_READ,
        permissionCategory = when (operation.capability) {
            BridgeCapability.FILES_READ, BridgeCapability.FILES_WRITE -> PermissionCategory.FILES
            BridgeCapability.SHELL_EXEC -> PermissionCategory.TERMINAL
            else -> PermissionCategory.DEVICE_CONTROL
        },
    )

    override fun execute(input: Map<String, String>): ToolResult {
        // 1. Typed arguments, validated before anything leaves the process.
        val args = LinkedHashMap<String, Any>()
        for (a in operation.args) {
            val raw = input[a.name]
            if (raw == null) {
                if (a.required) return ToolResult.Failure("Missing required input '${a.name}'")
                continue
            }
            args[a.name] = when (a.type) {
                ArgType.STRING -> raw
                ArgType.LONG -> raw.toLongOrNull() ?: return ToolResult.Failure("Input '${a.name}' must be an integer")
                ArgType.BOOL -> when (raw.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> return ToolResult.Failure("Input '${a.name}' must be true or false")
                }
                ArgType.BASE64_BYTES -> try {
                    Base64.getDecoder().decode(raw)
                } catch (_: IllegalArgumentException) {
                    return ToolResult.Failure("Input '${a.name}' must be base64")
                }
            }
        }

        // 2. Negotiate: fail with a precise reason instead of sending a request that cannot succeed.
        val st = transport.negotiate()
        if (!st.available) return ToolResult.Failure("Victorious Bridge is not running. Start it in the Victorious Bridge app (see Diagnostics for the last start result).")
        if (st.apiVersion != BridgeContract.API_VERSION) return ToolResult.Failure("Unsupported Victorious Bridge version ${st.apiVersion} (need ${BridgeContract.API_VERSION}).")
        if (operation.id !in st.supportedOperations) return ToolResult.Failure("This Victorious Bridge does not support ${operation.id}.")
        if (operation.capability !in st.held) {
            return ToolResult.Failure("Victorious Bridge has not granted ${operation.capability.id} to DroidCommand. Ask the owner to approve it in the Victorious Bridge prompt (not granted, expired, or revoked).")
        }
        if (!st.servicePolicy.let { it == BridgeBackend.ANY || backend == BridgeBackend.ANY || it == backend }) {
            return ToolResult.Failure("Victorious Bridge policy (${st.servicePolicy.id}) does not allow ${backend.id} execution.")
        }

        // 3. Submit with correlation id; the remote task id is metadata only.
        val call = transport.submit(BridgeRequestSpec(newId(), operation.id, args, backend, input["remote_task_id"]))
        val res = call.await(timeoutMs) ?: run {
            call.cancel()
            return ToolResult.Failure("Timed out after ${timeoutMs}ms; cancellation requested (request ${call.requestId}).")
        }
        return render(res)
    }

    private fun render(r: BridgeResponse): ToolResult = when {
        r.ok -> ToolResult.Success(json(r))
        r.status == "CANCELLED" -> ToolResult.Failure("Cancelled (${r.error ?: "CANCELLED"}) request ${r.requestId}: ${r.message ?: ""}")
        else -> ToolResult.Failure("${r.error ?: BridgeError.INTERNAL}: ${r.message ?: "failed"} (request ${r.requestId})")
    }

    private fun json(r: BridgeResponse): String = buildString {
        append("{\"request_id\":\"").append(r.requestId).append("\",\"status\":\"OK\",\"duration_ms\":").append(r.durationMs)
        r.execMode?.let { append(",\"exec_mode\":\"").append(it).append('"') }
        append(",\"data\":{")
        append(
            r.data.entries.joinToString(",") { (k, v) ->
                "\"$k\":" + when (v) {
                    null -> "null"
                    is Boolean, is Number -> v.toString()
                    is ByteArray -> "\"" + Base64.getEncoder().encodeToString(v) + "\""
                    is Collection<*> -> v.joinToString(",", "[", "]") { "\"" + it.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
                    else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
                }
            },
        )
        append("}}")
    }

    companion object {
        fun toolName(op: BridgeOperation): String = "victorious_bridge_" + op.id.replace('.', '_').replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2].lowercase()}" }

        fun all(transport: BridgeTransport, backend: BridgeBackend = BridgeBackend.ANY): List<BridgeTool> =
            BridgeOperation.entries.map { BridgeTool(transport, it, backend) }
    }
}
