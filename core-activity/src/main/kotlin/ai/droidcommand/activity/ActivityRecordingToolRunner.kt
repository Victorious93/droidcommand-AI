package ai.droidcommand.activity

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.describe

/**
 * Records every tool call of one [ActivityRun] — what was asked (arguments redacted) and how it
 * ended — and otherwise behaves exactly like [delegate]: same arguments, same result object, same
 * exceptions. A failure to *record* is swallowed here; observability must never change what a tool
 * does or whether it runs.
 *
 * Place it **outside** the secured runner (`SecureToolExecutor`), so a denial by policy shows up as
 * a failed step, and so the time spent waiting for an approval is attributed to this run through
 * [ActivityScope] (see [ActivityApprovalPrompt]).
 */
class ActivityRecordingToolRunner(
    private val delegate: ToolRunner,
    private val currentRun: () -> ActivityRun?,
    private val log: ActivityLog,
    private val clock: () -> Long = System::currentTimeMillis,
) : ToolRunner {
    /** Records under one fixed [run]. */
    constructor(
        delegate: ToolRunner,
        run: ActivityRun,
        log: ActivityLog,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(delegate, { run }, log, clock)

    override fun run(
        toolName: String,
        input: Map<String, String>,
        retryPolicy: RetryPolicy,
        isCancelled: () -> Boolean,
        mode: AgentMode?,
        initiator: Initiator?,
        grantId: String?,
    ): ToolResult {
        // No current run (for example, a bot between turns): behave exactly like the delegate, record nothing.
        val run = runCatching(currentRun).getOrNull()
            ?: return delegate.run(toolName, input, retryPolicy, isCancelled, mode, initiator, grantId)
        emit(run, ActivityKind.TOOL_STARTED, toolName, ActivityRedactor.args(input).ifBlank { null })
        val result = try {
            ActivityScope.within(run.scope()) {
                delegate.run(toolName, input, retryPolicy, isCancelled, mode, initiator, grantId)
            }
        } catch (t: Throwable) {
            emit(run, ActivityKind.TOOL_FAILED, toolName, ActivityRedactor.text("${t.javaClass.simpleName}: ${t.message.orEmpty()}", 200))
            throw t
        }
        when (result) {
            is ToolResult.Success -> emit(run, ActivityKind.TOOL_FINISHED, toolName, ActivityRedactor.output(result.output).ifBlank { null })
            is ToolResult.Partial -> emit(run, ActivityKind.TOOL_FINISHED, toolName, ActivityRedactor.text("partial: ${result.reason}", 200))
            is ToolResult.Unexpected, is ToolResult.Failure -> emit(run, ActivityKind.TOOL_FAILED, toolName, ActivityRedactor.output(result.describe()))
        }
        return result
    }

    private fun emit(run: ActivityRun, kind: ActivityKind, summary: String, detail: String?) {
        runCatching { log.append(ActivityEvent(run.actor, run.runId, kind, summary, detail, clock())) }
    }
}
