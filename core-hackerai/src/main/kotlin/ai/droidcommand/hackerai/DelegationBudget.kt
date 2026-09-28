package ai.droidcommand.hackerai

// Ported from hackeraiETC/lib/ai/subagents/delegation-budget.ts

private const val MIN_SUBAGENT_BUDGET_FRACTION = 0.25

/**
 * Returns true if the parent run may spawn another subagent.
 * Mirrors the TypeScript canSpawnSubagent implementation.
 */
fun canSpawnSubagent(
    parentCostDollars: Double,
    existingSubagentCount: Int,
): Boolean {
    if (existingSubagentCount >= MAX_SUBAGENTS_PER_PARENT_RUN) return false
    val remainingForWork = SUBAGENT_MAX_PARENT_COST_DOLLARS -
        SUBAGENT_PARENT_SYNTHESIS_RESERVE_DOLLARS -
        parentCostDollars
    val minNeeded = SUBAGENT_MAX_COST_DOLLARS * MIN_SUBAGENT_BUDGET_FRACTION
    return remainingForWork >= minNeeded
}

/**
 * Returns how much budget (in dollars) to allocate to the next subagent.
 * Distributes remaining budget evenly across remaining slots, capped at
 * SUBAGENT_MAX_COST_DOLLARS.
 */
fun computeSubagentBudgetAllocation(
    parentRemainingDollars: Double,
    existingSubagentCount: Int,
): Double {
    if (parentRemainingDollars <= 0.0) return 0.0
    val remainingSlots = maxOf(1, MAX_SUBAGENTS_PER_PARENT_RUN - existingSubagentCount)
    val evenShare = parentRemainingDollars / remainingSlots
    return minOf(evenShare, SUBAGENT_MAX_COST_DOLLARS)
}
