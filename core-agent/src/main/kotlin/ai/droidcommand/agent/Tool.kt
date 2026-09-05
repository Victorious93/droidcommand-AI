package ai.droidcommand.agent

enum class SecurityLevel {
    NORMAL,
    SENSITIVE,
    ROOT,
}

/** Declarative permission/root/security metadata a tool must publish before it can be registered. */
data class ToolSpec(
    val name: String,
    val description: String,
    val requiredPermissions: Set<String> = emptySet(),
    val requiresRoot: Boolean = false,
    val securityLevel: SecurityLevel = SecurityLevel.NORMAL,
    val requiresConfirmation: Boolean = securityLevel != SecurityLevel.NORMAL,
)

sealed class ToolResult {
    data class Success(val output: String) : ToolResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : ToolResult()
}

/**
 * A tool is the only unit of capability the agent can invoke — Pilot Mode
 * invokes one directly, Forge Mode's planner invokes a sequence of them.
 * Adding a tool means implementing this interface and registering it; it
 * never requires changing [ToolExecutor] or [AgentStateMachine].
 */
interface Tool {
    val spec: ToolSpec

    fun execute(input: Map<String, String>): ToolResult
}
