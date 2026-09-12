package ai.droidcommand.security

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.ROOT_EQUIVALENT_CATEGORIES

/**
 * Whether [category] is authorized under this policy. A root-equivalent
 * category ([ROOT_EQUIVALENT_CATEGORIES]) additionally requires
 * [SecurityPolicy.rootEnabled] and a true [SecurityPolicy.rootAvailable]
 * result — fail-closed, so listing [PermissionCategory.CONTAINER] in
 * [SecurityPolicy.grantedCategories] alone can never bypass the same root
 * gate [PermissionCategory.ROOT] itself needs. Now consulted directly by
 * [SecurityPolicyEnforcer.authorize] via [ai.droidcommand.agent.ToolSpec.permissionCategory].
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
