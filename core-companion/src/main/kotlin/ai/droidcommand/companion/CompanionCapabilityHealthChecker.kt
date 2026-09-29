package ai.droidcommand.companion

import ai.droidcommand.security.CapabilityHealthChecker
import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.CapabilityMetadata
import ai.droidcommand.security.CapabilityState
import ai.droidcommand.security.RiskTier

/**
 * [CapabilityHealthChecker] for companion APK capabilities.
 *
 * Health transitions:
 *  - Companion APK not installed → [CapabilityState.REQUIRES_EXTERNAL_SERVICE]
 *  - Installed but AIDL binder null (not yet bound / process died) → [CapabilityState.ERROR]
 *  - Installed + bound + ping() true → [CapabilityState.AVAILABLE]
 *  - ping() call throws RemoteException → [CapabilityState.ERROR]
 *
 * Reverify interval is 30 s, matching SwarmCapabilityHealthChecker.
 *
 * @param registry  The live [AndroidCompanionRegistry] — must already have [bind] called
 *                  for the target [descriptor] before [verify] is called.
 * @param descriptor The companion this checker monitors.
 */
class CompanionCapabilityHealthChecker(
    private val registry: AndroidCompanionRegistry,
    private val descriptor: CompanionDescriptor,
) : CapabilityHealthChecker {

    override fun verify(id: CapabilityId, providerId: String): CapabilityMetadata {
        val state = determineState()
        return CapabilityMetadata(
            id = id,
            providerId = providerId,
            version = descriptor.version,
            state = state,
            permissionsRequired = emptyList(),
            dependencies = emptyList(),
            lastVerifiedAt = System.currentTimeMillis(),
            lastError = if (state == CapabilityState.ERROR) "companion unreachable" else null,
            description = "Companion APK capability: ${descriptor.id}",
            riskTier = RiskTier.SENSITIVE,
        )
    }

    private fun determineState(): CapabilityState {
        if (!registry.isCompanionInstalled(descriptor.packageName)) {
            return CapabilityState.REQUIRES_EXTERNAL_SERVICE
        }
        val service = when (descriptor.packageName) {
            KnownCompanions.HACKERAI_PACKAGE -> registry.getHackerAiService()
            KnownCompanions.PENTESTSWARM_PACKAGE -> registry.getPentestSwarmService()
            else -> null
        } ?: return CapabilityState.ERROR

        return runCatching {
            val pingResult = when (service) {
                is IHackerAIService -> service.healthCheck() != null
                is IPentestSwarmService -> service.isSwarmReady()
                else -> false
            }
            if (pingResult) CapabilityState.AVAILABLE else CapabilityState.ERROR
        }.getOrElse { CapabilityState.ERROR }
    }

    override fun suggestedReverifyIntervalMs(providerId: String): Long = 30_000L
}
