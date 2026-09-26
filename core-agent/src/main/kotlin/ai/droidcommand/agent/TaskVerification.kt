package ai.droidcommand.agent

/**
 * Whether a [Task] that reached [AgentState.Completed] actually met its
 * [Task.verificationCriteria] — the check [Task]'s own doc comment left to
 * "a caller/planner concern" until this file existed.
 *
 * [Inconclusive] is deliberately distinct from [Failed]: the verifier could
 * not decide at all (a provider error, an unparseable judgment, a criterion
 * the judgment never addressed), versus deciding that a criterion was not
 * met. [TaskGraphExecutor] treats both the same way — fail-closed, the task
 * is not counted as done — but a caller can tell them apart.
 */
sealed class TaskVerification {
    data object Passed : TaskVerification()

    data class Failed(val unmetCriteria: List<String>, val reason: String) : TaskVerification()

    data class Inconclusive(val reason: String) : TaskVerification()
}

/**
 * Judges a completed [Task] against its [Task.verificationCriteria].
 * [TaskGraphExecutor] only calls this for a task whose [ObjectiveOutcome.finalState]
 * is [AgentState.Completed] *and* whose criteria list is non-empty — a
 * verifier never sees a failed, cancelled, or criteria-free task.
 *
 * Backed by `core-llm`'s `LlmTaskVerifier` for free-text criteria; a
 * caller with machine-checkable criteria (a file exists, a build passed)
 * can supply its own deterministic implementation instead.
 */
fun interface TaskVerifier {
    fun verify(task: Task, outcome: ObjectiveOutcome): TaskVerification
}
