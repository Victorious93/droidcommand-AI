package ai.droidcommand.root

import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.CapabilityState
import ai.droidcommand.security.RiskTier
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ScriptedRootProvider(
    override val info: RootProviderInfo,
    private val health: RootHealth,
) : RootProvider {
    var lastProbeShell: Boolean? = null
        private set

    override fun isRootAvailable(): Boolean = health.rootAvailable

    override fun isAuthorized(): Boolean = health.rootAuthorized

    override fun getPrivilegeLevel(): PrivilegeLevel = health.privilegeLevel

    override fun checkHealth(probeShell: Boolean): RootHealth {
        lastProbeShell = probeShell
        return health
    }

    override fun getCapabilities(): Set<String> = emptySet()

    override fun execute(command: RootCommand, isCancelled: () -> Boolean): RootExecutionResult =
        RootExecutionResult.Failure("not used by this test")
}

private fun health(
    state: RootProviderState,
    lastError: String? = null,
    privilegeLevel: PrivilegeLevel = PrivilegeLevel.NONE,
) = RootHealth(
    state = state,
    rootAvailable = state == RootProviderState.AVAILABLE,
    rootAuthorized = state == RootProviderState.AVAILABLE,
    rootShellAvailable = state == RootProviderState.AVAILABLE,
    privilegeLevel = privilegeLevel,
    lastError = lastError,
)

class RootCapabilityHealthCheckerTest {
    @Test
    fun `every RootProviderState maps to the identically-named CapabilityState`() {
        val expected = mapOf(
            RootProviderState.AVAILABLE to CapabilityState.AVAILABLE,
            RootProviderState.ENABLED to CapabilityState.ENABLED,
            RootProviderState.DISABLED to CapabilityState.DISABLED,
            RootProviderState.UNAVAILABLE to CapabilityState.UNAVAILABLE,
            RootProviderState.REQUIRES_PERMISSION to CapabilityState.REQUIRES_PERMISSION,
            RootProviderState.REQUIRES_ROOT to CapabilityState.REQUIRES_ROOT,
            RootProviderState.REQUIRES_CONFIGURATION to CapabilityState.REQUIRES_CONFIGURATION,
            RootProviderState.ERROR to CapabilityState.ERROR,
        )
        for ((rootState, capState) in expected) {
            val provider = ScriptedRootProvider(RootProviderInfo("magisk", "Magisk", "27.0"), health(rootState))
            val checker = RootCapabilityHealthChecker(provider)
            assertEquals(capState, checker.verify(CapabilityId("root.shell"), "magisk").state)
        }
    }

    @Test
    fun `a null provider version falls back to the literal string unknown`() {
        val provider = ScriptedRootProvider(RootProviderInfo("magisk", "Magisk", version = null), health(RootProviderState.AVAILABLE))
        val result = RootCapabilityHealthChecker(provider).verify(CapabilityId("root.shell"), "magisk")
        assertEquals("unknown", result.version)
    }

    @Test
    fun `a real provider version is passed through`() {
        val provider = ScriptedRootProvider(RootProviderInfo("magisk", "Magisk", "27.0"), health(RootProviderState.AVAILABLE))
        val result = RootCapabilityHealthChecker(provider).verify(CapabilityId("root.shell"), "magisk")
        assertEquals("27.0", result.version)
    }

    @Test
    fun `lastError is passed through from RootHealth`() {
        val provider = ScriptedRootProvider(
            RootProviderInfo("magisk", "Magisk", "27.0"),
            health(RootProviderState.REQUIRES_PERMISSION, lastError = "root shell not authorized"),
        )
        val result = RootCapabilityHealthChecker(provider).verify(CapabilityId("root.shell"), "magisk")
        assertEquals("root shell not authorized", result.lastError)
    }

    @Test
    fun `a healthy AVAILABLE result carries no lastError`() {
        val provider = ScriptedRootProvider(RootProviderInfo("magisk", "Magisk", "27.0"), health(RootProviderState.AVAILABLE))
        val result = RootCapabilityHealthChecker(provider).verify(CapabilityId("root.shell"), "magisk")
        assertNull(result.lastError)
    }

    @Test
    fun `lastVerifiedAt is set to a recent real timestamp`() {
        val provider = ScriptedRootProvider(RootProviderInfo("magisk", "Magisk", "27.0"), health(RootProviderState.AVAILABLE))
        val before = System.currentTimeMillis()
        val result = RootCapabilityHealthChecker(provider).verify(CapabilityId("root.shell"), "magisk")
        val after = System.currentTimeMillis()
        assertTrue(result.lastVerifiedAt in before..after)
    }

