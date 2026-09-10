package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenBudgetManagerTest {
    private val manager = DefaultTokenBudgetManager()

    @Test
    fun `selectBudget picks LIGHTWEIGHT for a short task with no dependencies or criteria`() {
        val task = Task("t1", "Fix a typo")
        assertEquals(TokenBudget.LIGHTWEIGHT, manager.selectBudget(task))
    }

    @Test
    fun `selectBudget escalates as description length grows`() {
        val short = Task("t1", "Fix a typo")
        val long = Task("t1", "x".repeat(3000))

        assertEquals(TokenBudget.LIGHTWEIGHT, manager.selectBudget(short))
        assertEquals(TokenBudget.FULL, manager.selectBudget(long))
    }

    @Test
    fun `selectBudget escalates as dependency count grows`() {
        val noDeps = Task("t1", "Do the thing")
        val manyDeps = Task("t1", "Do the thing", dependencies = setOf("a", "b", "c"))

        assertEquals(TokenBudget.LIGHTWEIGHT, manager.selectBudget(noDeps))
        assertEquals(TokenBudget.FULL, manager.selectBudget(manyDeps))
    }

    @Test
    fun `selectBudget escalates as verification criteria count grows`() {
        val noCriteria = Task("t1", "Do the thing")
        val manyCriteria = Task("t1", "Do the thing", verificationCriteria = listOf("a", "b", "c", "d", "e", "f"))

        assertEquals(TokenBudget.LIGHTWEIGHT, manager.selectBudget(noCriteria))
        assertEquals(TokenBudget.FULL, manager.selectBudget(manyCriteria))
    }

    @Test
    fun `selectBudget is deterministic across repeated calls`() {
        val task = Task("t1", "Do the thing", dependencies = setOf("a"), verificationCriteria = listOf("x"))
        assertEquals(manager.selectBudget(task), manager.selectBudget(task))
    }

    @Test
    fun `selectBudget respects custom thresholds`() {
        val task = Task("t1", "Do the thing") // score = estimateTokens("Do the thing") = 4
        val strict = DefaultTokenBudgetManager(ComplexityThresholds(lightweightMax = 1))

        assertEquals(TokenBudget.LIGHTWEIGHT, manager.selectBudget(task))
        assertEquals(TokenBudget.BALANCED, strict.selectBudget(task))
    }

    @Test
    fun `allocateTokens re-slices an already-built snapshot down to a smaller budget`() {
        val contextManager = DefaultContextManager()
        contextManager.registerProvider(ContextKind.KNOWLEDGE) {
            ContextContribution(ContextKind.KNOWLEDGE, "kg", "y".repeat(4000)) // ~1000 tokens
        }
        val task = Task("t1", "Do the thing")
        val snapshot = contextManager.buildContext(task, TokenBudget.FULL.tokens)
        assertTrue(snapshot.included.any { it.kind == ContextKind.KNOWLEDGE })

        val allocated = manager.allocateTokens(snapshot, TokenBudget.LIGHTWEIGHT)

        assertEquals(TokenBudget.LIGHTWEIGHT, allocated.budget)
        assertTrue(allocated.omitted.any { it.kind == ContextKind.KNOWLEDGE })
        assertTrue(allocated.included.any { it.kind == ContextKind.TASK })
        assertTrue(allocated.included.none { it.kind == ContextKind.KNOWLEDGE })
        assertEquals(TokenBudget.LIGHTWEIGHT.tokens - allocated.tokenBudgetUsed, allocated.tokenBudgetRemaining)
    }

    @Test
    fun `mandatory contributions survive a very small re-allocation`() {
        val contextManager = DefaultContextManager()
        val hugeTask = Task("huge", "y".repeat(8000))
        val snapshot = contextManager.buildContext(hugeTask, TokenBudget.FULL.tokens)

        val allocated = manager.allocateTokens(snapshot, TokenBudget.LIGHTWEIGHT)

        assertTrue(allocated.included.any { it.kind == ContextKind.TASK })
        assertTrue(allocated.tokenBudgetUsed > allocated.budget.tokens)
    }

    @Test
    fun `allocateTokens cannot recover contributions the original build already omitted`() {
        val contextManager = DefaultContextManager()
        contextManager.registerProvider(ContextKind.KNOWLEDGE) {
            ContextContribution(ContextKind.KNOWLEDGE, "kg", "y".repeat(4000)) // ~1000 tokens, won't fit LIGHTWEIGHT
        }
        val task = Task("t1", "Do the thing")
        val narrowSnapshot = contextManager.buildContext(task, TokenBudget.LIGHTWEIGHT.tokens)
        assertTrue(narrowSnapshot.omitted.any { it.kind == ContextKind.KNOWLEDGE })

        val reallocated = manager.allocateTokens(narrowSnapshot, TokenBudget.FULL)

        assertTrue(reallocated.omitted.any { it.kind == ContextKind.KNOWLEDGE })
        assertTrue(reallocated.included.none { it.kind == ContextKind.KNOWLEDGE })
    }

    @Test
    fun `inspectAllocation is null before any allocateTokens call and reflects the last one after`() {
        val fresh = DefaultTokenBudgetManager()
        assertNull(fresh.inspectAllocation())

        val contextManager = DefaultContextManager()
        val task = Task("t1", "Do the thing")
        val snapshot = contextManager.buildContext(task, TokenBudget.FULL.tokens)
        fresh.allocateTokens(snapshot, TokenBudget.BALANCED)

        val report = fresh.inspectAllocation()!!
        assertEquals(TokenBudget.BALANCED, report.budgetSelected)
        assertEquals("truncate", report.overflowStrategy)
    }

    @Test
    fun `selectBudget alone does not affect inspectAllocation`() {
        val fresh = DefaultTokenBudgetManager()
        fresh.selectBudget(Task("t1", "Do the thing"))
        assertNull(fresh.inspectAllocation())
    }
}
