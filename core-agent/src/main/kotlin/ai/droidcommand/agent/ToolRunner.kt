package ai.droidcommand.agent

/**
 * The shared "run a tool by name" contract [DroidCommandSession]/[ObjectiveEngine] depend on,
 * satisfied today by the plain [ToolExecutor] and by `core-security.SecureToolExecutor` (which
 * wraps a [ToolExecutor] with policy/approval/grant/audit gating before ever invoking it). Neither
 * caller needs to know or care which one it was handed.
 *
 * [grantId] is meaningful only to a grant-gated implementation — [ToolExecutor] accepts and
 * ignores it. Kotlin resolves default parameter values from this base declaration even when a
 * caller holds a concrete subtype, so no existing caller needs to change to keep compiling.
 */
interface ToolRunner {
    fun run(
        toolName: String,
        input: Map<String, String>,
        retryPolicy: RetryPolicy = RetryPolicy(),
        isCancelled: () -> Boolean = { false },
        mode: AgentMode? = null,
        initiator: Initiator? = null,
        grantId: String? = null,
    ): ToolResult
}
