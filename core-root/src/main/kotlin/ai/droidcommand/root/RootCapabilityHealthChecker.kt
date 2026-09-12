package ai.droidcommand.root

import ai.droidcommand.security.CapabilityHealthChecker
import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.CapabilityMetadata
import ai.droidcommand.security.CapabilityState
import ai.droidcommand.security.RiskTier

/**
 * The first real [CapabilityHealthChecker] in this repository (CAP-009's
 * registry, P1.1), wrapping any [RootProvider] — Magisk today, a future
 * KernelSU/APatch provider without any change here, per [RootProvider]'s
 * own "core application depends on the abstraction, not a specific
 * implementation" design. `core-root` already depended on `core-security`
 * (confirmed in `build.gradle.kts` before writing this), so no new module
 * dependency was needed.
 *
 * [verify] calls [RootProvider.checkHealth] with [probeShell] (`false` by
 * default, matching [RootProvider.checkHealth]'s own "never silently
 * trigger a root-authorization prompt from a routine check" rule) and maps
 * the result onto [CapabilityMetadata]. [RootProviderState]'s 8 values are
 * all present in the larger 11-value [CapabilityState] (confirmed by this
 * mapping being an exhaustive `when` with no `else` needed), so the
 * mapping is total and lossless — no state is approximated.
 *
 * [riskTier] defaults to [RiskTier.DESTRUCTIVE]: root execution capability
 * is high-risk to grant even before any specific command is considered.
 * Real, but arbitrary — a first reasonable default, not a proven
 * calibration, the same honesty [RiskApprovalPolicy]'s own defaults
 * already claim for themselves. [reverifyIntervalMs] defaults to 60
 * seconds, the same cadence [MagiskProvider]'s own root-shell probe cache
 * already uses (DP-011's recommended cadence per
 * `docs/AUDIT_2026-09-05.md`).
 */
class RootCapabilityHealthChecker(
    private val provider: RootProvider,
    private val probeShell: Boolean = false,
    private val riskTier: RiskTier = RiskTier.DESTRUCTIVE,
    private val reverifyIntervalMs: Long = 60_000,
) : CapabilityHealthChecker {
    override fun verify(id: CapabilityId, providerId: String): CapabilityMetadata {
        val health = provider.checkHealth(probeShell)
        return CapabilityMetadata(
            id = id,
            providerId = providerId,
            version = provider.info.version ?: "unknown",
            state = health.state.toCapabilityState(),
            permissionsRequired = emptyList(),
            dependencies = emptyList(),
            lastVerifiedAt = System.currentTimeMillis(),
            lastError = health.lastError,
            description = "${provider.info.providerName} root provider",
            riskTier = riskTier,
        )
    }

    override fun suggestedReverifyIntervalMs(providerId: String): Long = reverifyIntervalMs

    private fun RootProviderState.toCapabilityState(): CapabilityState = when (this) {
        RootProviderState.AVAILABLE -> CapabilityState.AVAILABLE
        RootProviderState.ENABLED -> CapabilityState.ENABLED
        RootProviderState.DISABLED -> CapabilityState.DISABLED
        RootProviderState.UNAVAILABLE -> CapabilityState.UNAVAILABLE
        RootProviderState.REQUIRES_PERMISSION -> CapabilityState.REQUIRES_PERMISSION
        RootProviderState.REQUIRES_ROOT -> CapabilityState.REQUIRES_ROOT
        RootProviderState.REQUIRES_CONFIGURATION -> CapabilityState.REQUIRES_CONFIGURATION
        RootProviderState.ERROR -> CapabilityState.ERROR
    }
}
