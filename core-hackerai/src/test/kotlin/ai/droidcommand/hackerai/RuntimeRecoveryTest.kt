package ai.droidcommand.hackerai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeRecoveryTest {
    @Test
    fun `rate_limited is transient and recoverable`() {
        assertTrue(isTransientProviderCategory(ProviderErrorCategory.RATE_LIMITED))
        assertTrue(isRecoverableProviderCategory(ProviderErrorCategory.RATE_LIMITED))
    }

    @Test
    fun `content_blocked is recoverable but not transient`() {
        assertFalse(isTransientProviderCategory(ProviderErrorCategory.CONTENT_BLOCKED))
        assertTrue(isRecoverableProviderCategory(ProviderErrorCategory.CONTENT_BLOCKED))
    }

    @Test
    fun `unknown is neither transient nor recoverable`() {
        assertFalse(isTransientProviderCategory(ProviderErrorCategory.UNKNOWN))
        assertFalse(isRecoverableProviderCategory(ProviderErrorCategory.UNKNOWN))
    }

    @Test
    fun `shouldRetry true when under max retries for recoverable category`() {
        val decision = getSubagentProviderRetryDecision(ProviderErrorCategory.RATE_LIMITED, 0)
        assertTrue(decision.shouldRetry)
        assertTrue(decision.delayMs > 0)
    }

    @Test
    fun `shouldRetry false when at max retries`() {
        val decision = getSubagentProviderRetryDecision(
            ProviderErrorCategory.RATE_LIMITED,
            SUBAGENT_MAX_PROVIDER_RECOVERY_RETRIES,
        )
        assertFalse(decision.shouldRetry)
        assertEquals(0L, decision.delayMs)
    }

    @Test
    fun `shouldRetry false for unknown category`() {
        val decision = getSubagentProviderRetryDecision(ProviderErrorCategory.UNKNOWN, 0)
        assertFalse(decision.shouldRetry)
    }

    @Test
    fun `classifyProviderError maps 429 to rate_limited`() {
        assertEquals(ProviderErrorCategory.RATE_LIMITED, classifyProviderError(429, null))
    }

    @Test
    fun `classifyProviderError maps 5xx to provider_5xx`() {
        assertEquals(ProviderErrorCategory.PROVIDER_5XX, classifyProviderError(500, null))
        assertEquals(ProviderErrorCategory.PROVIDER_5XX, classifyProviderError(503, null))
    }

    @Test
    fun `classifyProviderError maps timeout exception`() {
        assertEquals(ProviderErrorCategory.TIMEOUT, classifyProviderError(null, "SocketTimeoutException"))
    }
}
