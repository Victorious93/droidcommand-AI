package ai.droidcommand.security

import ai.droidcommand.agent.ToolSpec

sealed class PolicyDecision {
    data object Allow : PolicyDecision()
    data class RequireApproval(val reason: String) : PolicyDecision()
    data class Deny(val reason: String) : PolicyDecision()
}

/**
 * Decides, from a [ToolSpec]'s declared requirements alone, whether an
 * invocation may proceed. Order matters: root and permission checks are
 * hard [PolicyDecision.Deny]s evaluated before confirmation, so a tool
 * missing a required permission is denied outright rather than merely
 * prompted for approval.
 */
class SecurityPolicyEnforcer(private val policy: SecurityPolicy) {
    fun authorize(spec: ToolSpec): PolicyDecision {
        if (spec.requiresRoot) {
            if (!policy.rootEnabled) {
                return PolicyDecision.Deny("Tool '${spec.name}' requires root, but root is disabled for this session")
            }
            if (!policy.rootAvailable()) {
                return PolicyDecision.Deny("Tool '${spec.name}' requires root, but root is unavailable on this device")
            }
        }

        val missingPermissions = spec.requiredPermissions - policy.grantedPermissions
        if (missingPermissions.isNotEmpty()) {
            return PolicyDecision.Deny(
                "Tool '${spec.name}' is missing required permission(s): ${missingPermissions.sorted().joinToString()}",
            )
        }

        if (spec.requiresConfirmation && spec.securityLevel !in policy.autoApprove) {
            return PolicyDecision.RequireApproval(
                "Tool '${spec.name}' is ${spec.securityLevel} and requires explicit confirmation",
            )
        }

        return PolicyDecision.Allow
    }
}
