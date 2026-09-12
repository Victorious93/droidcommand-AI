package ai.droidcommand.security

import ai.droidcommand.agent.SecurityLevel

/**
 * The authorization rules a DroidCommand AI session runs under. Neither
 * [rootAvailable] nor [grantedPermissions] reaches into Android or a real
 * device from this module: both are supplied by the caller (a real
 * on-device check when core-tools-android/core-root exist, a fixture in a
 * test), so the policy itself stays pure and fully testable without a
 * device. [autoApprove] defaults to only [SecurityLevel.NORMAL] — a
 * [SecurityLevel.SENSITIVE] or [SecurityLevel.ROOT] tool always requires
 * explicit approval unless a caller deliberately widens this set, matching
 * "never give the LLM unrestricted root merely because root is available."
 *
 * [grantedCategories] (CAP-010, P1.2) is a separate, additive axis from
 * [grantedPermissions]: the latter is a flat set of tool-declared
 * permission strings ([ai.droidcommand.agent.ToolSpec.requiredPermissions]),
 * while [grantedCategories] is the structured [PermissionCategory]
 * taxonomy — consulted only via [isCategoryGranted], not by
 * [SecurityPolicyEnforcer.authorize], so no existing caller is affected by
 * its addition.
 */
class SecurityPolicy(
    val rootEnabled: Boolean = false,
    val rootAvailable: () -> Boolean = { false },
    val grantedPermissions: Set<String> = emptySet(),
    val autoApprove: Set<SecurityLevel> = setOf(SecurityLevel.NORMAL),
    val grantedCategories: Set<PermissionCategory> = emptySet(),
)
