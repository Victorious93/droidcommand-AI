package ai.droidcommand.security

import ai.droidcommand.agent.AgentState
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult

/**
 * Wraps [ToolExecutor] with a [SecurityPolicyEnforcer] check that runs
 * before a tool is ever invoked. A [PolicyDecision.Deny] never reaches
 * [ToolExecutor] or the tool at all — that is the controlled execution
 * boundary the security design calls for: no LLM output, no planner
 * decision, and no bug in a tool's own code can grant root or a missing
 * permission that policy withholds. A [PolicyDecision.RequireApproval]
 * transitions the shared [AgentStateMachine] to [AgentState.AwaitingApproval]
 * and blocks on [approvalPrompt] before the tool may run.
 */
class SecureToolExecutor(
    private val registry: ToolRegistry,
    private val delegate: ToolExecutor,
    private val stateMachine: AgentStateMachine,
    private val enforcer: SecurityPolicyEnforcer,
    private val approvalPrompt: ApprovalPrompt,
) {
    fun run(
        toolName: String,
        input: Map<String, String>,
        retryPolicy: RetryPolicy = RetryPolicy(),
        isCancelled: () -> Boolean = { false },
    ): ToolResult {
        val spec = registry.get(toolName).spec

        when (val decision = enforcer.authorize(spec)) {
            is PolicyDecision.Deny -> {
                val result = ToolResult.Failure(decision.reason)
                stateMachine.transition(AgentState.Observing(toolName, result))
                return result
            }

            is PolicyDecision.RequireApproval -> {
                stateMachine.transition(AgentState.AwaitingApproval(toolName, decision.reason))
                if (!approvalPrompt.requestApproval(decision.reason)) {
                    val result = ToolResult.Failure("User denied authorization: ${decision.reason}")
                    stateMachine.transition(AgentState.Observing(toolName, result))
                    return result
                }
            }

            PolicyDecision.Allow -> Unit
        }

        return delegate.run(toolName, input, retryPolicy, isCancelled)
    }
}
