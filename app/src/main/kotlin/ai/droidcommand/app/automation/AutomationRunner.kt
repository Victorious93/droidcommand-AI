package ai.droidcommand.app.automation

import ai.droidcommand.agent.MacroExecutor
import ai.droidcommand.agent.MacroOutcome
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.describe

/** The outcome of one run of an automation key, ready to show. [text] is the output, or the reason it did not succeed. */
data class AutomationRunResult(val succeeded: Boolean, val text: String)

/**
 * Runs one automation key as a one-step macro through [runner]. The app passes the gated SecureToolExecutor, so each run
 * waits for the owner's approval. A shell key whose executable is not allowed is refused here, before any dialog, so the
 * owner is never asked to approve a command that cannot run. A macro makes a single attempt, so an AI call is never
 * repeated.
 */
class AutomationRunner(
    private val runner: ToolRunner,
    private val allowedExecutables: () -> Set<String>,
) {
    /** Blocks until the approval dialog is answered and the run finishes: call it off the main thread. */
    fun run(key: AutomationKey): AutomationRunResult {
        val action = key.action
        if (action is AutomationAction.Shell && action.executable !in allowedExecutables()) {
            return AutomationRunResult(
                false,
                "'${action.executable}' is not in the allowed executables. Add it under Allowed executables first.",
            )
        }
        return when (val outcome = MacroExecutor(runner).run(key.toMacro())) {
            is MacroOutcome.Completed -> outcome.completedSteps.lastOrNull()?.result?.toRunResult()
                ?: AutomationRunResult(false, "This key has no steps.")
            is MacroOutcome.StoppedOnFailure -> AutomationRunResult(false, outcome.failure.reason)
            is MacroOutcome.Cancelled -> AutomationRunResult(false, "The run was cancelled.")
        }
    }

    private fun ToolResult.toRunResult(): AutomationRunResult = when (this) {
        is ToolResult.Success -> AutomationRunResult(true, output.ifBlank { "(no output)" })
        is ToolResult.Partial -> AutomationRunResult(true, describe())
        is ToolResult.Unexpected -> AutomationRunResult(false, describe())
        is ToolResult.Failure -> AutomationRunResult(false, reason)
    }
}
