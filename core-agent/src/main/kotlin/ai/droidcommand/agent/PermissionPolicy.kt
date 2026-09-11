package ai.droidcommand.agent

/**
 * The 12-value permission taxonomy P1.2 names (CAP-010) — a permission
 * *domain*, distinct from [SecurityLevel]'s risk-*level* axis; the two
 * are not unified in this slice (the same "not unified here" call
 * CAP-008/009/011 already made toward each other's own vocabularies).
 */
enum class PermissionCategory {
    VIEW,
    AUTOMATION,
    TERMINAL,
    FILES,
    NETWORK,
    AI,
    REMOTE_CONTROL,
    DEVICE_CONTROL,
    KNOWLEDGE_GRAPH,
    ROOT,

    /**
     * **CRITICAL (verbatim from the roadmap prompt): Docker/container
     * socket access is root-equivalent. Never present [CONTAINER] as a
     * peer permission to [VIEW]/[AUTOMATION].** Enforced concretely in
     * `core-security.SecurityPolicyEnforcer.authorize`: a [ToolSpec]
     * declaring [CONTAINER] is gated exactly like `requiresRoot = true`,
     * never a lesser check.
     */
    CONTAINER,
    VIRTUALIZATION,
    INFRASTRUCTURE,
}

/**
 * The privilege-escalation preference order P1.2 names (CAP-010) —
 * **declaration order is the preference order** (lower ordinal = less
 * escalated = more preferred), the same "declaration order is priority
 * order" discipline [ContextKind] already documents for itself.
 */
enum class PrivilegeEscalationTier {
    ANDROID_SYSTEM_API,
    ACCESSIBILITY_SERVICE,
    ANDROID_RUNTIME_PERMISSION,

    /**
     * Included here only because the roadmap prompt's own escalation
     * diagram places it in this position — its own note says plainly:
     * **"Termux is not a privilege tier; it's a separate execution
     * environment."** Not reinterpreted or excluded here; the caveat is
     * carried forward verbatim rather than silently resolved either way.
     */
    TERMUX,

    /** "Shizuku requires ADB or root to bootstrap; it's the API surface, not the privilege grant" (verbatim from the roadmap prompt). */
    SHIZUKU,
    ADB,
    ROOT,
}

/**
 * The least-escalated tier among [tiers] (lowest [PrivilegeEscalationTier.ordinal]),
 * or `null` if [tiers] is empty — real logic exercising
 * [PrivilegeEscalationTier]'s own declaration-order-as-preference-order,
 * rather than shipping a bare enum nothing touches.
 */
fun leastEscalatedAvailable(tiers: Set<PrivilegeEscalationTier>): PrivilegeEscalationTier? = tiers.minByOrNull { it.ordinal }
