package ai.droidcommand.agent

/**
 * Lifecycle states for a single agent task (one Pilot instruction, or one
 * Forge objective). [Completed], [Cancelled], and [Failed] are terminal:
 * [AgentStateMachine] rejects any transition out of them.
 */
sealed class AgentState {
    data object Idle : AgentState()
    data class Planning(val objective: String) : AgentState()
    data class AwaitingApproval(val toolName: String, val reason: String) : AgentState()
    data class ExecutingTool(val toolName: String, val attempt: Int) : AgentState()
    data class Observing(val toolName: String, val result: ToolResult) : AgentState()
    data class Recovering(val cause: Throwable, val attempt: Int) : AgentState()
    data class Completed(val summary: String) : AgentState()
    data class Cancelled(val reason: String) : AgentState()
    data class Failed(val cause: Throwable) : AgentState()
}

class IllegalAgentTransition(from: AgentState, to: AgentState) :
    IllegalStateException("Cannot transition from $from to $to")

/**
 * Validates transitions so a terminated task can never be silently resumed
 * (the concrete mechanism behind "prevent infinite loops" / "never create
 * uncontrolled execution").
 *
 * Thread-safe: [state] is `@Volatile` so a read from a different thread than
 * the one calling [transition] (e.g. a status display polling a
 * [DroidCommandSession] while its work runs on another thread, or
 * `MacroScheduler` firing on its own background thread) is guaranteed to
 * see the latest value rather than a stale or torn one; [transition]'s own
 * check-then-set is `synchronized` so two concurrent transitions can never
 * both pass the terminal-state check before either writes. This closes a
 * real memory-visibility gap, not a cosmetic one — reading a plain `var`
 * across threads has no happens-before guarantee under the JVM memory
 * model, and `MacroScheduler` already introduces exactly that
 * cross-thread pattern.
 *
 * This does **not** make it correct for two unrelated logical tasks (e.g.
 * a live Pilot instruction and a scheduled macro) to share one instance:
 * their transitions would still interleave into a single, meaningless
 * sequence even though no individual read or write is corrupted anymore.
 * `MacroScheduler`'s own doc comment is right to recommend a dedicated
 * instance per independent task; this fix only guarantees that whichever
 * single task owns an instance at a given time observes it correctly
 * across threads.
 */
class AgentStateMachine(initial: AgentState = AgentState.Idle) {
    private val lock = Any()

    @Volatile
    var state: AgentState = initial
        private set

    fun transition(to: AgentState): AgentState {
        synchronized(lock) {
            val from = state
            if (from is AgentState.Completed || from is AgentState.Cancelled || from is AgentState.Failed) {
                throw IllegalAgentTransition(from, to)
            }
            state = to
            return state
        }
    }
}
