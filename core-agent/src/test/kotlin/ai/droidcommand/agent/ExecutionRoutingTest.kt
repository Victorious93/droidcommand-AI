package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class RecordingRouterLogger : Logger {
    val events = mutableListOf<LogEvent>()

    override fun log(event: LogEvent) {
        events.add(event)
    }
}

private class RecordingExecutor(private val response: ExecutionResponse) : CapabilityExecutor {
    var invocations = 0
    var lastRequest: ExecutionRequest? = null

    override fun execute(request: ExecutionRequest): ExecutionResponse {
        invocations++
        lastRequest = request
        return response
    }
}

class CapabilityIdTest {
    @Test
    fun `accepts a lowercase dotted capability id`() {
        assertEquals("android.tap", CapabilityId("android.tap").value)
    }

    @Test
    fun `accepts a hyphenated single-character id`() {
        assertEquals("a", CapabilityId("a").value)
        assertEquals("termux-exec", CapabilityId("termux-exec").value)
    }

    @Test
    fun `rejects an uppercase id`() {
        assertFailsWith<IllegalArgumentException> { CapabilityId("Android.Tap") }
    }

    @Test
    fun `rejects an id starting with a separator`() {
        assertFailsWith<IllegalArgumentException> { CapabilityId(".android") }
        assertFailsWith<IllegalArgumentException> { CapabilityId("-android") }
    }

    @Test
    fun `rejects an empty id`() {
        assertFailsWith<IllegalArgumentException> { CapabilityId("") }
    }

    @Test
    fun `rejects an id containing a space`() {
        assertFailsWith<IllegalArgumentException> { CapabilityId("android tap") }
    }
}

class DefaultExecutionRouterTest {
    private fun request(riskTier: RiskTier, id: String = "android.tap") = ExecutionRequest(
        capabilityId = CapabilityId(id),
        targetType = ExecutionTargetType.ANDROID,
        parameters = emptyMap(),
        riskTier = riskTier,
    )

    @Test
    fun `an unregistered capability returns CapabilityUnavailable and never invokes the executor`() {
        val executor = RecordingExecutor(ExecutionResponse.Success("ok", ExecutionTargetType.ANDROID, verified = true))
        val router = DefaultExecutionRouter(registry = CapabilityRegistry { false }, executor = executor)

        val response = router.route(request(RiskTier.READ_ONLY))

        val unavailable = assertIs<ExecutionResponse.CapabilityUnavailable>(response)
        assertEquals(CapabilityId("android.tap"), unavailable.capabilityId)
        assertEquals(0, executor.invocations)
    }

    @Test
    fun `a READ_ONLY request for a registered capability delegates to the executor`() {
        val executor = RecordingExecutor(ExecutionResponse.Success("ok", ExecutionTargetType.ANDROID, verified = true))
        val router = DefaultExecutionRouter(registry = CapabilityRegistry { true }, executor = executor)

        val response = router.route(request(RiskTier.READ_ONLY))

        assertEquals(ExecutionResponse.Success("ok", ExecutionTargetType.ANDROID, verified = true), response)
        assertEquals(1, executor.invocations)
    }

    @Test
    fun `a REVERSIBLE request for a registered capability also delegates by default`() {
        val executor = RecordingExecutor(ExecutionResponse.Success("ok", ExecutionTargetType.ANDROID, verified = false))
        val router = DefaultExecutionRouter(registry = CapabilityRegistry { true }, executor = executor)

        router.route(request(RiskTier.REVERSIBLE))

        assertEquals(1, executor.invocations)
    }

    @Test
    fun `a DESTRUCTIVE request for a registered capability requires approval and never invokes the executor`() {
        val executor = RecordingExecutor(ExecutionResponse.Success("ok", ExecutionTargetType.ANDROID, verified = true))
        val router = DefaultExecutionRouter(registry = CapabilityRegistry { true }, executor = executor)

        val response = router.route(request(RiskTier.DESTRUCTIVE))

        val requiresApproval = assertIs<ExecutionResponse.RequiresApproval>(response)
        assertEquals(RiskTier.DESTRUCTIVE, requiresApproval.riskTier)
        assertTrue(requiresApproval.operationDescription.isNotBlank())
        assertTrue(requiresApproval.requestId.isNotBlank())
        assertEquals(0, executor.invocations)
    }

