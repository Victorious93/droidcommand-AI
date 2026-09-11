package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ScriptedHealthChecker(
    private val response: (CapabilityId, String) -> CapabilityMetadata,
    private val intervalMs: Long = 10_000,
) : CapabilityHealthChecker {
    var invocations = 0
        private set
    var lastId: CapabilityId? = null
    var lastProviderId: String? = null

    override fun verify(id: CapabilityId, providerId: String): CapabilityMetadata {
        invocations++
        lastId = id
        lastProviderId = providerId
        return response(id, providerId)
    }

    override fun suggestedReverifyIntervalMs(providerId: String): Long = intervalMs
}

private fun metadata(
    id: CapabilityId,
    providerId: String = "android",
    state: CapabilityState = CapabilityState.AVAILABLE,
    lastVerifiedAt: Long? = 1_000L,
) = CapabilityMetadata(
    id = id,
    providerId = providerId,
    version = "1.0.0",
    state = state,
    permissionsRequired = emptyList(),
    dependencies = emptyList(),
    lastVerifiedAt = lastVerifiedAt,
    lastError = null,
    description = "test capability",
    riskTier = RiskTier.READ_ONLY,
)

class CapabilityManagerTest {
    private fun newManager(intervalMs: Long = 10_000, response: (CapabilityId, String) -> CapabilityMetadata = { id, p -> metadata(id, p) }) =
        DefaultCapabilityManager(ScriptedHealthChecker(response, intervalMs))

    @Test
    fun `register then getCapability returns an equal value`() {
        val manager = newManager()
        val m = metadata(CapabilityId("android.tap"))

        manager.register(m)

        assertEquals(m, manager.getCapability(CapabilityId("android.tap")))
    }

    @Test
    fun `re-registering the same id overwrites the previous value`() {
        val manager = newManager()
        val id = CapabilityId("android.tap")
        manager.register(metadata(id, state = CapabilityState.DISABLED))
        manager.register(metadata(id, state = CapabilityState.AVAILABLE))

        assertEquals(CapabilityState.AVAILABLE, manager.getCapability(id)!!.state)
    }

    @Test
    fun `getCapability of an unregistered id returns null`() {
        assertNull(newManager().getCapability(CapabilityId("nope")))
    }

    @Test
    fun `listCapabilities with no filter returns everything sorted by id`() {
        val manager = newManager()
        manager.register(metadata(CapabilityId("b.cap")))
        manager.register(metadata(CapabilityId("a.cap")))

        assertEquals(listOf("a.cap", "b.cap"), manager.listCapabilities().map { it.id.value })
    }

    @Test
    fun `listCapabilities filters by state`() {
        val manager = newManager()
        manager.register(metadata(CapabilityId("a.cap"), state = CapabilityState.AVAILABLE))
        manager.register(metadata(CapabilityId("b.cap"), state = CapabilityState.DISABLED))

        val result = manager.listCapabilities(CapabilityFilter(state = CapabilityState.AVAILABLE))

        assertEquals(listOf("a.cap"), result.map { it.id.value })
    }

    @Test
    fun `listCapabilities filters by providerId`() {
        val manager = newManager()
        manager.register(metadata(CapabilityId("a.cap"), providerId = "android"))
        manager.register(metadata(CapabilityId("b.cap"), providerId = "termux"))

        val result = manager.listCapabilities(CapabilityFilter(providerId = "termux"))

        assertEquals(listOf("b.cap"), result.map { it.id.value })
    }

    @Test
    fun `listCapabilities with a combined filter narrows to the intersection`() {
        val manager = newManager()
        manager.register(metadata(CapabilityId("a.cap"), providerId = "android", state = CapabilityState.AVAILABLE))
        manager.register(metadata(CapabilityId("b.cap"), providerId = "android", state = CapabilityState.DISABLED))
        manager.register(metadata(CapabilityId("c.cap"), providerId = "termux", state = CapabilityState.AVAILABLE))

        val result = manager.listCapabilities(CapabilityFilter(state = CapabilityState.AVAILABLE, providerId = "android"))

        assertEquals(listOf("a.cap"), result.map { it.id.value })
    }

    @Test
    fun `reverify calls the health checker with the stored providerId and persists the result`() {
        val id = CapabilityId("android.tap")
        val manager = newManager(response = { i, p -> metadata(i, p, state = CapabilityState.AVAILABLE) })
        manager.register(metadata(id, providerId = "android", state = CapabilityState.REQUIRES_PERMISSION))

        val result = manager.reverify(id)

        assertEquals(CapabilityState.AVAILABLE, result.state)
        assertEquals(CapabilityState.AVAILABLE, manager.getCapability(id)!!.state)
    }

    @Test
    fun `reverify of an unregistered id throws without invoking the health checker`() {
        val checker = ScriptedHealthChecker({ i, p -> metadata(i, p) })
        val manager = DefaultCapabilityManager(checker)

        val e = assertFailsWith<UnknownCapabilityException> { manager.reverify(CapabilityId("nope")) }

        assertEquals(CapabilityId("nope"), e.id)
        assertEquals(0, checker.invocations)
    }

