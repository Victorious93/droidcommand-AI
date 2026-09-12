package ai.droidcommand.security

/**
 * CAP-010, P1.2. The permission categories the roadmap prompt's own P1.2
 * section lists verbatim (13 values — an earlier `docs/AUDIT_2026-09-05.md`
 * addendum's own CAP-010 row miscounted this as 12; the literal list has
 * 13, corrected here rather than perpetuated). This is a *category*
 * axis — what kind of capability something is — orthogonal to
 * [SecurityLevel] (how much confirmation invoking it needs) and [RiskTier]
 * (how reversible it is): the same "distinct axis, not a competing model"
 * relationship [RiskTier] and [SecurityLevel] already have. Nothing here
 * folds a 14th value into [SecurityLevel] the way a literal reading of the
 * prompt's own "extend the existing `SecurityLevel` enum" heading might
 * suggest — doing that would break every existing use of [SecurityLevel]
 * as a 3-tier confirmation-requirement axis for a wholly different
 * "content type of the permission" concept.
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
    CONTAINER,
    VIRTUALIZATION,
    INFRASTRUCTURE,
}

/**
 * The roadmap prompt's own "CRITICAL: Docker/container socket access is
 * root-equivalent. Never present `CONTAINER` as a peer permission to
 * `VIEW`/`AUTOMATION`" instruction, made an executable gate via
 * [SecurityPolicy.isCategoryGranted] rather than left as a comment only —
 * the prompt's own "Document this explicitly in the policy engine"
 * instruction, taken at its strongest.
 */
val ROOT_EQUIVALENT_CATEGORIES: Set<PermissionCategory> = setOf(PermissionCategory.ROOT, PermissionCategory.CONTAINER)

/**
 * Whether [category] is authorized under this policy. A root-equivalent
 * category ([ROOT_EQUIVALENT_CATEGORIES]) additionally requires
 * [SecurityPolicy.rootEnabled] and a true [SecurityPolicy.rootAvailable]
 * result — fail-closed, so listing [PermissionCategory.CONTAINER] in
 * [SecurityPolicy.grantedCategories] alone can never bypass the same root
 * gate [PermissionCategory.ROOT] itself needs. Additive: no existing
 * [SecurityPolicy] caller or [SecurityPolicyEnforcer.authorize] call site
 * is affected, since neither consults [SecurityPolicy.grantedCategories]
 * or this function today.
 */
fun SecurityPolicy.isCategoryGranted(category: PermissionCategory): Boolean {
    if (category in ROOT_EQUIVALENT_CATEGORIES && !(rootEnabled && rootAvailable())) {
        return false
    }
    return category in grantedCategories
}

/**
 * The roadmap prompt's own privilege-escalation preference order,
 * lowest-privilege tier first: declaration order *is* the preference
 * order, so [entries] iterates least-to-most-privileged and `ordinal`
 * comparisons are meaningful. Two tiers carry a caveat from the prompt's
 * own "Note" that a bare enum value can't express, preserved here instead
 * of silently dropped: [TERMUX] "is not a privilege tier; it's a separate
 * execution environment" — kept in this sequence because the prompt's own
 * list places it here, not because escalating to it grants privilege the
 * way [ROOT] does. [SHIZUKU] "requires ADB or root to bootstrap; it's the
 * API surface, not the privilege grant" — reaching [SHIZUKU] presupposes
 * whatever bootstrapped it already granted the underlying privilege.
 */
enum class EscalationTier {
    ANDROID_SYSTEM_API,
    ACCESSIBILITY_SERVICE,
    ANDROID_RUNTIME_PERMISSION,
    TERMUX,
    SHIZUKU,
    ADB,
    ROOT,
}
