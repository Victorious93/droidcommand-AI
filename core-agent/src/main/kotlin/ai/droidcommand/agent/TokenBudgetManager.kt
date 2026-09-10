package ai.droidcommand.agent

/** The four budget profiles `docs/CAPABILITY_ROADMAP_PROMPT.md`'s P0.2 (CAP-002) names, verbatim. */
enum class TokenBudget(val tokens: Int) {
    LIGHTWEIGHT(1000),
    BALANCED(3000),
    COMPREHENSIVE(8000),
    FULL(16000),
}

/**
 * The result of [TokenBudgetManager.allocateTokens]: a [ContextSnapshot]
 * re-sliced to fit one [budget]. Not defined anywhere in the roadmap
 * prompt (only referenced as `allocateTokens`'s return type) — designed
 * here to mirror [ContextSnapshot] itself, scoped to a [TokenBudget]
 * instead of a raw token count.
 */
data class AllocatedContext(
    val task: Task?,
    val budget: TokenBudget,
    val included: List<ContextContribution>,
    val omitted: List<ContextContribution>,
    val tokenBudgetUsed: Int,
    val tokenBudgetRemaining: Int,
)

/**
 * Visibility into the most recent [TokenBudgetManager.allocateTokens] call
 * (P0.2's "provide token/context visibility where practical" requirement).
 * [overflowStrategy] is always `"truncate"` for [DefaultTokenBudgetManager]:
 * a contribution that doesn't fit is dropped whole, never shortened
 * ("truncate", one of the three literal strategies the roadmap prompt's own
 * comment names) or summarized ("summarize" needs a live LLM call, which
 * this dependency-free module correctly has none of).
 */
data class AllocationReport(
    val budgetSelected: TokenBudget,
    val contributions: List<ContextContribution>,
    val totalUsed: Int,
    val remaining: Int,
    val overflowStrategy: String,
)

/**
 * Selects a [TokenBudget] for a [Task] and re-allocates an already-built
 * [ContextSnapshot] (CAP-001) to fit one (CAP-002, P0.2).
 *
 * **Deliberate deviation from the roadmap prompt's literal (non-nullable)
 * `inspectAllocation(): AllocationReport` signature:** this returns
 * [AllocationReport]`?`, `null` before [allocateTokens] has ever been
 * called — the same honest "nothing built yet" signal
 * [ContextInspection.lastSnapshot] already uses. Fabricating a placeholder
 * report with an arbitrary [AllocationReport.budgetSelected] before any
 * real allocation happened would misrepresent state.
 */
interface TokenBudgetManager {
    fun selectBudget(task: Task): TokenBudget

    fun allocateTokens(snapshot: ContextSnapshot, budget: TokenBudget): AllocatedContext

    fun inspectAllocation(): AllocationReport?
}

/**
 * The break points [DefaultTokenBudgetManager.selectBudget] maps a
 * complexity score onto: a score below [lightweightMax] selects
 * [TokenBudget.LIGHTWEIGHT], below [balancedMax] selects
 * [TokenBudget.BALANCED], below [comprehensiveMax] selects
 * [TokenBudget.COMPREHENSIVE], and anything at or above [comprehensiveMax]
 * selects [TokenBudget.FULL]. Constructor-injectable (matching
 * `MagiskProvider`'s injectable-paths precedent) so a caller can tune
 * these without a code change; the defaults are a first, reasonable-but-
 * arbitrary cut, not a proven-correct calibration.
 */
data class ComplexityThresholds(
    val lightweightMax: Int = 50,
    val balancedMax: Int = 200,
    val comprehensiveMax: Int = 600,
)

/**
 * The real [TokenBudgetManager] implementation.
 *
 * **`selectBudget`, honestly scoped as a mechanical heuristic, not a
 * classifier:** a [Task] carries no semantic complexity signal today —
 * only a description, a dependency set, and verification criteria — so the
 * complexity score is a deterministic function of those three real,
 * measurable fields: [estimateTokens] of the description (the same
 * ~4-chars/token heuristic [ConversationContext] already uses and
 * documents as an honest approximation, never an exact count), plus 200
 * per dependency (a task wired into a larger decomposed objective needs
 * more surrounding context to reason about correctly), plus 100 per
 * verification criterion (each one is a real thing a caller needs context
 * to check against). This has no understanding of what the task actually
 * *means* — a genuinely LLM-informed classifier is real future work for
 * `core-llm` (a second [TokenBudgetManager] implementation, the same way
 * `ModelRouter` composes multiple `LlmProvider`s), not attempted here.
 *
 * **`allocateTokens` reuses [allocateByPriority] — the exact algorithm
 * [DefaultContextManager.buildContext] already runs — rather than a second,
 * duplicate implementation.** It re-slices [ContextSnapshot.included]
 * (already priority-ordered) down to [budget]; newly-excluded contributions
 * join [ContextSnapshot.omitted] in the result. **A stated, honest
 * limitation:** this can only *narrow* what `buildContext` already
 * gathered — a contribution `buildContext`'s own original budget already
 * excluded was never fetched into `included` and can never be recovered
 * here, even by allocating to a larger [budget] than the snapshot was
 * built with. A caller wanting headroom to try multiple budgets against
 * one gathered snapshot should build once at [TokenBudget.FULL]`.tokens`
 * (the widest defined profile) and reallocate down from there.
 */
class DefaultTokenBudgetManager(private val thresholds: ComplexityThresholds = ComplexityThresholds()) : TokenBudgetManager {
    @Volatile
    private var lastReport: AllocationReport? = null

    override fun selectBudget(task: Task): TokenBudget {
        val score = estimateTokens(task.description) +
            task.dependencies.size * DEPENDENCY_WEIGHT +
            task.verificationCriteria.size * CRITERION_WEIGHT
        return when {
            score < thresholds.lightweightMax -> TokenBudget.LIGHTWEIGHT
            score < thresholds.balancedMax -> TokenBudget.BALANCED
            score < thresholds.comprehensiveMax -> TokenBudget.COMPREHENSIVE
            else -> TokenBudget.FULL
        }
    }

    override fun allocateTokens(snapshot: ContextSnapshot, budget: TokenBudget): AllocatedContext {
        val allocation = allocateByPriority(snapshot.included, budget.tokens)
        val remaining = (budget.tokens - allocation.used).coerceAtLeast(0)

        lastReport = AllocationReport(
            budgetSelected = budget,
            contributions = allocation.included,
            totalUsed = allocation.used,
            remaining = remaining,
            overflowStrategy = "truncate",
        )

        return AllocatedContext(
            task = snapshot.task,
            budget = budget,
            included = allocation.included,
            omitted = allocation.omitted + snapshot.omitted,
            tokenBudgetUsed = allocation.used,
            tokenBudgetRemaining = remaining,
        )
    }

    override fun inspectAllocation(): AllocationReport? = lastReport

    private companion object {
        const val DEPENDENCY_WEIGHT = 200
        const val CRITERION_WEIGHT = 100
    }
}
