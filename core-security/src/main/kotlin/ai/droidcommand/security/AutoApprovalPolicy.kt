package ai.droidcommand.security

import ai.droidcommand.agent.ToolSpec

/*
 * Design adapted from Victorious93/opendroid (Apache License 2.0), core/agent/AutoApprovalPolicy.kt.
 * See the note in ActionRisk.kt. OpenDroid's YOLO mode (it disables the never-auto-approve guard)
 * is ported here as [YoloMode]: explicit, default-off, and still refused for pentest authorization.
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
 * the prompt. It can only ever relax a confirmation, never add a permission: root-available,
 * permission, category, grant, initiator and mode gates all run before/around it, and the enforcer
 * consults it only at the confirmation step.
 *
 * Default ([yoloMode] false, [YoloMode.ENABLED_DEFAULT]): an action auto-approves only when all of
 * these hold:
 *  - [ActionRiskClassifier.hardGate] finds no structural reason to refuse, and
 *  - the tool is explicitly declared [ActionRisk.READ_ONLY] in the classifier's catalog.
 *
 * Everything else — undeclared tools, [ActionRisk.REVERSIBLE] and above, anything that mutates state,
 * sends data off the device, reads private data, runs shell/termux/root, talks to a fleet node or does
 * pentest work — requires approval.
 *
 * [yoloMode] true (explicit opt-in only): auto-approves those same actions, including the hard gates
 * for shell / Termux / root / grant-gated tools / fleet nodes / non-destructive remote commands.
 * Scope is everything **except** Kali / pentest authorization gates
 * ([YoloMode.pentestAuthorizationGate] and the per-step [ApprovalPrompt] path that never consults
 * this class) **and** destructive remote actions ([YoloMode.destructiveRemoteGate]). Every YOLO
 * waiver's [reason] starts with [YoloMode.AUDIT_REASON] so the audit log records that YOLO was active.
 *
 * There is deliberately no threshold knob besides the YOLO flag: widening the default means changing
 * this class, which review will see.
 */
class AutoApprovalPolicy(
    private val classifier: ActionRiskClassifier,
    val yoloMode: Boolean = YoloMode.ENABLED_DEFAULT,
) {
    fun evaluate(spec: ToolSpec): AutoApprovalDecision {
        if (yoloMode) return evaluateYolo(spec)
        return evaluateDefault(spec)
    }

    /** YOLO off — identical to the original read-only policy, including reason strings. */
    private fun evaluateDefault(spec: ToolSpec): AutoApprovalDecision {
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

    /**
     * YOLO on — waive every confirmation except pentest authorization and destructive remote
     * actions. Pentest gates are checked first and unchanged; destructive remote is a separate
     * check so Kali/pentest tokens are not overloaded.
     */
    private fun evaluateYolo(spec: ToolSpec): AutoApprovalDecision {
        YoloMode.pentestAuthorizationGate(spec)?.let {
            return AutoApprovalDecision.RequireApproval(it)
        }
        YoloMode.destructiveRemoteGate(spec)?.let {
            return AutoApprovalDecision.RequireApproval(it)
        }
        val risk = classifier.classify(spec) ?: ActionRisk.ADVANCED_CONTROL
        val waived = classifier.hardGate(spec)?.let { " (would have been blocked: $it)" } ?: ""
        return AutoApprovalDecision.AutoApprove(
            risk,
            "${YoloMode.AUDIT_REASON}: waived approval for tool '${spec.name}'$waived",
        )
    }
}
