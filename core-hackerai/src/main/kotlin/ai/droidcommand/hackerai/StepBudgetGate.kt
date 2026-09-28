package ai.droidcommand.hackerai

// Ported from hackeraiETC/lib/ai/subagents/runtime-recovery.ts (step budget section)

const val SUBAGENT_RESULT_RECOVERY_STEP_RESERVE = 2

/**
 * Returns the max steps the subagent may use for exploration, reserving
 * [SUBAGENT_RESULT_RECOVERY_STEP_RESERVE] steps for result recovery at the end.
 */
fun getSubagentExplorationStepLimit(remainingSteps: Int): Int =
    maxOf(0, remainingSteps - SUBAGENT_RESULT_RECOVERY_STEP_RESERVE)

/**
 * Returns true when the agent has consumed enough steps that it should start
 * forced finalization (result recovery phase) rather than continuing exploration.
 */
fun shouldStartResultRecovery(stepsTaken: Int, maxSteps: Int = SUBAGENT_MAX_STEPS): Boolean =
    stepsTaken >= maxSteps - SUBAGENT_RESULT_RECOVERY_STEP_RESERVE
