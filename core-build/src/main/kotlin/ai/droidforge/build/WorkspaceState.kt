package ai.droidforge.build

import java.time.Instant

enum class WorkspaceState { CREATED, PREPARING, READY, BUILDING, COMPLETED, FAILED, CLEANING, CLEANED }

class IllegalWorkspaceTransition(from: WorkspaceState, to: WorkspaceState) :
    IllegalStateException("Cannot transition workspace from $from to $to")

private val ALLOWED_TRANSITIONS: Map<WorkspaceState, Set<WorkspaceState>> = mapOf(
    WorkspaceState.CREATED to setOf(WorkspaceState.PREPARING, WorkspaceState.FAILED, WorkspaceState.CLEANING),
    WorkspaceState.PREPARING to setOf(WorkspaceState.READY, WorkspaceState.FAILED),
    WorkspaceState.READY to setOf(WorkspaceState.BUILDING, WorkspaceState.CLEANING, WorkspaceState.FAILED),
    WorkspaceState.BUILDING to setOf(WorkspaceState.COMPLETED, WorkspaceState.FAILED),
    WorkspaceState.COMPLETED to setOf(WorkspaceState.CLEANING),
    WorkspaceState.FAILED to setOf(WorkspaceState.CLEANING),
    WorkspaceState.CLEANING to setOf(WorkspaceState.CLEANED, WorkspaceState.FAILED),
    WorkspaceState.CLEANED to emptySet(),
)

/**
 * A single workspace's identity, filesystem location, and lifecycle state.
 * [transition] enforces [ALLOWED_TRANSITIONS] — the same "an illegal
 * transition throws rather than silently succeeding" mechanism
 * `core-agent.AgentStateMachine` uses, applied to workspace lifecycle
 * instead of agent task lifecycle.
 */
class WorkspaceHandle(val workspaceId: String, val rootPath: String, val createdAt: Instant) {
    var state: WorkspaceState = WorkspaceState.CREATED
        private set

    fun transition(to: WorkspaceState): WorkspaceState {
        val allowed = ALLOWED_TRANSITIONS[state] ?: emptySet()
        if (to !in allowed) {
            throw IllegalWorkspaceTransition(state, to)
        }
        state = to
        return state
    }
}
