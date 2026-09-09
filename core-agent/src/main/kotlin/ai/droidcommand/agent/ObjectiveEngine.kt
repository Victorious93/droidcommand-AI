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
 *
 * [logger] defaults to [NoOpLogger] (ROADMAP-014) — every existing caller
 * that doesn't pass one sees no behavior change. When a real [Logger] is
 * supplied, it observes the same lifecycle a caller embedding this in a
 * real app needs visibility into: the objective starting, each iteration's
 * planning step, each tool's outcome, and how the run ended. Pilot Mode's
 * direct [ToolExecutor]/`core-security`'s `SecureToolExecutor` calls are
 * not wired to a logger yet — that remains a follow-up, not silently
 * assumed to be covered by this constructor.
 */
class ObjectiveEngine(
    private val registry: ToolRegistry,
    private val executor: ToolExecutor,
    private val stateMachine: AgentStateMachine,
    private val planner: Planner,
    private val maxIterations: Int = 25,
    private val mode: AgentMode = AgentMode.FORGE,
    private val logger: Logger = NoOpLogger,
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
        logger.info("objective_started", mapOf("mode" to mode.name))
        context.append(Role.USER, objective)
        var lastObservation: ToolResult? = null

        for (iteration in 1..maxIterations) {
            if (isCancelled()) {
                logger.warn("objective_cancelled", mapOf("iteration" to iteration.toString()))
                val cancelled = stateMachine.transition(AgentState.Cancelled("Cancelled before iteration $iteration"))
                return ObjectiveOutcome(cancelled, iteration - 1)
            }

            stateMachine.transition(AgentState.Planning(objective))
            val decision = try {
                planner.decide(objective, context, registry.list(mode, Initiator.AI), lastObservation)
            } catch (t: Throwable) {
                logger.error("planner_threw", mapOf("iteration" to iteration.toString()), cause = t)
                val failed = stateMachine.transition(AgentState.Failed(t))
                return ObjectiveOutcome(failed, iteration)
            }

            when (decision) {
                is PlannerDecision.Complete -> {
                    logger.info("objective_completed", mapOf("iteration" to iteration.toString()))
                    context.append(Role.ASSISTANT, decision.summary)
                    val completed = stateMachine.transition(AgentState.Completed(decision.summary))
                    return ObjectiveOutcome(completed, iteration)
                }

                is PlannerDecision.Abort -> {
                    logger.warn("objective_aborted", mapOf("iteration" to iteration.toString(), "reason" to decision.reason))
                    val failed = stateMachine.transition(AgentState.Failed(RuntimeException(decision.reason)))
                    return ObjectiveOutcome(failed, iteration)
                }

                is PlannerDecision.InvokeTool -> {
                    context.append(Role.ASSISTANT, "Invoking tool '${decision.toolName}' with ${decision.input}")
                    val result = try {
                        executor.run(decision.toolName, decision.input, retryPolicy, isCancelled, mode, Initiator.AI)
                    } catch (c: CancellationRequested) {
                        logger.warn("objective_cancelled", mapOf("iteration" to iteration.toString(), "tool" to decision.toolName))
                        return ObjectiveOutcome(stateMachine.state, iteration - 1)
                    } catch (u: UnknownToolException) {
                        // The planner named a tool that doesn't exist (or isn't offered in this
                        // mode) despite being handed the real registry — rather than treating
                        // this as a fatal engine failure, tell it exactly what is actually
                        // available and let it replan on the next iteration. Bounded by the
                        // same maxIterations loop, so a planner that keeps inventing tool names
                        // still terminates rather than looping forever.
                        logger.warn("unknown_tool", mapOf("iteration" to iteration.toString(), "tool" to decision.toolName))
                        val available = registry.list(mode, Initiator.AI).joinToString { it.name }
                        val observation = ToolResult.Failure(
                            "Unknown tool '${decision.toolName}'. Available tools in $mode mode: $available",
                        )
                        lastObservation = observation
                        context.append(Role.TOOL, describe(observation))
                        continue
                    } catch (t: Throwable) {
                        logger.error("tool_threw", mapOf("iteration" to iteration.toString(), "tool" to decision.toolName), cause = t)
                        val failed = stateMachine.transition(AgentState.Failed(t))
                        return ObjectiveOutcome(failed, iteration)
                    }
                    logger.log(
                        LogEvent(
                            levelFor(result),
                            "tool_result",
                            mapOf("iteration" to iteration.toString(), "tool" to decision.toolName, "outcome" to result::class.simpleName.orEmpty()),
                        ),
                    )
                    lastObservation = result
                    context.append(Role.TOOL, describe(result))
                }
            }
        }

        logger.warn("objective_exhausted_iterations", mapOf("maxIterations" to maxIterations.toString()))
        val failed = stateMachine.transition(
            AgentState.Failed(RuntimeException("Objective did not complete within $maxIterations iterations")),
        )
        return ObjectiveOutcome(failed, maxIterations)
    }

    private fun describe(result: ToolResult): String = when (result) {
        is ToolResult.Success -> result.output
        is ToolResult.Partial -> "PARTIAL: ${result.output} (${result.reason})"
        is ToolResult.Unexpected -> "UNEXPECTED: ${result.description}"
        is ToolResult.Failure -> "ERROR: ${result.reason}"
    }

    private fun levelFor(result: ToolResult): LogLevel = when (result) {
        is ToolResult.Success, is ToolResult.Partial -> LogLevel.INFO
        is ToolResult.Unexpected -> LogLevel.WARN
        is ToolResult.Failure -> LogLevel.ERROR
    }
}
