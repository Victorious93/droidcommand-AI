package ai.droidforge.agent

enum class AgentMode { PILOT, FORGE }

class IllegalModeSwitch(val from: AgentMode, val to: AgentMode, reason: String) :
    IllegalStateException("Cannot switch from $from to $to: $reason")

/**
 * Coordinates Pilot Mode (a single direct [Tool] invocation) and Forge Mode
 * (an [ObjectiveEngine] loop) over the same [ToolRegistry] / [ToolExecutor] /
 * [AgentStateMachine], and is the single place that guards a mode switch
 * against corrupting a task that is still running. [mode] is the value a UI
 * surfaces as "PILOT" or "FORGE" per the two-mode design in
 * docs/ARCHITECTURE.md.
 *
 * [lock] guards only the [taskActive] test-and-set, not the task execution
 * itself: a mode switch attempted while a task is running must fail
 * immediately, not block until the task finishes, so the lock is never held
 * across a (potentially long-running) [ToolExecutor.run] or
 * [ObjectiveEngine.run] call.
 */
class DroidForgeSession(
    private val registry: ToolRegistry,
    private val executor: ToolExecutor,
    private val stateMachine: AgentStateMachine,
) {
    private val lock = Any()

    @Volatile
    var mode: AgentMode = AgentMode.PILOT
        private set

    @Volatile
    private var taskActive = false

    fun switchMode(to: AgentMode) {
        synchronized(lock) {
            if (taskActive) {
                throw IllegalModeSwitch(mode, to, "a task is currently active")
            }
            mode = to
        }
    }

    fun runPilotInstruction(
        toolName: String,
        input: Map<String, String>,
        retryPolicy: RetryPolicy = RetryPolicy(),
        isCancelled: () -> Boolean = { false },
    ): ToolResult {
        synchronized(lock) {
            check(mode == AgentMode.PILOT) { "runPilotInstruction called while in $mode mode" }
            taskActive = true
        }
        try {
            return executor.run(toolName, input, retryPolicy, isCancelled)
        } finally {
            synchronized(lock) { taskActive = false }
        }
    }

    fun runForgeObjective(
        objective: String,
        planner: Planner,
        context: ConversationContext = ConversationContext(),
        maxIterations: Int = 25,
        retryPolicy: RetryPolicy = RetryPolicy(),
        isCancelled: () -> Boolean = { false },
    ): ObjectiveOutcome {
        synchronized(lock) {
            check(mode == AgentMode.FORGE) { "runForgeObjective called while in $mode mode" }
            taskActive = true
        }
        try {
            val engine = ObjectiveEngine(registry, executor, stateMachine, planner, maxIterations)
            return engine.run(objective, context, retryPolicy, isCancelled)
        } finally {
            synchronized(lock) { taskActive = false }
        }
    }
}
