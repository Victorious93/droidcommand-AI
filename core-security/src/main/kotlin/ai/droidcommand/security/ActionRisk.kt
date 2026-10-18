package ai.droidcommand.security

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolSpec

/*
 * Design adapted from Victorious93/opendroid (Apache License 2.0):
 * core/agent/ActionRisk.kt and core/agent/AutoApprovalPolicy.kt. The graded risk enum, the "never
 * auto-approve" floor and the "unknown means ask" default are that project's ideas; this code is a
 * fresh implementation against DroidCommand's `ToolSpec`/`SecurityPolicyEnforcer`, not a copy.
 * Source: https://github.com/Victorious93/opendroid
 */

/**
 * How consequential an action is, least to most. [severity] is only for ordering/[highest]; approval
 * decisions never compare severities, they ask "is this exactly [READ_ONLY] and not hard-gated".
 *
 * Separate from [RiskTier] on purpose: [RiskTier] is the 4-value timeout/approval-flow axis used by
 * [ApprovalProvider], while this axis adds [SENSITIVE] (reads or sends private/personal data) and
 * [ADVANCED_CONTROL] (shell, root, remote nodes, pentest) because those are exactly the actions that
 * must never be waved through. An action with no declared risk is not a member of this enum at all —
 * [ActionRiskClassifier.classify] returns null for it, which every caller treats as "ask".
 */
enum class ActionRisk(val severity: Int) {
    READ_ONLY(1),
    REVERSIBLE(2),
    SENSITIVE(3),
    ADVANCED_CONTROL(4),
    IRREVERSIBLE(5),
    ;

    companion object {
        /** The most consequential of [risks]; an empty collection is [READ_ONLY] only vacuously, so callers must check emptiness. */
        fun highest(risks: Iterable<ActionRisk>): ActionRisk? = risks.maxByOrNull { it.severity }
    }
}

/**
 * Classifies a [ToolSpec] as an [ActionRisk], fail-closed in two layers:
 *
 * 1. [hardGate] — structural facts about the spec that make it ineligible for auto-approval no matter
 *    what anyone declares (root, grants, remote initiators, non-VIEW permission categories, tools never
 *    offered to the planners, and a name deny-list for shell/termux/root/fleet/pentest tooling). A
 *    declaration cannot override these; they exist so a mislabelled catalog entry cannot relax a
 *    dangerous tool.
 * 2. [declared] — an explicit tool-name → [ActionRisk] catalog supplied by the caller. A tool that is
 *    not in the catalog is unclassified (null), never READ_ONLY by default.
 */
class ActionRiskClassifier(private val declared: Map<String, ActionRisk> = emptyMap()) {
    /** The reason [spec] can never be auto-approved, or null if no structural gate applies. */
    fun hardGate(spec: ToolSpec): String? {
        val name = spec.name.lowercase()
        return when {
            spec.requiresRoot || spec.securityLevel == SecurityLevel.ROOT -> "requires root"
            spec.grantCapability != null -> "requires a live '${spec.grantCapability}' grant"
            spec.requiredInitiator != null -> "is restricted to initiators ${spec.requiredInitiator}"
            spec.allowedModes.isEmpty() -> "is only invoked explicitly, never offered to the planners"
            spec.permissionCategory != PermissionCategory.VIEW -> "has permission category ${spec.permissionCategory ?: "none"}, not VIEW"
            NEVER_AUTO_APPROVE_NAME_TOKENS.any { it in name } -> "is shell/root/fleet/pentest tooling by name"
            else -> null
        }
    }

    /** The declared risk for [spec], or null when the tool has no declaration (unclassified). */
    fun classify(spec: ToolSpec): ActionRisk? = declared[spec.name]

    companion object {
        /** Substrings of a lower-cased tool name that always require a human tap. */
        val NEVER_AUTO_APPROVE_NAME_TOKENS: Set<String> = setOf(
            "shell", "termux", "root", "adb", "ssh", "fleet", "flash", "kali", "pentest", "swarm",
            "metasploit", "msf", "setoolkit", "hackerai", "exploit", "playbook",
        )
    }
}
