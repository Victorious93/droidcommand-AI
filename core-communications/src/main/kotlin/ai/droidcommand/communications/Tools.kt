package ai.droidcommand.communications

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One droidcommand-AI agent tool per capability (Chat, Plan, Pilot, Forge, Assist, Remember). Names follow the existing
 * snake_case convention (`communications_send_sms`); the dotted id is the wire capability.
 *
 * Anything that sends, calls or changes automation declares `requiresConfirmation`, so `SecureToolExecutor` shows the
 * owner its approval prompt BEFORE [execute] runs. That prompt is the approval for the local path; the executor, not this
 * class, is the boundary. These tools do not replace `send_sms`/`make_call` (which bypass VictorSMS through
 * `DeviceController`); the communications tools only ever go through the VictorSMS gateway.
 */
class CommunicationsTool(private val cap: CommsCapability, private val broker: CommsBroker) : Tool {
    override val spec = ToolSpec(
        name = toolName(cap.id),
        description = cap.description + " Input: " + cap.args.entries.joinToString(", ") { (k, a) -> k + if (a.required) "*" else "" } +
            ". Never reports success unless the gateway reported it; an UNKNOWN outcome must not be retried.",
        securityLevel = when (cap.risk) {
            CommsRisk.READ, CommsRisk.DRAFT, CommsRisk.SAFE -> SecurityLevel.NORMAL
            else -> SecurityLevel.SENSITIVE
        },
        requiresConfirmation = cap.risk == CommsRisk.SEND || cap.risk == CommsRisk.CALL || (cap.risk == CommsRisk.ADMIN && cap.safeWhen == null),
        permissionCategory = if (cap.risk == CommsRisk.READ || cap.risk == CommsRisk.DRAFT) PermissionCategory.VIEW else PermissionCategory.REMOTE_CONTROL,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val reply = try { CommsContract.JSON.parseToJsonElement(broker.local(cap.id, input)) as JsonObject } catch (_: Exception) {
            return ToolResult.Failure("The communications gateway gave an unreadable answer. Do not assume anything was done.")
        }
        val state = (reply["state"] as? JsonPrimitive)?.contentOrNull
        val detail = (reply["detail"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (state) {
            "completed" -> ToolResult.Success((reply["result"] ?: JsonObject(emptyMap())).toString())
            "running" -> ToolResult.Success("Started as job ${(reply["job_id"] as? JsonPrimitive)?.contentOrNull}. It is NOT finished: check its status before saying it is done.")
            "unknown" -> ToolResult.Failure("OUTCOME UNKNOWN: $detail Do not retry and do not tell the owner it was sent; check delivery status first.")
            "pending_approval" -> ToolResult.Failure("Waiting for the owner's approval. Nothing was done.")
            else -> ToolResult.Failure(detail.ifBlank { "Refused (${(reply["code"] as? JsonPrimitive)?.contentOrNull})." })
        }
    }

    companion object {
        fun toolName(id: String) = id.replace('.', '_')
    }
}

object CommunicationsTools {
    fun all(broker: CommsBroker): List<Tool> = CommsCatalog.ALL.map { CommunicationsTool(it, broker) }
}
