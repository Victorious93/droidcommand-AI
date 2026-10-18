package ai.droidcommand.security

import ai.droidcommand.agent.ToolSpec

/*
 * Design adapted from Victorious93/opendroid (Apache License 2.0), core/agent/AutoApprovalPolicy.kt.
 * See the note in ActionRisk.kt.
 */

/** Outcome of [AutoApprovalPolicy.evaluate]. */
sealed class AutoApprovalDecision {
    /** Safe to run without asking. [reason] is recorded in the audit trail. */
    data class AutoApprove(val risk: ActionRisk, val reason: String) : AutoApprovalDecision()

    /** A human must tap. [reason] says why, for logs and tests; it is never a way to skip the tap. */
    data class RequireApproval(val reason: String) : AutoApprovalDecision()
}

/**
 * Decides whether an action that [SecurityPolicyEnforcer] would otherwise prompt for may run without
 * the prompt. It can only ever relax a confirmation, never add a permission: root, permission,
 * category, grant, initiator and mode gates all run before it, and the enforcer consults it only at
 * the confirmation step.
 *
 * An action auto-approves only when all of these hold:
 *  - [ActionRiskClassifier.hardGate] finds no structural reason to refuse, and
 *  - the tool is explicitly declared [ActionRisk.READ_ONLY] in the classifier's catalog.
 *
 * Everything else — undeclared tools, [ActionRisk.REVERSIBLE] and above, anything that mutates state,
 * sends data off the device, reads private data, runs shell/termux/root, talks to a fleet node or does
 * pentest work — requires approval. There is deliberately no threshold knob: widening what
 * auto-approves means changing this class, which review will see.
 */
class AutoApprovalPolicy(private val classifier: ActionRiskClassifier) {
    fun evaluate(spec: ToolSpec): AutoApprovalDecision {
        classifier.hardGate(spec)?.let {
            return AutoApprovalDecision.RequireApproval("Tool '${spec.name}' $it")
        }
        val risk = classifier.classify(spec)
            ?: return AutoApprovalDecision.RequireApproval("Tool '${spec.name}' has no declared action risk")
        if (risk != ActionRisk.READ_ONLY) {
            return AutoApprovalDecision.RequireApproval("Tool '${spec.name}' is $risk, not READ_ONLY")
        }
        return AutoApprovalDecision.AutoApprove(risk, "Tool '${spec.name}' is declared READ_ONLY with no hard gate")
    }
}
