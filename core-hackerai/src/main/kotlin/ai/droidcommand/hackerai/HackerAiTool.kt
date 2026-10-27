package ai.droidcommand.hackerai

import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.remotecontrol.DisconnectedHackerAiChannel
import ai.droidcommand.remotecontrol.HackerAiEnvelopeChannel
import ai.droidcommand.remotecontrol.HackerAiEnvelopeTransport

/**
 * Hub tool: `run_hackerai_agent_task`.
 *
 * When a [HackerAiEnvelopeChannel] is bound (the live HackerAI AIDL
 * wrapper), this dispatches the frozen remote-control envelope v1.1 JSON
 * through [HackerAiEnvelopeTransport] → `executeEnvelope` /
 * `getTaskResult`. Destructive actionRefs (`agent.run`,
 * `finding.validate`, `task.cancel`, `shizuku.shell`) stay
 * `confirmation_required` and are never auto-executed.
 *
 * Without a connected channel this still returns [ToolResult.Failure]
 * ("not connected") — the same honest gap [AppModule] documents when
 * AIDL bind is not started.
 */
class HackerAiTool(
    private val channel: HackerAiEnvelopeChannel = DisconnectedHackerAiChannel,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : Tool {
    private val transport = HackerAiEnvelopeTransport(channel, nowMs)

    override val spec = ToolSpec(
        name = "run_hackerai_agent_task",
        description = "Delegate a security task to the HackerAI companion APK. " +
            "Runs the HackerAI agent orchestration stack: skill selection, doom-loop detection, " +
            "evidence-gated finding validation, and provider-error recovery. " +
            "Requires the HackerAI companion APK to be installed and bound.",
        securityLevel = SecurityLevel.SENSITIVE,
        requiresConfirmation = true,
        permissionCategory = PermissionCategory.NETWORK,
        requiredInitiator = setOf(Initiator.REMOTE),
    )

    override fun execute(input: Map<String, String>): ToolResult {
        if (!channel.connected) {
            return ToolResult.Failure(HackerAiEnvelopeTransport.NOT_CONNECTED)
        }
        val taskId = input["taskId"]
        val hasEnvelope = !input["envelope"].isNullOrBlank() || looksLikeEnvelopeJson(input["input"])
        if (!taskId.isNullOrBlank() && !hasEnvelope) {
            return transport.pollTask(taskId).toToolResult()
        }
        val envelope = try {
            HackerAiEnvelopeTransport.envelopeFromToolInput(input, nowMs())
        } catch (e: IllegalArgumentException) {
            return ToolResult.Failure("invalid_parameters: ${e.message ?: "invalid envelope"}", e)
        }
        return transport.submit(envelope).toToolResult()
    }

    private fun looksLikeEnvelopeJson(raw: String?): Boolean =
        !raw.isNullOrBlank() && raw.trim().startsWith("{")
}