    @Test
    fun `invalidate clears lastVerifiedAt for a registered id`() {
        val manager = newManager()
        val id = CapabilityId("android.tap")
        manager.register(metadata(id, lastVerifiedAt = 5_000L))

        manager.invalidate(id)

        assertNull(manager.getCapability(id)!!.lastVerifiedAt)
    }

    @Test
    fun `invalidate of an unregistered id is a no-op, not a throw`() {
        newManager().invalidate(CapabilityId("nope"))
    }

    @Test
    fun `isStale is true when lastVerifiedAt is null`() {
        val manager = newManager()
        val id = CapabilityId("android.tap")
        manager.register(metadata(id, lastVerifiedAt = null))

        assertTrue(manager.isStale(id, referenceTimeMs = 100_000L))
    }

    @Test
    fun `isStale is false within the suggested reverify interval`() {
        val manager = newManager(intervalMs = 10_000)
        val id = CapabilityId("android.tap")
        manager.register(metadata(id, lastVerifiedAt = 1_000L))

        assertFalse(manager.isStale(id, referenceTimeMs = 5_000L))
    }

    @Test
    fun `isStale is true once past the suggested reverify interval`() {
        val manager = newManager(intervalMs = 10_000)
        val id = CapabilityId("android.tap")
        manager.register(metadata(id, lastVerifiedAt = 1_000L))

        assertTrue(manager.isStale(id, referenceTimeMs = 20_000L))
    }

    @Test
    fun `isStale of an unregistered id is false`() {
        assertFalse(newManager().isStale(CapabilityId("nope")))
    }

    @Test
    fun `isRegistered is true only for AVAILABLE or ENABLED states`() {
        val manager = newManager()
        manager.register(metadata(CapabilityId("a"), state = CapabilityState.AVAILABLE))
        manager.register(metadata(CapabilityId("b"), state = CapabilityState.ENABLED))
        manager.register(metadata(CapabilityId("c"), state = CapabilityState.DISABLED))
        manager.register(metadata(CapabilityId("d"), state = CapabilityState.REQUIRES_ROOT))
        manager.register(metadata(CapabilityId("e"), state = CapabilityState.ERROR))

        assertTrue(manager.isRegistered(CapabilityId("a")))
        assertTrue(manager.isRegistered(CapabilityId("b")))
        assertFalse(manager.isRegistered(CapabilityId("c")))
        assertFalse(manager.isRegistered(CapabilityId("d")))
        assertFalse(manager.isRegistered(CapabilityId("e")))
    }

    @Test
    fun `isRegistered is false for an unregistered id`() {
        assertFalse(newManager().isRegistered(CapabilityId("nope")))
    }

    @Test
    fun `an unregistered capability routes to CapabilityUnavailable through a real DefaultCapabilityManager`() {
        val id = CapabilityId("shell.exec")
        val manager = newManager()
        var invocations = 0
        val executor = CapabilityExecutor {
            invocations++
            ExecutionResponse.Success("ran", ExecutionTargetType.LOCAL_PC, verified = true)
        }
        val router = DefaultExecutionRouter(registry = manager, executor = executor)
        val request = ExecutionRequest(id, ExecutionTargetType.LOCAL_PC, emptyMap(), RiskTier.READ_ONLY)

        assertIs<ExecutionResponse.CapabilityUnavailable>(router.route(request))
        assertEquals(0, invocations)
    }

    @Test
    fun `a DISABLED capability still routes to CapabilityUnavailable through a real DefaultCapabilityManager`() {
        val id = CapabilityId("shell.exec")
        val manager = newManager()
        manager.register(metadata(id, state = CapabilityState.DISABLED))
        var invocations = 0
        val executor = CapabilityExecutor {
            invocations++
            ExecutionResponse.Success("ran", ExecutionTargetType.LOCAL_PC, verified = true)
        }
        val router = DefaultExecutionRouter(registry = manager, executor = executor)
        val request = ExecutionRequest(id, ExecutionTargetType.LOCAL_PC, emptyMap(), RiskTier.READ_ONLY)

        assertIs<ExecutionResponse.CapabilityUnavailable>(router.route(request))
        assertEquals(0, invocations)
    }

    @Test
    fun `an AVAILABLE capability routes through to the executor via a real DefaultCapabilityManager`() {
        val id = CapabilityId("shell.exec")
        val manager = newManager()
        manager.register(metadata(id, state = CapabilityState.AVAILABLE))
        var invocations = 0
        val executor = CapabilityExecutor {
            invocations++
            ExecutionResponse.Success("ran", ExecutionTargetType.LOCAL_PC, verified = true)
        }
        val router = DefaultExecutionRouter(registry = manager, executor = executor)
        val request = ExecutionRequest(id, ExecutionTargetType.LOCAL_PC, emptyMap(), RiskTier.READ_ONLY)

        val response = router.route(request)

        assertEquals(ExecutionResponse.Success("ran", ExecutionTargetType.LOCAL_PC, verified = true), response)
        assertEquals(1, invocations)
    }
}
