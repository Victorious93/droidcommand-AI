package ai.droidforge.agent

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
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val stateMachine: AgentStateMachine,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun run(
        toolName: String,
        input: Map<String, String>,
        retryPolicy: RetryPolicy = RetryPolicy(),
        isCancelled: () -> Boolean = { false },
    ): ToolResult {
        val tool = registry.get(toolName)

        var lastResult: ToolResult = ToolResult.Failure("Tool never invoked")
        for (attempt in 1..retryPolicy.maxAttempts) {
            if (isCancelled()) {
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

            if (lastResult is ToolResult.Success) return lastResult

            if (attempt < retryPolicy.maxAttempts) {
                stateMachine.transition(AgentState.Recovering((lastResult as ToolResult.Failure).cause ?: RuntimeException(lastResult.reason), attempt))
                sleep(retryPolicy.backoff(attempt))
            }
        }
        return lastResult
    }
}
