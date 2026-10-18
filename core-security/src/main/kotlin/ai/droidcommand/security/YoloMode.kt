package ai.droidcommand.security

import ai.droidcommand.agent.ToolSpec

/**
 * Explicit, default-off override of [AutoApprovalPolicy].
 *
 * **Off (default):** behavior is exactly the read-only policy — only a declared
 * [ActionRisk.READ_ONLY] tool with no [ActionRiskClassifier.hardGate] skips a tap.
 *
 * **On:** auto-approves every confirmation the policy would otherwise raise,
 * including the structural hard gates: shell, Termux, root, grant-gated tools,
 * and fleet nodes (RootForge / Flash / companions). It still cannot add a
 * permission, cannot override a [PolicyDecision.Deny], and cannot skip a missing
 * grant — those run before/after this policy.
 *
 * **Non-bypassable exception:** Kali / Metasploit / SET / Pentest-Swarm
 * authorization gates. The exact confirmation phrase
 * [AUTHORIZED_CONFIRMATION_PHRASE] and every ACTIVE / EXPLOIT per-step approval
 * always go to [ApprovalPrompt]. [ai.droidcommand.pentestswarm.ApprovalPromptStepApprover]
 * (and any equivalent) must call the prompt directly and never ask this policy.
 * YOLO must never auto-approve those.
 *
 * Enable only by passing `yoloMode = true` to [AutoApprovalPolicy] /
 * [DefaultActionRisks.policy]. There is no silent default.
 */
object YoloMode {
    /** Factory / constructor default. Never flip this to true. */
    const val ENABLED_DEFAULT = false

    /**
     * Copy for a Settings toggle or confirm-enable dialog. The UI itself is
     * optional; if one is added, show this string before the switch can turn on.
     */
    const val WARNING: String =
        "YOLO auto-approves every action except Kali/pentest authorization gates — " +
            "including shell, Termux, root, grant-gated tools, and fleet nodes " +
            "(RootForge / Flash / companions). It never auto-approves the confirmation " +
            "'I confirm these targets are authorized' or per-step ACTIVE/EXPLOIT " +
            "approvals for Kali, Metasploit, SET, or Pentest-Swarm. Those always prompt. " +
            "Off by default; enable only if you understand the risk."

    /** Prefixed onto every YOLO-waived [AutoApprovalDecision.AutoApprove.reason] and thus the audit trail. */
    const val AUDIT_REASON = "YOLO mode is active"

    /**
     * Exact per-run confirmation [ai.droidcommand.pentestswarm.LocalKaliPlaybookRunner]
     * requires. Documented here so YOLO's scope and that runner cannot drift in wording.
     * This is input validation, not an [AutoApprovalPolicy] decision — YOLO cannot skip it.
     */
    const val AUTHORIZED_CONFIRMATION_PHRASE = "I confirm these targets are authorized"

    /**
     * Tool-name prefix [ai.droidcommand.pentestswarm.ApprovalPromptStepApprover] uses when it
     * forwards an ACTIVE/EXPLOIT step to [ApprovalPrompt]. Contains a pentest token on purpose
     * so a step that somehow reached [AutoApprovalPolicy.evaluate] is still refused.
     */
    const val KALI_PLAYBOOK_STEP_PREFIX = "kali_playbook_step:"

    /**
     * Lower-cased name substrings that identify Kali / Metasploit / SET / Pentest-Swarm
     * authorization. YOLO never waives these, even though they are also in
     * [ActionRiskClassifier.NEVER_AUTO_APPROVE_NAME_TOKENS].
     */
    val PENTEST_AUTHORIZATION_NAME_TOKENS: Set<String> = setOf(
        "kali", "pentest", "swarm", "metasploit", "msf", "setoolkit", "exploit", "playbook",
    )

    fun isPentestAuthorization(toolName: String): Boolean {
        val name = toolName.lowercase()
        return PENTEST_AUTHORIZATION_NAME_TOKENS.any { it in name }
    }

    /** Why [spec] must still prompt, or null if it is not a pentest authorization gate. */
    fun pentestAuthorizationGate(spec: ToolSpec): String? {
        if (!isPentestAuthorization(spec.name)) return null
        return "Tool '${spec.name}' is a Kali/pentest authorization gate and is never auto-approved, including in YOLO mode"
    }
}
