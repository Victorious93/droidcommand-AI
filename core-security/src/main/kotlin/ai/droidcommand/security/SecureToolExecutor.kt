package ai.droidcommand.security

import ai.droidcommand.agent.AgentState
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.SecurityLevel
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
 *
 * [grantStore]/[auditLog] are optional and additive: when a [ToolSpec][ai.droidcommand.agent.ToolSpec]
 * declares a [ai.droidcommand.agent.ToolSpec.grantCapability], this executor
 * also requires a live grant for it (checked via [grantStore], consumed only
 * once the delegate call actually succeeds — never merely once every gate is
 * passed, so a failed attempt never burns a single-use grant). When
 * [auditLog] is configured, a sensitive/root-level invocation that cannot be
 * recorded is denied rather than run unaudited.
 */
class SecureToolExecutor(
    private val registry: ToolRegistry,
    private val delegate: ToolExecutor,
    private val stateMachine: AgentStateMachine,
    private val enforcer: SecurityPolicyEnforcer,
    private val approvalPrompt: ApprovalPrompt,
    private val grantStore: GrantStore? = null,
    private val auditLog: AuditLog? = null,
) {
    fun run(
        toolName: String,
        input: Map<String, String>,
        retryPolicy: RetryPolicy = RetryPolicy(),
        isCancelled: () -> Boolean = { false },
        grantId: String? = null,
    ): ToolResult {
        val spec = registry.get(toolName).spec

        fun deny(reason: String, auditType: AuditEventType): ToolResult.Failure {
            auditLog?.record(AuditEvent(auditType, toolName, reason))
            val result = ToolResult.Failure(reason)
            stateMachine.transition(AgentState.Observing(toolName, result))
            return result
        }

        when (val decision = enforcer.authorize(spec)) {
            is PolicyDecision.Deny -> return deny(decision.reason, AuditEventType.ACCESS_DENIED)

            is PolicyDecision.RequireApproval -> {
                stateMachine.transition(AgentState.AwaitingApproval(toolName, decision.reason))
                if (!approvalPrompt.requestApproval(decision.reason)) {
                    return deny("User denied authorization: ${decision.reason}", AuditEventType.ACCESS_DENIED)
                }
            }

            PolicyDecision.Allow -> Unit
        }

        val capability = spec.grantCapability
        if (capability != null) {
            val store = grantStore ?: return deny(
                "Tool '$toolName' requires a live grant for capability '$capability', but no grant store is configured",
                AuditEventType.GRANT_DENIED,
            )
            when (val check = store.check(grantId, capability)) {
                is GrantCheck.Denied -> return deny("Grant check failed for '$toolName': ${check.reason}", AuditEventType.GRANT_DENIED)
                GrantCheck.Live -> Unit
            }
        }

        if (auditLog != null && spec.securityLevel != SecurityLevel.NORMAL) {
            val recorded = auditLog.record(AuditEvent(AuditEventType.ACCESS_GRANTED, toolName, "authorized, invoking"))
            if (!recorded) {
                return deny("Audit log is at capacity; refusing to run '$toolName' unaudited", AuditEventType.ACCESS_DENIED)
            }
        }

        val result = delegate.run(toolName, input, retryPolicy, isCancelled)

        if (capability != null && grantId != null && result is ToolResult.Success) {
            grantStore?.consume(grantId)
            auditLog?.record(AuditEvent(AuditEventType.GRANT_CONSUMED, toolName, "grant '$grantId' consumed after successful execution"))
        }

        return result
    }
}
