package ai.droidcommand.bots

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner

/**
 * Bot Maker's sandbox (`docs/BRAIN_JARVIS_BOTMAKER_PLAN.md` §3): wraps a
 * [ToolRunner] (typically `core-security.SecureToolExecutor`) so a bot can
 * only ever reach the tools named in [allowedTools]. "A bot cannot see
 * another bot's folder [and] cannot enable a Kai gate you left off" extends
 * here to "cannot call a tool its recipe didn't list" — an empty
 * [allowedTools] means chat-only, per [BotRecipe.allowedTools]'s own doc.
 *
 * This is a recipe-level scoping check, not a security-policy decision:
 * a denial here is returned directly, without ever calling [delegate], so
 * [delegate]'s own policy/approval/grant/audit machinery never even sees a
 * call this bot wasn't allowed to make in the first place. Every call that
 * *is* in [allowedTools] delegates unchanged — [delegate]'s own checks
 * (policy, approval, grants, audit) still apply in full; this type adds a
 * restriction, never a bypass.
 */
class ScopedToolRunner(
    private val delegate: ToolRunner,
    private val allowedTools: Set<String>,
) : ToolRunner {
    override fun run(
        toolName: String,
        input: Map<String, String>,
        retryPolicy: RetryPolicy,
        isCancelled: () -> Boolean,
        mode: AgentMode?,
        initiator: Initiator?,
        grantId: String?,
    ): ToolResult {
        if (toolName !in allowedTools) {
            return ToolResult.Failure("Tool '$toolName' is not in this bot's allowed-tool list")
        }
        return delegate.run(toolName, input, retryPolicy, isCancelled, mode, initiator, grantId)
    }
}
