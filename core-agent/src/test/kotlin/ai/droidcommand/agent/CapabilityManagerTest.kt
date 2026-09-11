package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ScriptedCapabilityHealthChecker(
    private val reverifyIntervalMs: Long = 10_000,
    private val verifyResult: (CapabilityId, String) -> CapabilityMetadata,
) : CapabilityHealthChecker {
    var verifyCalls = 0
        private set

    override fun verify(id: CapabilityId, providerId: String): CapabilityMetadata {
        verifyCalls++
        return verifyResult(id, providerId)
    }

    override fun suggestedReverifyIntervalMs(providerId: String): Long = reverifyIntervalMs
}

private fun metadata(
    id: String,
    state: CapabilityState = CapabilityState.AVAILABLE,
    providerId: String = "android",
    riskTier: RiskTier = RiskTier.READ_ONLY,
    lastVerifiedAt: Long? = 0L,
) = CapabilityMetadata(
    id = CapabilityId(id),
    providerId = providerId,
    version = "1.0.0",
    state = state,
    permissionsRequired = emptyList(),
    dependencies = emptyList(),
    lastVerifiedAt = lastVerifiedAt,
    lastError = null,
    description = "a capability",
    riskTier = riskTier,
)

class CapabilityManagerTest {
    @Test
    fun `a fresh capability is returned from cache without calling the health checker`() {
        val checker = ScriptedCapabilityHealthChecker { _, _ -> error("should not be called") }
        val registry = DefaultCapabilityRegistry(checker, clock = { 5_000L })
        registry.register(metadata("shell.echo", lastVerifiedAt = 0L))

        val result = registry.getCapability(CapabilityId("shell.echo"))

        assertEquals(metadata("shell.echo", lastVerifiedAt = 0L), result)
        assertEquals(0, checker.verifyCalls)
    }

    @Test
    fun `a stale capability (beyond the suggested interval) triggers a real re-verify on access`() {
        var clockNow = 20_000L
        val fresh = metadata("shell.echo", lastVerifiedAt = 20_000L)
        val checker = ScriptedCapabilityHealthChecker(reverifyIntervalMs = 10_000) { _, _ -> fresh }
        val registry = DefaultCapabilityRegistry(checker, clock = { clockNow })
        registry.register(metadata("shell.echo", lastVerifiedAt = 0L))

        val result = registry.getCapability(CapabilityId("shell.echo"))

        assertEquals(1, checker.verifyCalls)
        assertEquals(fresh, result)
    }

    @Test
    fun `a capability with a null lastVerifiedAt is always treated as stale`() {
        val fresh = metadata("shell.echo", lastVerifiedAt = 1L)
        val checker = ScriptedCapabilityHealthChecker { _, _ -> fresh }
        val registry = DefaultCapabilityRegistry(checker, clock = { 0L })
        registry.register(metadata("shell.echo", lastVerifiedAt = null))

        val result = registry.getCapability(CapabilityId("shell.echo"))

        assertEquals(1, checker.verifyCalls)
        assertEquals(fresh, result)
    }

    @Test
    fun `getCapability for an unregistered id returns null`() {
        val checker = ScriptedCapabilityHealthChecker { _, _ -> error("should not be called") }
        assertNull(DefaultCapabilityRegistry(checker).getCapability(CapabilityId("nope")))
    }

    @Test
    fun `reverify returns fresh metadata and updates the store`() {
        val fresh = metadata("shell.echo", state = CapabilityState.ERROR, lastVerifiedAt = 99L)
        val checker = ScriptedCapabilityHealthChecker { _, _ -> fresh }
        val registry = DefaultCapabilityRegistry(checker, clock = { 99L })
        registry.register(metadata("shell.echo"))

        val result = registry.reverify(CapabilityId("shell.echo"))

        assertEquals(fresh, result)
        assertEquals(fresh, registry.getCapability(CapabilityId("shell.echo")))
    }

    @Test
    fun `reverify throws UnknownCapabilityException for an unregistered id`() {
        val checker = ScriptedCapabilityHealthChecker { _, _ -> error("should not be called") }
        assertFailsWith<UnknownCapabilityException> { DefaultCapabilityRegistry(checker).reverify(CapabilityId("nope")) }
    }

    @Test
    fun `invalidate clears lastVerifiedAt so the next getCapability call genuinely re-verifies`() {
        val fresh = metadata("shell.echo", lastVerifiedAt = 500L)
        val checker = ScriptedCapabilityHealthChecker(reverifyIntervalMs = 1_000_000) { _, _ -> fresh }
        val registry = DefaultCapabilityRegistry(checker, clock = { 500L })
        registry.register(metadata("shell.echo", lastVerifiedAt = 0L))

        // Well within the interval - would not re-verify on its own.
        assertEquals(0, checker.verifyCalls)

        registry.invalidate(CapabilityId("shell.echo"))
        val result = registry.getCapability(CapabilityId("shell.echo"))

        assertEquals(1, checker.verifyCalls)
        assertEquals(fresh, result)
    }

    @Test
    fun `invalidate on an unregistered id is a safe no-op`() {
        val checker = ScriptedCapabilityHealthChecker { _, _ -> error("should not be called") }
        DefaultCapabilityRegistry(checker).invalidate(CapabilityId("nope")) // must not throw
    }

    @Test
    fun `listCapabilities with no filter returns everything, sorted, never calling the health checker`() {
        val checker = ScriptedCapabilityHealthChecker { _, _ -> error("should not be called") }
        val registry = DefaultCapabilityRegistry(checker, clock = { 0L })
        // lastVerifiedAt null makes both entries stale, proving listCapabilities still never re-verifies.
        registry.register(metadata("zeta", lastVerifiedAt = null))
        registry.register(metadata("alpha", lastVerifiedAt = null))

        val result = registry.listCapabilities()

        assertEquals(listOf(CapabilityId("alpha"), CapabilityId("zeta")), result.map { it.id })
        assertEquals(0, checker.verifyCalls)
    }

    @Test
    fun `listCapabilities filters by state, providerId, and riskTier independently`() {
        val checker = ScriptedCapabilityHealthChecker { _, _ -> error("should not be called") }
        val registry = DefaultCapabilityRegistry(checker, clock = { 0L })
        registry.register(metadata("a", state = CapabilityState.AVAILABLE, providerId = "android", riskTier = RiskTier.READ_ONLY))
        registry.register(metadata("b", state = CapabilityState.DISABLED, providerId = "termux", riskTier = RiskTier.DESTRUCTIVE))

        assertEquals(listOf(CapabilityId("a")), registry.listCapabilities(CapabilityFilter(state = CapabilityState.AVAILABLE)).map { it.id })
        assertEquals(listOf(CapabilityId("b")), registry.listCapabilities(CapabilityFilter(providerId = "termux")).map { it.id })
        assertEquals(listOf(CapabilityId("b")), registry.listCapabilities(CapabilityFilter(riskTier = RiskTier.DESTRUCTIVE)).map { it.id })
    }

    @Test
    fun `register upserts - re-registering the same id replaces its metadata`() {
        val checker = ScriptedCapabilityHealthChecker { _, _ -> error("should not be called") }
        val registry = DefaultCapabilityRegistry(checker, clock = { 0L })
        registry.register(metadata("shell.echo", state = CapabilityState.AVAILABLE))
        registry.register(metadata("shell.echo", state = CapabilityState.DISABLED))

        assertEquals(CapabilityState.DISABLED, registry.getCapability(CapabilityId("shell.echo"))?.state)
        assertTrue(registry.listCapabilities().size == 1)
    }
}
