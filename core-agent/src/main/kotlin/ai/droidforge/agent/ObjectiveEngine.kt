package ai.droidforge.agent

data class ObjectiveOutcome(val finalState: AgentState, val iterations: Int)

/**
 * Runs the Forge Mode loop (UNDERSTAND -> PLAN -> SELECT TOOLS -> EXECUTE ->
 * OBSERVE -> VALIDATE -> RECOVER -> COMPLETE) by repeatedly asking a
 * [Planner] for the next step and, when it selects a tool, running it
 * through [ToolExecutor]. [maxIterations] is the agent-safety bound: a
 * planner that never returns [PlannerDecision.Complete] or
 * [PlannerDecision.Abort] cannot loop forever — the engine gives up and
 * terminates in [AgentState.Failed] instead.
 */
class ObjectiveEngine(
    private val registry: ToolRegistry,
    private val executor: ToolExecutor,
    private val stateMachine: AgentStateMachine,
    private val planner: Planner,
    private val maxIterations: Int = 25,
) {
    init {
        require(maxIterations >= 1) { "maxIterations must be >= 1, got $maxIterations" }
    }

    fun run(
        objective: String,
        context: ConversationContext = ConversationContext(),
        retryPolicy: RetryPolicy = RetryPolicy(),
        isCancelled: () -> Boolean = { false },
    ): ObjectiveOutcome {
        context.append(Role.USER, objective)
        var lastObservation: ToolResult? = null

        for (iteration in 1..maxIterations) {
            if (isCancelled()) {
                val cancelled = stateMachine.transition(AgentState.Cancelled("Cancelled before iteration $iteration"))
                return ObjectiveOutcome(cancelled, iteration - 1)
            }

            stateMachine.transition(AgentState.Planning(objective))
            val decision = try {
                planner.decide(objective, context, registry.list(), lastObservation)
            } catch (t: Throwable) {
                val failed = stateMachine.transition(AgentState.Failed(t))
                return ObjectiveOutcome(failed, iteration)
            }

            when (decision) {
                is PlannerDecision.Complete -> {
                    context.append(Role.ASSISTANT, decision.summary)
                    val completed = stateMachine.transition(AgentState.Completed(decision.summary))
                    return ObjectiveOutcome(completed, iteration)
                }

                is PlannerDecision.Abort -> {
                    val failed = stateMachine.transition(AgentState.Failed(RuntimeException(decision.reason)))
                    return ObjectiveOutcome(failed, iteration)
                }

                is PlannerDecision.InvokeTool -> {
                    context.append(Role.ASSISTANT, "Invoking tool '${decision.toolName}' with ${decision.input}")
                    val result = try {
                        executor.run(decision.toolName, decision.input, retryPolicy, isCancelled)
                    } catch (c: CancellationRequested) {
                        return ObjectiveOutcome(stateMachine.state, iteration - 1)
                    } catch (t: Throwable) {
                        val failed = stateMachine.transition(AgentState.Failed(t))
                        return ObjectiveOutcome(failed, iteration)
                    }
                    lastObservation = result
                    context.append(Role.TOOL, describe(result))
                }
            }
        }

        val failed = stateMachine.transition(
            AgentState.Failed(RuntimeException("Objective did not complete within $maxIterations iterations")),
        )
        return ObjectiveOutcome(failed, maxIterations)
    }

    private fun describe(result: ToolResult): String = when (result) {
        is ToolResult.Success -> result.output
        is ToolResult.Failure -> "ERROR: ${result.reason}"
    }
}
