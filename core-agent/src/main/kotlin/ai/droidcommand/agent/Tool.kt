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
    /**
     * Which mode(s) this tool may be offered/invoked under. Defaults to both,
     * so existing tools are unaffected; a tool that only makes sense in one
     * mode (e.g. a build/deploy tool that should never be one-shot-invoked
     * from Pilot) declares that explicitly instead of relying on convention.
     */
    val allowedModes: Set<AgentMode> = AgentMode.entries.toSet(),
    /**
     * Optional named capability (e.g. "root", "remote_shell") this tool
     * requires a live grant for, on top of the ordinary security-policy
     * check. Null (the default) means no grant lifecycle applies — most
     * tools, including the existing root/shell tools, are unaffected unless
     * a caller explicitly opts a specific instance into requiring one.
     */
    val grantCapability: String? = null,
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