    @Test
    fun `description names the provider's own providerName`() {
        val provider = ScriptedRootProvider(RootProviderInfo("magisk", "Magisk", "27.0"), health(RootProviderState.AVAILABLE))
        val result = RootCapabilityHealthChecker(provider).verify(CapabilityId("root.shell"), "magisk")
        assertTrue(result.description.contains("Magisk"))
    }

    @Test
    fun `riskTier defaults to DESTRUCTIVE`() {
        val provider = ScriptedRootProvider(RootProviderInfo("magisk", "Magisk", "27.0"), health(RootProviderState.AVAILABLE))
        val result = RootCapabilityHealthChecker(provider).verify(CapabilityId("root.shell"), "magisk")
        assertEquals(RiskTier.DESTRUCTIVE, result.riskTier)
    }

    @Test
    fun `riskTier is overridable`() {
        val provider = ScriptedRootProvider(RootProviderInfo("magisk", "Magisk", "27.0"), health(RootProviderState.AVAILABLE))
        val result = RootCapabilityHealthChecker(provider, riskTier = RiskTier.IRREVERSIBLE).verify(CapabilityId("root.shell"), "magisk")
        assertEquals(RiskTier.IRREVERSIBLE, result.riskTier)
    }

    @Test
    fun `the given providerId is used verbatim, not the provider's own info providerId`() {
        val provider = ScriptedRootProvider(RootProviderInfo("magisk", "Magisk", "27.0"), health(RootProviderState.AVAILABLE))
        val result = RootCapabilityHealthChecker(provider).verify(CapabilityId("root.shell"), "some-other-id")
        assertEquals("some-other-id", result.providerId)
    }

    @Test
    fun `probeShell defaults to false, matching RootProvider's own never-prompt-silently rule`() {
        val provider = ScriptedRootProvider(RootProviderInfo("magisk", "Magisk", "27.0"), health(RootProviderState.AVAILABLE))
        RootCapabilityHealthChecker(provider).verify(CapabilityId("root.shell"), "magisk")
        assertEquals(false, provider.lastProbeShell)
    }

    @Test
    fun `probeShell true is forwarded to checkHealth when configured`() {
        val provider = ScriptedRootProvider(RootProviderInfo("magisk", "Magisk", "27.0"), health(RootProviderState.AVAILABLE))
        RootCapabilityHealthChecker(provider, probeShell = true).verify(CapabilityId("root.shell"), "magisk")
        assertEquals(true, provider.lastProbeShell)
    }

    @Test
    fun `suggestedReverifyIntervalMs defaults to 60 seconds`() {
        val checker = RootCapabilityHealthChecker(NullRootProvider())
        assertEquals(60_000L, checker.suggestedReverifyIntervalMs("magisk"))
    }

    @Test
    fun `suggestedReverifyIntervalMs is overridable`() {
        val checker = RootCapabilityHealthChecker(NullRootProvider(), reverifyIntervalMs = 5_000)
        assertEquals(5_000L, checker.suggestedReverifyIntervalMs("magisk"))
    }
}

/**
 * Proves this checker flows through a real [MagiskProvider] — not a
 * scripted double — the same "real component" discipline
 * `MagiskProviderSecureExecutorIntegrationTest` already holds itself to.
 * No `su`/`magisk` script fixture is needed for either case exercised
 * here: an absent marker path proves the genuine "not installed" path,
 * and a present marker path with a freshly-constructed (never-probed)
 * provider proves the genuine "installed but not yet probed" path —
 * both real [MagiskProvider] behavior, not simulated.
 */
class RootCapabilityHealthCheckerMagiskIntegrationTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("root-capability-health-checker-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `a real MagiskProvider with no marker present yields UNAVAILABLE`() {
        val provider = MagiskProvider(
            magiskMarkerPaths = listOf(File(tempDir, "does-not-exist").path),
            magiskExecutable = File(tempDir, "no-such-magisk-binary").path,
        )
        val result = RootCapabilityHealthChecker(provider).verify(CapabilityId("root.shell"), "magisk")
        assertEquals(CapabilityState.UNAVAILABLE, result.state)
    }

    @Test
    fun `a real MagiskProvider with a marker present but never probed yields REQUIRES_PERMISSION`() {
        val marker = File(tempDir, "magisk-marker").apply { createNewFile() }
        val provider = MagiskProvider(
            magiskMarkerPaths = listOf(marker.path),
            magiskExecutable = File(tempDir, "no-such-magisk-binary").path,
        )
        val result = RootCapabilityHealthChecker(provider).verify(CapabilityId("root.shell"), "magisk")
        assertEquals(CapabilityState.REQUIRES_PERMISSION, result.state)
    }
}
