package ai.droidcommand.agent

enum class SecurityLevel {
    NORMAL,
    SENSITIVE,
    ROOT,
}

/**
 * Who is asking a tool to run — self-declared by the caller invoking
 * [ai.droidcommand.security.SecureToolExecutor], the same way DroidPilot's
 * `AI_ROOT` gate works. This is a **policy** distinction, not a
 * cryptographic one: nothing here proves a [REMOTE] or [DEVICE_OWNER] claim
 * against a hostile peer who could simply pass a different value, exactly
 * as DroidPilot's own design documents for its equivalent field. Its value
 * is narrowing what an AI-planned action may do by default (e.g. a tool
 * scoped to [DEVICE_OWNER] can never be selected by the agent's own
 * planner loop), not resisting a compromised or malicious caller.
 */
enum class Initiator {
    AI,
    DEVICE_OWNER,
    REMOTE,
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
    /**
     * Optional set of [Initiator]s permitted to invoke this tool (e.g. an
     * `AI_ROOT`/`REMOTE_ROOT`/`REMOTE_SHELL`-style category). Null (the
     * default) means no restriction, so existing tools are unaffected.
     * Checked by [ai.droidcommand.security.SecureToolExecutor] against the
     * initiator the caller declares — see [Initiator]'s own doc for why
     * this is a policy boundary, not a cryptographic one.
     */
    val requiredInitiator: Set<Initiator>? = null,
    /**
     * Optional set of [PermissionCategory] domains this tool touches
     * (CAP-010, P1.2) — orthogonal to [securityLevel] (a risk *level*,
     * not a permission *domain*), and not unified with it in this slice.
     * Empty by default, so every existing tool is unaffected.
     * [ai.droidcommand.security.SecurityPolicyEnforcer.authorize] treats
     * [PermissionCategory.ROOT]/[PermissionCategory.CONTAINER] here as
     * root-equivalent, exactly like [requiresRoot] — the roadmap prompt's
     * own CRITICAL rule that container/Docker socket access must never be
     * presented as a lesser, peer permission.
     */
    val requiredPermissionCategories: Set<PermissionCategory> = emptySet(),
)

sealed class ToolResult {
    data class Success(val output: String) : ToolResult()

    /**
     * The tool ran and produced a genuine result, but only part of what was
     * asked was actually accomplished (e.g. 3 of 5 items processed before
     * stopping). Distinct from [Failure] so a caller doesn't have to treat a
     * partially-done action as either a full success or a total loss — the
     * planner sees [reason] and can decide whether the remainder needs a
     * follow-up action.
     */
    data class Partial(val output: String, val reason: String) : ToolResult()

    /**
     * The tool ran and returned a real result, but one that doesn't fit the
     * success/partial/failure shape the caller anticipated (e.g. a device or
     * service state the tool has no established interpretation for).
     * Reported explicitly rather than forced into [Success] or [Failure],
     * where that ambiguity would otherwise be silently lost.
     */
    data class Unexpected(val description: String, val raw: String? = null) : ToolResult()

    data class Failure(val reason: String, val cause: Throwable? = null) : ToolResult()
}

/**
 * A single human-readable line describing any [ToolResult], used to feed a
 * [ObjectiveEngine]'s conversation context and, identically, an MCP
 * tool-call response's text content (`core-mcp`) — one shared format rather
 * than two call sites independently deciding how to phrase the same four
 * outcome shapes.
 */
fun ToolResult.describe(): String = when (this) {
    is ToolResult.Success -> output
    is ToolResult.Partial -> "PARTIAL: $output ($reason)"
    is ToolResult.Unexpected -> "UNEXPECTED: $description"
    is ToolResult.Failure -> "ERROR: $reason"
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
