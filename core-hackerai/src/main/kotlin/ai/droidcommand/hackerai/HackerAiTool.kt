package ai.droidcommand.hackerai

import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Tool stub: invokes an AI agent task via the HackerAI companion APK AIDL service.
 *
 * Real implementation wires IHackerAIService at runtime (Phase 5 / `core-companion`).
 * Until then, `execute` returns [ToolResult.Failure] with a clear "companion not connected"
 * reason so the planner can report the gap rather than silently doing nothing.
 *
 * Design note: all companion AIDL calls use [Initiator.REMOTE] so SecureToolExecutor
 * applies the REMOTE policy tier (same as remote-shell tools), not the AI tier.
 */
class HackerAiTool : Tool {
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

    override fun execute(input: Map<String, String>): ToolResult =
        ToolResult.Failure(
            "HackerAI companion APK is not connected. " +
                "Install ai.hackerai.companion and ensure DroidCommand AI has bound to its service.",
        )
}
