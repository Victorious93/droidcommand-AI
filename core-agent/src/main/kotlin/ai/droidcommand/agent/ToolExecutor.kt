package ai.droidcommand.agent

data class RetryPolicy(
    val maxAttempts: Int = 1,
    val backoff: (attempt: Int) -> Long = { 0L },
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, got $maxAttempts" }
    }
}

class CancellationRequested(reason: String) : RuntimeException(reason)

/**
 * Runs a tool against the shared [AgentStateMachine], applying a bounded
 * [RetryPolicy] on failure. maxAttempts is enforced by [RetryPolicy]'s own
 * invariant (>= 1) plus the loop bound below — there is no code path that
 * retries more than [RetryPolicy.maxAttempts] times.
 *
 * [logger] defaults to [NoOpLogger] (ROADMAP-014) — every existing caller
 * that doesn't pass one sees no behavior change. This is the executor
 * Pilot Mode invokes directly (as well as the one [ObjectiveEngine] drives
 * for Forge Mode), so wiring it here — the follow-up [ObjectiveEngine]'s
 * own doc comment named — gives Pilot Mode the same operational visibility
 * Forge Mode already has.
 *
 * Implements [ToolRunner] so a caller (e.g. [DroidCommandSession]) can hold
 * either this class or `core-security.SecureToolExecutor` behind the same
 * field without knowing which. [grantId] is accepted for that interface's
 * sake and ignored here — grant-gating is `SecureToolExecutor`'s concern.
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val stateMachine: AgentStateMachine,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val logger: Logger = NoOpLogger,
) : ToolRunner {
    override fun run(
        toolName: String,
        input: Map<String, String>,
        retryPolicy: RetryPolicy,
        isCancelled: () -> Boolean,
        mode: AgentMode?,
        initiator: Initiator?,
        grantId: String?,
    ): ToolResult {
        val tool = registry.get(toolName)

        if (mode != null && mode !in tool.spec.allowedModes) {
            logger.warn("tool_mode_rejected", mapOf("tool" to toolName, "mode" to mode.name))
            return ToolResult.Failure("Tool '$toolName' is not available in $mode mode")
        }

        val requiredInitiator = tool.spec.requiredInitiator
        if (initiator != null && requiredInitiator != null && initiator !in requiredInitiator) {
            logger.warn("tool_initiator_rejected", mapOf("tool" to toolName, "initiator" to initiator.name))
            return ToolResult.Failure("Tool '$toolName' requires initiator in $requiredInitiator, but was invoked as $initiator")
        }

        var lastResult: ToolResult = ToolResult.Failure("Tool never invoked")
        for (attempt in 1..retryPolicy.maxAttempts) {
            if (isCancelled()) {
                logger.warn("tool_cancelled", mapOf("tool" to toolName, "attempt" to attempt.toString()))
                stateMachine.transition(AgentState.Cancelled("Cancelled before attempt $attempt of '$toolName'"))
                throw CancellationRequested("Cancelled before attempt $attempt of '$toolName'")
            }

            stateMachine.transition(AgentState.ExecutingTool(toolName, attempt))
            lastResult = try {
                tool.execute(input)
            } catch (t: Throwable) {
                ToolResult.Failure(t.message ?: "Unhandled exception in tool '$toolName'", t)
            }
            stateMachine.transition(AgentState.Observing(toolName, lastResult))
            logger.log(
                LogEvent(
                    levelFor(lastResult),
                    "tool_result",
                    mapOf("tool" to toolName, "attempt" to attempt.toString(), "outcome" to lastResult::class.simpleName.orEmpty()),
                ),
            )

            // Only a Failure is retried: Partial/Unexpected are real results the
            // tool stands behind, just not a clean success — retrying blindly
            // wouldn't necessarily improve them, so that judgment is left to the
            // planner/caller instead of this executor.
            if (lastResult !is ToolResult.Failure) return lastResult

            if (attempt < retryPolicy.maxAttempts) {
                stateMachine.transition(AgentState.Recovering(lastResult.cause ?: RuntimeException(lastResult.reason), attempt))
                sleep(retryPolicy.backoff(attempt))
            }
        }
        return lastResult
    }

    private fun levelFor(result: ToolResult): LogLevel = when (result) {
        is ToolResult.Success, is ToolResult.Partial -> LogLevel.INFO
        is ToolResult.Unexpected -> LogLevel.WARN
        is ToolResult.Failure -> LogLevel.ERROR
    }
}
