package ai.droidcommand.agent.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContextBudgetAndUsageTest {
    @Test
    fun `preset is capped by what the context window leaves free`() {
        assertEquals(3072, ContextBudgetPlanner.memoryBudget(MemoryBudgetPreset.TOKENS_3072, 32_000, 500, 500, 500, 4_000))
        assertEquals(1_000, ContextBudgetPlanner.memoryBudget(MemoryBudgetPreset.TOKENS_16384, 8_000, 1_000, 1_000, 1_000, 4_000))
    }

    @Test
    fun `nothing free means a zero budget, never negative`() {
        assertEquals(0, ContextBudgetPlanner.memoryBudget(MemoryBudgetPreset.TOKENS_1024, 4_096, 2_000, 1_000, 1_000, 1_000))
    }

    @Test
    fun `auto is a quarter of the free window, capped`() {
        assertEquals(2_000, ContextBudgetPlanner.memoryBudget(MemoryBudgetPreset.AUTO, 10_000, 0, 0, 0, 2_000))
        assertEquals(16_384, ContextBudgetPlanner.memoryBudget(MemoryBudgetPreset.AUTO, 1_000_000, 0, 0, 0, 0))
    }

    @Test
    fun `tracker keeps estimates and provider-reported usage separate and reports no rate without data`() {
        val t = TokenUsageTracker()
        assertNull(t.summary().cacheHitRate)
        assertNull(t.summary().averageRetrievalMillis)
        t.record(TokenUsageRecord(100, 70, 200, 30, reportedInputTokens = 310, cacheHit = true, retrievalMillis = 4))
        t.record(TokenUsageRecord(50, 50, 0, 10, cacheHit = false, retrievalMillis = 6))
        val s = t.summary()
        assertEquals(2, s.requests)
        assertEquals(300L + 60L, s.estimatedTotalInputTokens)
        assertEquals(30L, s.estimatedPromptSavings)
        assertEquals(310L, s.reportedInputTokens)
        assertEquals(1, s.requestsWithReportedUsage)
        assertEquals(0.5, s.cacheHitRate)
        assertEquals(5.0, s.averageRetrievalMillis)
    }

    @Test
    fun `tracker is bounded`() {
        val t = TokenUsageTracker(maxRecords = 3)
        repeat(10) { t.record(TokenUsageRecord(it, it, 0, 0)) }
        assertEquals(listOf(7, 8, 9), t.all().map { it.estimatedOriginalPromptTokens })
    }
}
