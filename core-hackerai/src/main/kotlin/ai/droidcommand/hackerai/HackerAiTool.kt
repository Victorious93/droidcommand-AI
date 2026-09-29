package ai.droidcommand.hackerai

import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Tool stub: intended to delegate a security task to HackerAI's agent
 * orchestration stack (skill selection, doom-loop detection, evidence-gated
 * finding validation, provider-error recovery).
 *
 * **No real backing exists, and none is buildable as this class was originally
 * documented — scoped 2026-09-29c (`docs/AUDIT_2026-09-05.md`).** The prior doc
 * comment here described an "HackerAI companion APK AIDL service" (`IHackerAIService`,
 * "Phase 5 / core-companion`"); that mechanism was never real. `Victorious93/hackeraiETC`
 * has no Android presence at all — it is a Next.js/Convex/Trigger.dev cloud web app, and
 * its one native shell (`packages/desktop`) is a desktop-only Tauri wrapper with no
 * standalone service. AIDL requires two Android components on the same device, so there
 * was never a companion APK to bind. hackeraiETC's one real external-control mechanism,
 * `@hackerai/local` (npm), runs the opposite direction from what this tool needs — it
 * lets *HackerAI's cloud* drive a local executor, not this agent delegate a task to
 * HackerAI — and its Agent-run API (`/api/agent-long/*`) is gated by a browser session
 * cookie, not any key an external caller like this one could use. Closing this gap needs
 * hackeraiETC to add a new, genuinely server-side, API-key-authenticated surface first —
 * out of scope for this repo. Until then `execute` returns [ToolResult.Failure] honestly,
 * so the planner reports the gap rather than silently doing nothing.
 *
 * Design note: [Initiator.REMOTE] is kept on [requiredInitiator] so SecureToolExecutor
 * applies the REMOTE policy tier (same as remote-shell tools), not the AI tier — this
 * reflects the tool's intended remote/networked nature regardless of which real transport
 * eventually backs it.
 */
class HackerAiTool : Tool {
    override val spec = ToolSpec(
        name = "run_hackerai_agent_task",
        description = "Delegate a security task to HackerAI's agent orchestration stack: " +
            "skill selection, doom-loop detection, evidence-gated finding validation, and " +
            "provider-error recovery. Not currently reachable — HackerAI has no Android " +
            "presence and no external-caller API this tool can use yet.",
        securityLevel = SecurityLevel.SENSITIVE,
        requiresConfirmation = true,
        permissionCategory = PermissionCategory.NETWORK,
        requiredInitiator = setOf(Initiator.REMOTE),
    )

    override fun execute(input: Map<String, String>): ToolResult =
        ToolResult.Failure(
            "HackerAI delegation is not available: HackerAI (Victorious93/hackeraiETC) has " +
                "no companion Android app and no API-key-authenticated way for an external " +
                "caller to start an Agent run today. See docs/AUDIT_2026-09-05.md's " +
                "2026-09-29c addendum.",
        )
}
