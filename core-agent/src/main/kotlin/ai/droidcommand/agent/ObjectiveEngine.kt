package ai.droidcommand.agent

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
    private val mode: AgentMode = AgentMode.FORGE,
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
                planner.decide(objective, context, registry.list(mode), lastObservation)
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
                        executor.run(decision.toolName, decision.input, retryPolicy, isCancelled, mode)
                    } catch (c: CancellationRequested) {
                        return ObjectiveOutcome(stateMachine.state, iteration - 1)
                    } catch (u: UnknownToolException) {
                        // The planner named a tool that doesn't exist (or isn't offered in this
                        // mode) despite being handed the real registry — rather than treating
                        // this as a fatal engine failure, tell it exactly what is actually
                        // available and let it replan on the next iteration. Bounded by the
                        // same maxIterations loop, so a planner that keeps inventing tool names
                        // still terminates rather than looping forever.
                        val available = registry.list(mode).joinToString { it.name }
                        val observation = ToolResult.Failure(
                            "Unknown tool '${decision.toolName}'. Available tools in $mode mode: $available",
                        )
                        lastObservation = observation
                        context.append(Role.TOOL, describe(observation))
                        continue
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
