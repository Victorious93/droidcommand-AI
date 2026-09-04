package ai.droidforge.security

import ai.droidforge.agent.SecurityLevel

/**
 * The authorization rules a DroidForge AI session runs under. Neither
 * [rootAvailable] nor [grantedPermissions] reaches into Android or a real
 * device from this module: both are supplied by the caller (a real
 * on-device check when core-tools-android/core-root exist, a fixture in a
 * test), so the policy itself stays pure and fully testable without a
 * device. [autoApprove] defaults to only [SecurityLevel.NORMAL] — a
 * [SecurityLevel.SENSITIVE] or [SecurityLevel.ROOT] tool always requires
 * explicit approval unless a caller deliberately widens this set, matching
 * "never give the LLM unrestricted root merely because root is available."
 */
class SecurityPolicy(
    val rootEnabled: Boolean = false,
    val rootAvailable: () -> Boolean = { false },
    val grantedPermissions: Set<String> = emptySet(),
    val autoApprove: Set<SecurityLevel> = setOf(SecurityLevel.NORMAL),
)
