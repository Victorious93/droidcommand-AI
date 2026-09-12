package ai.droidcommand.security

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class RecordingApprovalAuditLog : AuditLog {
    val events = mutableListOf<AuditEvent>()
    override fun record(event: AuditEvent): Boolean {
        events += event
        return true
    }
}

private fun request(
    riskTier: RiskTier = RiskTier.DESTRUCTIVE,
    timeoutMs: Long = 1_000,
) = ApprovalRequest(
    requestId = "req-1",
    operationDescription = "delete everything",
    riskTier = riskTier,
    targetType = "local_process",
    toolId = "rm",
    capabilityId = "root",
    timeoutMs = timeoutMs,
)

class RiskApprovalPolicyTest {
    @Test
    fun `READ_ONLY never requires approval`() {
        assertFalse(RiskApprovalPolicy.requiresApproval(RiskTier.READ_ONLY))
    }

    @Test
    fun `REVERSIBLE, DESTRUCTIVE and IRREVERSIBLE all require approval`() {
        assertTrue(RiskApprovalPolicy.requiresApproval(RiskTier.REVERSIBLE))
        assertTrue(RiskApprovalPolicy.requiresApproval(RiskTier.DESTRUCTIVE))
        assertTrue(RiskApprovalPolicy.requiresApproval(RiskTier.IRREVERSIBLE))
    }

    @Test
    fun `default timeouts scale with risk`() {
        assertEquals(0L, RiskApprovalPolicy.defaultTimeoutMs(RiskTier.READ_ONLY))
        assertEquals(30_000L, RiskApprovalPolicy.defaultTimeoutMs(RiskTier.REVERSIBLE))
        assertEquals(120_000L, RiskApprovalPolicy.defaultTimeoutMs(RiskTier.DESTRUCTIVE))
        assertEquals(300_000L, RiskApprovalPolicy.defaultTimeoutMs(RiskTier.IRREVERSIBLE))
    }
}

class TimeoutApprovalProviderTest {
    @Test
    fun `returns Approved when the prompt approves within the timeout`() {
        val provider = TimeoutApprovalProvider(ApprovalPrompt { true })
        assertIs<ApprovalResponse.Approved>(provider.requestApproval(request()))
    }

    @Test
    fun `returns Denied when the prompt denies within the timeout`() {
        val provider = TimeoutApprovalProvider(ApprovalPrompt { false })
        assertIs<ApprovalResponse.Denied>(provider.requestApproval(request()))
    }

    @Test
    fun `returns Unavailable when the prompt throws`() {
        val provider = TimeoutApprovalProvider(ApprovalPrompt { throw IllegalStateException("no UI attached") })
        assertIs<ApprovalResponse.Unavailable>(provider.requestApproval(request()))
    }

    @Test
    fun `returns TimedOut, and returns promptly, when the prompt never responds in time`() {
        val neverReturns = ApprovalPrompt {
            CountDownLatch(1).await() // blocks forever; the provider must not wait for it
            true
        }
        val provider = TimeoutApprovalProvider(neverReturns)

        val startedAt = System.nanoTime()
        val response = provider.requestApproval(request(timeoutMs = 100))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertIs<ApprovalResponse.TimedOut>(response)
        assertTrue(elapsedMs < 5_000, "expected a bounded wait near the 100ms timeout, took ${elapsedMs}ms")
    }

    @Test
    fun `a non-positive timeout times out immediately rather than blocking forever`() {
        val neverReturns = ApprovalPrompt {
            CountDownLatch(1).await()
            true
        }
        val provider = TimeoutApprovalProvider(neverReturns)

        val startedAt = System.nanoTime()
        val response = provider.requestApproval(request(riskTier = RiskTier.READ_ONLY, timeoutMs = 0))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertIs<ApprovalResponse.TimedOut>(response)
        assertTrue(elapsedMs < 5_000, "expected an immediate return, took ${elapsedMs}ms")
    }

    @Test
    fun `records an APPROVAL_APPROVED event naming the request id and tool, not the prompt's own detail`() {
        val auditLog = RecordingApprovalAuditLog()
        val provider = TimeoutApprovalProvider(ApprovalPrompt { true }, auditLog)

        provider.requestApproval(request())

        val event = auditLog.events.single()
        assertEquals(AuditEventType.APPROVAL_APPROVED, event.type)
        assertEquals("tool:rm", event.subject)
        assertTrue(event.detail.contains("req-1"))
    }

    @Test
    fun `records a distinct APPROVAL_TIMED_OUT event, not conflated with APPROVAL_DENIED`() {
        val auditLog = RecordingApprovalAuditLog()
        val neverReturns = ApprovalPrompt {
            CountDownLatch(1).await()
            true
        }
        val provider = TimeoutApprovalProvider(neverReturns, auditLog)

        provider.requestApproval(request(timeoutMs = 50))

        val event = auditLog.events.single()
        assertEquals(AuditEventType.APPROVAL_TIMED_OUT, event.type)
    }

    @Test
    fun `records a distinct APPROVAL_UNAVAILABLE event when the prompt throws`() {
        val auditLog = RecordingApprovalAuditLog()
        val provider = TimeoutApprovalProvider(ApprovalPrompt { throw IllegalStateException("boom") }, auditLog)

        provider.requestApproval(request())

        val event = auditLog.events.single()
        assertEquals(AuditEventType.APPROVAL_UNAVAILABLE, event.type)
    }

    @Test
    fun `with no audit log given, nothing is logged and behavior is unchanged`() {
        val provider = TimeoutApprovalProvider(ApprovalPrompt { true })
        assertIs<ApprovalResponse.Approved>(provider.requestApproval(request()))
    }
}