    @Test
    fun `an IRREVERSIBLE request for a registered capability requires approval`() {
        val executor = RecordingExecutor(ExecutionResponse.Success("ok", ExecutionTargetType.ANDROID, verified = true))
        val router = DefaultExecutionRouter(registry = CapabilityRegistry { true }, executor = executor)

        val response = router.route(request(RiskTier.IRREVERSIBLE))

        assertIs<ExecutionResponse.RequiresApproval>(response)
        assertEquals(0, executor.invocations)
    }

    @Test
    fun `a custom autoApprove set can widen approval to DESTRUCTIVE`() {
        val executor = RecordingExecutor(ExecutionResponse.Success("ok", ExecutionTargetType.ANDROID, verified = true))
        val router = DefaultExecutionRouter(
            registry = CapabilityRegistry { true },
            executor = executor,
            autoApprove = setOf(RiskTier.READ_ONLY, RiskTier.REVERSIBLE, RiskTier.DESTRUCTIVE),
        )

        val response = router.route(request(RiskTier.DESTRUCTIVE))

        assertIs<ExecutionResponse.Success>(response)
        assertEquals(1, executor.invocations)
    }

    @Test
    fun `a custom requestIdGenerator is honored`() {
        val executor = RecordingExecutor(ExecutionResponse.Success("ok", ExecutionTargetType.ANDROID, verified = true))
        val router = DefaultExecutionRouter(
            registry = CapabilityRegistry { true },
            executor = executor,
            requestIdGenerator = { "fixed-request-id" },
        )

        val response = router.route(request(RiskTier.DESTRUCTIVE))

        val requiresApproval = assertIs<ExecutionResponse.RequiresApproval>(response)
        assertEquals("fixed-request-id", requiresApproval.requestId)
    }

    @Test
    fun `the executor receives the exact request unchanged`() {
        val executor = RecordingExecutor(ExecutionResponse.Success("ok", ExecutionTargetType.ANDROID, verified = true))
        val router = DefaultExecutionRouter(registry = CapabilityRegistry { true }, executor = executor)
        val req = request(RiskTier.READ_ONLY)

        router.route(req)

        assertEquals(req, executor.lastRequest)
    }

    @Test
    fun `an unregistered capability logs exactly one WARN event`() {
        val logger = RecordingRouterLogger()
        val executor = RecordingExecutor(ExecutionResponse.Success("ok", ExecutionTargetType.ANDROID, verified = true))
        val router = DefaultExecutionRouter(registry = CapabilityRegistry { false }, executor = executor, logger = logger)

        router.route(request(RiskTier.READ_ONLY))

        assertEquals(1, logger.events.size)
        assertEquals(LogLevel.WARN, logger.events.single().level)
        assertEquals("capability_unavailable", logger.events.single().message)
    }

    @Test
    fun `a requires-approval path logs exactly one INFO event`() {
        val logger = RecordingRouterLogger()
        val executor = RecordingExecutor(ExecutionResponse.Success("ok", ExecutionTargetType.ANDROID, verified = true))
        val router = DefaultExecutionRouter(registry = CapabilityRegistry { true }, executor = executor, logger = logger)

        router.route(request(RiskTier.DESTRUCTIVE))

        assertEquals(1, logger.events.size)
        assertEquals(LogLevel.INFO, logger.events.single().level)
        assertEquals("requires_approval", logger.events.single().message)
    }

    @Test
    fun `a delegated execution logs exactly one INFO event`() {
        val logger = RecordingRouterLogger()
        val executor = RecordingExecutor(ExecutionResponse.Success("ok", ExecutionTargetType.ANDROID, verified = true))
        val router = DefaultExecutionRouter(registry = CapabilityRegistry { true }, executor = executor, logger = logger)

        router.route(request(RiskTier.READ_ONLY))

        assertEquals(1, logger.events.size)
        assertEquals(LogLevel.INFO, logger.events.single().level)
        assertEquals("execution_delegated", logger.events.single().message)
    }

    @Test
    fun `a real executor's own Denied response is returned unchanged, not second-guessed`() {
        val executor = RecordingExecutor(ExecutionResponse.Denied("target-specific refusal", suggestedAlternative = "try TERMUX"))
        val router = DefaultExecutionRouter(registry = CapabilityRegistry { true }, executor = executor)

        val response = router.route(request(RiskTier.READ_ONLY))

        assertEquals(ExecutionResponse.Denied("target-specific refusal", "try TERMUX"), response)
    }
}
