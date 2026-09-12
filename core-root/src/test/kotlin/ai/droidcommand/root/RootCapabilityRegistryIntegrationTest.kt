package ai.droidcommand.root

import ai.droidcommand.security.CapabilityFilter
import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.CapabilityMetadata
import ai.droidcommand.security.CapabilityState
import ai.droidcommand.security.InMemoryCapabilityRegistry
import ai.droidcommand.security.RiskTier
import ai.droidcommand.security.isStale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves `core-security.CapabilityRegistry` (CAP-009) and
 * [RootCapabilityHealthChecker] (CAP-009 registry integration) actually
 * work together end-to-end through a real [MagiskProvider] — not two
 * pieces that merely compile against each other, matching every other
 * `*SecureExecutorIntegrationTest`'s "real component, not a scripted
 * double" discipline in this codebase. `core-root` already depends on
 * `core-security`, so this lives here rather than needing a new module.
 */
class RootCapabilityRegistryIntegrationTest {
    private fun magiskProviderWithNoRealMagisk() = MagiskProvider(
        magiskMarkerPaths = listOf("/does/not/exist/on/this/machine"),
        magiskExecutable = "no-such-magisk-binary-anywhere-on-path",
    )

    @Test
    fun `registering root shell then reverifying through a real checker updates its state`() {
        val checker = RootCapabilityHealthChecker(magiskProviderWithNoRealMagisk())
        val registry = InMemoryCapabilityRegistry(checker)
        val id = CapabilityId("root.shell")

        registry.register(
            CapabilityMetadata(
                id = id,
                providerId = "magisk",
                version = "unknown",
                state = CapabilityState.REQUIRES_CONFIGURATION,
                permissionsRequired = emptyList(),
                dependencies = emptyList(),
                description = "root shell execution via Magisk",
                riskTier = RiskTier.DESTRUCTIVE,
            ),
        )

        val reverified = registry.reverify(id)

        assertEquals(CapabilityState.UNAVAILABLE, reverified.state)
        assertEquals(reverified, registry.getCapability(id))
    }

    @Test
    fun `invalidate then isStale correctly flags the capability as due for reverification`() {
        val checker = RootCapabilityHealthChecker(magiskProviderWithNoRealMagisk())
        val registry = InMemoryCapabilityRegistry(checker)
        val id = CapabilityId("root.shell")

        registry.register(
            CapabilityMetadata(
                id = id,
                providerId = "magisk",
                version = "1.0.0",
                state = CapabilityState.AVAILABLE,
                permissionsRequired = emptyList(),
                dependencies = emptyList(),
                lastVerifiedAt = System.currentTimeMillis(),
                description = "root shell execution via Magisk",
                riskTier = RiskTier.DESTRUCTIVE,
            ),
        )
        assertFalse(registry.getCapability(id)!!.isStale(checker.suggestedReverifyIntervalMs("magisk")))

        registry.invalidate(id)

        assertTrue(registry.getCapability(id)!!.isStale(checker.suggestedReverifyIntervalMs("magisk")))
    }

    @Test
    fun `listCapabilities by riskTier finds the registered root capability`() {
        val checker = RootCapabilityHealthChecker(magiskProviderWithNoRealMagisk())
        val registry = InMemoryCapabilityRegistry(checker)
        val id = CapabilityId("root.shell")

        registry.register(
            CapabilityMetadata(
                id = id,
                providerId = "magisk",
                version = "unknown",
                state = CapabilityState.UNAVAILABLE,
                permissionsRequired = emptyList(),
                dependencies = emptyList(),
                description = "root shell execution via Magisk",
                riskTier = RiskTier.DESTRUCTIVE,
            ),
        )

        val results = registry.listCapabilities(CapabilityFilter(riskTier = RiskTier.DESTRUCTIVE))

        assertEquals(listOf(id), results.map { it.id })
    }
}
