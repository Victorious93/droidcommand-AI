package ai.droidforge.config

import ai.droidforge.agent.SecurityLevel
import ai.droidforge.security.SecurityPolicy

/**
 * Builds a [SecurityPolicy] from a [ConfigSource]. [rootAvailable] is never
 * read from configuration — root availability is a real device fact, so
 * the caller must supply it (a real check once core-root exists, a fixture
 * in tests), the same way [SecurityPolicy] itself requires it.
 */
object SecurityPolicyLoader {
    fun load(source: ConfigSource, rootAvailable: () -> Boolean = { false }): SecurityPolicy {
        val reader = ConfigReader(source)

        val grantedPermissions = reader.optional(ConfigKeys.GRANTED_PERMISSIONS)
            .toCsvSet()

        val autoApprove = reader.optional(ConfigKeys.AUTO_APPROVE_LEVELS)
            .toCsvSet()
            .map { SecurityLevel.valueOf(it.uppercase()) }
            .toSet()
            .ifEmpty { setOf(SecurityLevel.NORMAL) }

        return SecurityPolicy(
            rootEnabled = reader.optionalBoolean(ConfigKeys.ROOT_ENABLED, default = false),
            rootAvailable = rootAvailable,
            grantedPermissions = grantedPermissions,
            autoApprove = autoApprove,
        )
    }

    private fun String?.toCsvSet(): Set<String> =
        this?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
}
