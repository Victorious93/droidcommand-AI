package ai.droidcommand.agent

/** One step of a [Macro]: a tool name plus the fixed input to invoke it with. */
data class MacroStep(val toolName: String, val input: Map<String, String>)

/**
 * A saved, ordered sequence of tool invocations — e.g. a user-authored
 * routine ("silence phone, launch maps, start navigation") or a scheduled
 * automation's stored action list (ROADMAP-127). Distinct from a Forge
 * objective: a macro's steps are fixed in advance by whoever saved it, not
 * chosen live by a [Planner].
 */
data class Macro(val name: String, val steps: List<MacroStep>)

/** One step's outcome as recorded by [MacroExecutor.run]. */
data class MacroStepOutcome(val step: MacroStep, val result: ToolResult)

/** Whether a [Macro] run finished every step, stopped early, or was cancelled. */
sealed class MacroOutcome {
    abstract val completedSteps: List<MacroStepOutcome>

    data class Completed(override val completedSteps: List<MacroStepOutcome>) : MacroOutcome()

    data class StoppedOnFailure(
        override val completedSteps: List<MacroStepOutcome>,
        val failedStep: MacroStep,
        val failure: ToolResult.Failure,
    ) : MacroOutcome()

    data class Cancelled(override val completedSteps: List<MacroStepOutcome>) : MacroOutcome()
}

/**
 * Plays back a fixed [Macro] step by step through the same [ToolExecutor]
 * that Pilot Mode and [ObjectiveEngine] already use — reusing its dispatch,
 * mode/initiator scoping, and retry handling rather than duplicating any of
 * it. This is OD-002's pattern: a saved routine is an intentionally linear
 * executor sharing primitives with the full agent loop, not a second
 * implementation of tool dispatch.
 *
 * Deliberately does **not** replan: there is no [Planner] involved, and a
 * step's [ToolResult.Failure] stops the sequence immediately rather than
 * asking anything to decide what to do next — that is the entire
 * distinction between a macro and a Forge objective. [ToolResult.Partial]
 * and [ToolResult.Unexpected] are real results the tool stands behind (see
 * their own doc comments on [ToolResult]), so — unlike [ToolResult.Failure]
 * — they don't stop playback; a macro's author is assumed to have chosen
 * steps that don't depend on one step's exact outcome shape, the same
 * assumption OD's own linear macro executor makes.
 *
 * An unknown tool name in a step (an authoring bug, since a macro has no
 * planner to replan with) is reported as an ordinary [MacroOutcome.StoppedOnFailure]
 * rather than propagating [UnknownToolException], so a caller only ever
 * has one outcome shape to handle.
 */
class MacroExecutor(
    private val executor: ToolExecutor,
    private val logger: Logger = NoOpLogger,
) {
    fun run(
        macro: Macro,
        retryPolicy: RetryPolicy = RetryPolicy(),
        isCancelled: () -> Boolean = { false },
        mode: AgentMode? = null,
        initiator: Initiator? = null,
    ): MacroOutcome {
        logger.info("macro_started", mapOf("macro" to macro.name, "steps" to macro.steps.size.toString()))
        val completed = mutableListOf<MacroStepOutcome>()

        for (step in macro.steps) {
            val result = try {
                executor.run(step.toolName, step.input, retryPolicy, isCancelled, mode, initiator)
            } catch (c: CancellationRequested) {
                logger.warn("macro_cancelled", mapOf("macro" to macro.name, "completed" to completed.size.toString()))
                return MacroOutcome.Cancelled(completed)
            } catch (u: UnknownToolException) {
                val failure = ToolResult.Failure("Unknown tool '${step.toolName}'", u)
                logger.warn(
                    "macro_stopped_on_failure",
                    mapOf("macro" to macro.name, "tool" to step.toolName, "completed" to completed.size.toString()),
                )
                return MacroOutcome.StoppedOnFailure(completed, step, failure)
            }

            completed.add(MacroStepOutcome(step, result))

            if (result is ToolResult.Failure) {
                logger.warn(
                    "macro_stopped_on_failure",
                    mapOf("macro" to macro.name, "tool" to step.toolName, "completed" to completed.size.toString()),
                )
                return MacroOutcome.StoppedOnFailure(completed, step, result)
            }
        }

        logger.info("macro_completed", mapOf("macro" to macro.name, "steps" to completed.size.toString()))
        return MacroOutcome.Completed(completed)
    }
}
