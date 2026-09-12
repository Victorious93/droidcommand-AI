package ai.droidcommand.security

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.AgentState
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.LogEvent
import ai.droidcommand.agent.LogLevel
import ai.droidcommand.agent.Logger
import ai.droidcommand.agent.NoOpLogger
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner

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
 *
 * When a [ToolSpec][ai.droidcommand.agent.ToolSpec] declares
 * [ai.droidcommand.agent.ToolSpec.requiredInitiator], the caller-declared
 * [Initiator] passed to [run] must be one of them or the call is denied
 * before the tool (or any grant check) ever runs. As documented on
 * [Initiator] itself, this is a policy boundary — a self-declared value the
 * caller supplies, not something cryptographically verified against a
 * hostile peer — the same honestly-scoped guarantee DroidPilot's `AI_ROOT`
 * gate makes for its own equivalent field.
 *
 * Implements [ToolRunner] so a caller (e.g. `core-agent.DroidCommandSession`)
 * can hold this class or the plain [ToolExecutor] behind the same field. A
 * non-null [AgentMode] passed to [run] is checked against
 * [ai.droidcommand.agent.ToolSpec.allowedModes] before any policy/approval/
 * grant/audit step runs — a tool not offered in the requested mode is denied
 * outright, never merely prompted for approval — and is also forwarded to
 * the [delegate] call, so [ToolExecutor]'s own identical check runs a second
 * time as a harmless backstop.
 *
 * [logger] defaults to [NoOpLogger] (ROADMAP-014) — every existing caller
 * that doesn't pass one is unaffected. It is deliberately distinct from
 * [auditLog]: the audit log is a fail-closed, capacity-bounded *security
 * record* (denying an invocation it cannot record), while [logger] is
 * ordinary operational visibility with no such guarantee — losing a log
 * line is not a reason to deny a call. Every denial [logger]s at `WARN` in
 * addition to whatever [auditLog] records; the delegate's own result logs
 * at the same level [ToolExecutor]'s `tool_result` event would.
 */
class SecureToolExecutor(
    private val registry: ToolRegistry,
    private val delegate: ToolExecutor,
    private val stateMachine: AgentStateMachine,
    private val enforcer: SecurityPolicyEnforcer,
    private val approvalPrompt: ApprovalPrompt,
    private val grantStore: GrantStore? = null,
    private val auditLog: AuditLog? = null,
    private val logger: Logger = NoOpLogger,
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
        val spec = registry.get(toolName).spec
        val effectiveInitiator = initiator ?: Initiator.AI

        fun deny(reason: String, auditType: AuditEventType): ToolResult.Failure {
            auditLog?.record(AuditEvent(auditType, toolName, reason))
            logger.warn("secure_tool_denied", mapOf("tool" to toolName, "auditType" to auditType.name, "reason" to reason))
            val result = ToolResult.Failure(reason)
            stateMachine.transition(AgentState.Observing(toolName, result))
            return result
        }

        if (mode != null && mode !in spec.allowedModes) {
            return deny("Tool '$toolName' is not available in $mode mode", AuditEventType.MODE_DENIED)
        }

        val requiredInitiator = spec.requiredInitiator
        if (requiredInitiator != null && effectiveInitiator !in requiredInitiator) {
            return deny(
                "Tool '$toolName' requires initiator in $requiredInitiator, but was invoked as $effectiveInitiator",
                AuditEventType.INITIATOR_DENIED,
            )
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

        val result = delegate.run(toolName, input, retryPolicy, isCancelled, mode, effectiveInitiator)
        logger.log(
            LogEvent(
                levelFor(result),
                "secure_tool_result",
                mapOf("tool" to toolName, "outcome" to result::class.simpleName.orEmpty()),
            ),
        )

        if (capability != null && grantId != null && result is ToolResult.Success) {
            grantStore?.consume(grantId)
            auditLog?.record(AuditEvent(AuditEventType.GRANT_CONSUMED, toolName, "grant '$grantId' consumed after successful execution"))
        }

        return result
    }

    private fun levelFor(result: ToolResult): LogLevel = when (result) {
        is ToolResult.Success, is ToolResult.Partial -> LogLevel.INFO
        is ToolResult.Unexpected -> LogLevel.WARN
        is ToolResult.Failure -> LogLevel.ERROR
    }
}
