package ai.droidcommand.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun metadata(
    id: CapabilityId = CapabilityId("android.notifications.read"),
    providerId: String = "android",
    state: CapabilityState = CapabilityState.AVAILABLE,
    lastVerifiedAt: Long? = null,
    riskTier: RiskTier = RiskTier.READ_ONLY,
) = CapabilityMetadata(
    id = id,
    providerId = providerId,
    version = "1.0.0",
    state = state,
    permissionsRequired = emptyList(),
    dependencies = emptyList(),
    lastVerifiedAt = lastVerifiedAt,
    description = "reads notifications",
    riskTier = riskTier,
)

class CapabilityMetadataIsStaleTest {
    @Test
    fun `never verified is always stale`() {
        assertTrue(metadata(lastVerifiedAt = null).isStale(reverifyIntervalMs = 10_000, now = 100_000))
    }

    @Test
    fun `within the interval is not stale`() {
        assertFalse(metadata(lastVerifiedAt = 95_000).isStale(reverifyIntervalMs = 10_000, now = 100_000))
    }

    @Test
    fun `exactly at the interval boundary is stale`() {
        assertTrue(metadata(lastVerifiedAt = 90_000).isStale(reverifyIntervalMs = 10_000, now = 100_000))
    }

    @Test
    fun `past the interval is stale`() {
        assertTrue(metadata(lastVerifiedAt = 50_000).isStale(reverifyIntervalMs = 10_000, now = 100_000))
    }
}

private class FakeCapabilityHealthChecker(
    private val result: (CapabilityId, String) -> CapabilityMetadata,
) : CapabilityHealthChecker {
    var callCount = 0
        private set
    var lastId: CapabilityId? = null
        private set
    var lastProviderId: String? = null
        private set

    override fun verify(id: CapabilityId, providerId: String): CapabilityMetadata {
        callCount++
        lastId = id
        lastProviderId = providerId
        return result(id, providerId)
    }

    override fun suggestedReverifyIntervalMs(providerId: String): Long = 10_000
}

class InMemoryCapabilityRegistryTest {
    @Test
    fun `getCapability returns null for an unregistered id`() {
        val registry = InMemoryCapabilityRegistry()
        assertNull(registry.getCapability(CapabilityId("android.notifications.read")))
    }

    @Test
    fun `register then getCapability returns the stored metadata`() {
        val registry = InMemoryCapabilityRegistry()
        val meta = metadata()
        registry.register(meta)
        assertEquals(meta, registry.getCapability(meta.id))
    }

    @Test
    fun `registering the same id twice replaces the stored metadata`() {
        val registry = InMemoryCapabilityRegistry()
        val id = CapabilityId("android.notifications.read")
        registry.register(metadata(id = id, state = CapabilityState.AVAILABLE))
        registry.register(metadata(id = id, state = CapabilityState.DISABLED))
        assertEquals(CapabilityState.DISABLED, registry.getCapability(id)?.state)
    }

    @Test
    fun `listCapabilities with the default filter returns everything registered`() {
        val registry = InMemoryCapabilityRegistry()
        registry.register(metadata(id = CapabilityId("a.one"), providerId = "android"))
        registry.register(metadata(id = CapabilityId("b.two"), providerId = "docker"))
        assertEquals(2, registry.listCapabilities().size)
    }

    @Test
    fun `listCapabilities filters by state`() {
        val registry = InMemoryCapabilityRegistry()
        registry.register(metadata(id = CapabilityId("a.one"), state = CapabilityState.AVAILABLE))
        registry.register(metadata(id = CapabilityId("b.two"), state = CapabilityState.DISABLED))
        val result = registry.listCapabilities(CapabilityFilter(state = CapabilityState.DISABLED))
        assertEquals(listOf(CapabilityId("b.two")), result.map { it.id })
    }

    @Test
    fun `listCapabilities filters by providerId`() {
        val registry = InMemoryCapabilityRegistry()
        registry.register(metadata(id = CapabilityId("a.one"), providerId = "android"))
        registry.register(metadata(id = CapabilityId("b.two"), providerId = "docker"))
        val result = registry.listCapabilities(CapabilityFilter(providerId = "docker"))
        assertEquals(listOf(CapabilityId("b.two")), result.map { it.id })
    }

    @Test
    fun `listCapabilities filters by riskTier`() {
        val registry = InMemoryCapabilityRegistry()
        registry.register(metadata(id = CapabilityId("a.one"), riskTier = RiskTier.READ_ONLY))
        registry.register(metadata(id = CapabilityId("b.two"), riskTier = RiskTier.DESTRUCTIVE))
        val result = registry.listCapabilities(CapabilityFilter(riskTier = RiskTier.DESTRUCTIVE))
        assertEquals(listOf(CapabilityId("b.two")), result.map { it.id })
    }

    @Test
    fun `listCapabilities combines multiple filter fields with AND`() {
        val registry = InMemoryCapabilityRegistry()
        registry.register(metadata(id = CapabilityId("a.one"), providerId = "android", state = CapabilityState.AVAILABLE))
        registry.register(metadata(id = CapabilityId("b.two"), providerId = "android", state = CapabilityState.DISABLED))
        registry.register(metadata(id = CapabilityId("c.three"), providerId = "docker", state = CapabilityState.AVAILABLE))
        val result = registry.listCapabilities(CapabilityFilter(providerId = "android", state = CapabilityState.AVAILABLE))
        assertEquals(listOf(CapabilityId("a.one")), result.map { it.id })
    }

    @Test
    fun `reverify on an unregistered id throws UnknownCapabilityException`() {
        val registry = InMemoryCapabilityRegistry(FakeCapabilityHealthChecker { id, providerId -> metadata(id = id, providerId = providerId) })
        assertFailsWith<UnknownCapabilityException> { registry.reverify(CapabilityId("android.notifications.read")) }
    }

    @Test
    fun `reverify with no health checker configured fails loudly`() {
        val registry = InMemoryCapabilityRegistry()
        val meta = metadata()
        registry.register(meta)
        assertFailsWith<IllegalStateException> { registry.reverify(meta.id) }
    }

    @Test
    fun `reverify calls the health checker with the registered provider id and stores the result`() {
        val checker = FakeCapabilityHealthChecker { id, providerId ->
            metadata(id = id, providerId = providerId, state = CapabilityState.ERROR, lastVerifiedAt = 42)
        }
        val registry = InMemoryCapabilityRegistry(checker)
        val original = metadata(providerId = "android", state = CapabilityState.AVAILABLE)
        registry.register(original)

        val result = registry.reverify(original.id)

        assertEquals(1, checker.callCount)
        assertEquals(original.id, checker.lastId)
        assertEquals("android", checker.lastProviderId)
        assertEquals(CapabilityState.ERROR, result.state)
        assertEquals(42, result.lastVerifiedAt)
        assertEquals(result, registry.getCapability(original.id))
    }

    @Test
    fun `invalidate on an unregistered id is a no-op`() {
        val registry = InMemoryCapabilityRegistry()
        registry.invalidate(CapabilityId("android.notifications.read"))
        assertNull(registry.getCapability(CapabilityId("android.notifications.read")))
    }

    @Test
    fun `invalidate clears lastVerifiedAt but keeps every other field`() {
        val registry = InMemoryCapabilityRegistry()
        val meta = metadata(lastVerifiedAt = 12345)
        registry.register(meta)

        registry.invalidate(meta.id)

        val result = registry.getCapability(meta.id)!!
        assertNull(result.lastVerifiedAt)
        assertEquals(meta.copy(lastVerifiedAt = null), result)
    }
}
