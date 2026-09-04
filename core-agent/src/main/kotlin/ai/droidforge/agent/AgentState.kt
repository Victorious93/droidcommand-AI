package ai.droidforge.agent

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
 */
class AgentStateMachine(initial: AgentState = AgentState.Idle) {
    var state: AgentState = initial
        private set

    fun transition(to: AgentState): AgentState {
        val from = state
        if (from is AgentState.Completed || from is AgentState.Cancelled || from is AgentState.Failed) {
            throw IllegalAgentTransition(from, to)
        }
        state = to
        return state
    }
}
